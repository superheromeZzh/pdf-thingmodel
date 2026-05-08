package com.example.pdftm.ingestion;

import com.example.pdftm.domain.Document;
import com.example.pdftm.domain.DocumentChunk;
import com.example.pdftm.mapper.DocumentChunkMapper;
import com.example.pdftm.mapper.DocumentMapper;
import com.example.pdftm.pdf.PdfTextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF → documents → document_chunks → thing_models 的总编排。
 *
 * 同步流程（v1）：
 *   <ol>
 *     <li>PdfTextExtractor 抽页文本 + 书签</li>
 *     <li>SkeletonExtractor 用便宜 LLM 一次性产出骨架 + chunk 清单</li>
 *     <li>DocumentMapper.insert 写 documents（拿到 documentId）</li>
 *     <li>对每个 detected chunk: documentChunks.insert + ChunkParseService 跑物模型</li>
 *   </ol>
 *
 * 故意不在方法上加 @Transactional——多次 LLM 调用累计可能几十秒，
 * 整体放一个事务里会持有 DB 连接 + 锁太久。每个 mapper 调用各自走短事务；
 * 部分 chunk 失败不阻塞其他 chunk，最终在 IngestionResult.chunks 里按条目报状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final PdfTextExtractor pdfTextExtractor;
    private final SkeletonExtractor skeletonExtractor;
    private final ChunkParseService chunkParseService;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;

    /**
     * 同步执行 PDF → documents → document_chunks → thing_models 的完整 ingestion 流程。
     *
     * @param fileName 文件名（写进 documents.document_name）
     * @param pdfBytes PDF 字节流
     * @return ingestion 结果，含每个 chunk 的最终状态
     */
    public IngestionResult ingest(String fileName, byte[] pdfBytes) {
        // 1. PDF 解析
        PdfTextExtractor.Extracted extract = pdfTextExtractor.extract(pdfBytes);
        log.info("ingest start: file='{}' pages={} bookmarks={}",
                fileName, extract.getPageCount(), extract.getBookmarks().size());

        // 2. 骨架抽取
        ExtractedSkeleton skeleton = skeletonExtractor.extract(fileName, extract);

        // 3. INSERT documents
        Document doc = new Document();
        doc.setDocumentName(fileName);
        doc.setPageCount(extract.getPageCount());
        doc.setSkeletonJson(skeleton.getSkeletonJson());
        documentMapper.insert(doc);
        log.info("ingest doc inserted: documentId={} detectedChunks={}",
                doc.getDocumentId(), skeleton.getDetectedChunks().size());

        // 4. 每 chunk 入库 + 物模型解析
        List<ChunkIngestionStatus> statuses = new ArrayList<>(skeleton.getDetectedChunks().size());
        int parsedCount = 0;
        for (DetectedChunk dc : skeleton.getDetectedChunks()) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(doc.getDocumentId());
            chunk.setChunkName(dc.getChunkName());
            chunk.setPageStart(dc.getPageStart());
            chunk.setPageEnd(dc.getPageEnd());
            chunk.setRawText(PdfTextExtractor.textForPageRange(extract, dc.getPageStart(), dc.getPageEnd()));
            chunk.setSummary(dc.getSummary());

            try {
                documentChunkMapper.insert(chunk);
            } catch (DataIntegrityViolationException e) {
                log.warn("chunk insert failed (likely duplicate chunk_name '{}'): {}",
                        dc.getChunkName(), e.getMessage());
                statuses.add(ChunkIngestionStatus.chunkInsertFailed(dc.getChunkName(), e.getMessage()));
                continue;
            }

            // 5. 物模型解析（失败不阻塞别的 chunk）
            try {
                chunkParseService.parseAndSave(skeleton.getSkeletonJson(), chunk);
                statuses.add(ChunkIngestionStatus.parsed(chunk.getChunkId(), chunk.getChunkName()));
                parsedCount++;
            } catch (Exception e) {
                log.warn("chunk {} model parse failed: {}", chunk.getChunkId(), e.toString());
                statuses.add(ChunkIngestionStatus.parseFailed(
                        chunk.getChunkId(), chunk.getChunkName(), e.getMessage()));
            }
        }

        log.info("ingest done: documentId={} parsed={}/{}",
                doc.getDocumentId(), parsedCount, skeleton.getDetectedChunks().size());

        IngestionResult result = new IngestionResult();
        result.setDocumentId(doc.getDocumentId());
        result.setDocumentName(doc.getDocumentName());
        result.setPageCount(doc.getPageCount());
        result.setDetectedChunkCount(skeleton.getDetectedChunks().size());
        result.setParsedChunkCount(parsedCount);
        result.setChunks(statuses);
        return result;
    }
}

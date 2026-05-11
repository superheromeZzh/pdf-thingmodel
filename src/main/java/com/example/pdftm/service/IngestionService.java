package com.example.pdftm.service;
import com.example.pdftm.utils.PageTextUtils;
import com.example.pdftm.dto.DetectedChunk;
import com.example.pdftm.dto.ExtractedSkeleton;
import com.example.pdftm.vo.IngestionResult;
import com.example.pdftm.vo.ChunkIngestionStatus;

import com.example.pdftm.entity.Document;
import com.example.pdftm.entity.DocumentChunk;
import com.example.pdftm.service.extract.DocumentExtractor;
import com.example.pdftm.service.extract.DocumentExtractorRegistry;
import com.example.pdftm.common.enums.DocumentFormat;
import com.example.pdftm.dto.Extracted;
import com.example.pdftm.mapper.DocumentChunkMapper;
import com.example.pdftm.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档 → documents → document_chunks 的总编排（不含物模型解析）。
 *
 * 同步流程：
 *   <ol>
 *     <li>按 {@link DocumentFormat} 取对应的 {@link DocumentExtractor}，抽页文本 + 书签</li>
 *     <li>SkeletonExtractor 用便宜 LLM 一次性产出骨架 + chunk 清单</li>
 *     <li>DocumentMapper.insert 写 documents（拿到 documentId）</li>
 *     <li>对每个 detected chunk: documentChunks.insert</li>
 *   </ol>
 *
 * 物模型解析（thing_models）不在此处做，由后续 {@link ChunkParseService} 在用户
 * 显式触发时按 chunk 解析。理由：1) 上传只想看到"文档+分块"是否就绪；
 * 2) 强模型一次调用几秒到十几秒，N 个 chunk 串起来上传接口要等几分钟；
 * 3) 解析失败应当是可重试的独立动作，不该污染上传的成败判断。
 *
 * 故意不在方法上加 @Transactional——LLM 调用（骨架抽取）放事务里会长时间持有 DB
 * 连接。每个 mapper 调用各自走短事务；部分 chunk 入库失败不阻塞其他 chunk。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final DocumentExtractorRegistry extractorRegistry;
    private final SkeletonExtractor skeletonExtractor;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;

    /**
     * 同步执行 文档 → documents → document_chunks 的上传 ingestion 流程。
     * 不解析 thing_models，物模型解析由调用方后续显式触发。
     *
     * @param fileName 文件名（写进 documents.document_name）
     * @param bytes    文件字节流
     * @param format   文档格式（由上游 DocumentFormatDetector 判定）
     * @return ingestion 结果，含每个 chunk 的入库状态
     */
    public IngestionResult ingest(String fileName, byte[] bytes, DocumentFormat format) {
        // 1. 文档解析（按格式分发）
        DocumentExtractor extractor = extractorRegistry.get(format);
        Extracted extract = extractor.extract(bytes);
        log.info("ingest start: file='{}' format={} pages={} bookmarks={}",
                fileName, format, extract.getPageCount(), extract.getBookmarks().size());

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

        // 4. 每个 chunk 入库
        List<ChunkIngestionStatus> statuses = new ArrayList<>(skeleton.getDetectedChunks().size());
        int insertedCount = 0;
        for (DetectedChunk dc : skeleton.getDetectedChunks()) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(doc.getDocumentId());
            chunk.setChunkName(dc.getChunkName());
            chunk.setPageStart(dc.getPageStart());
            chunk.setPageEnd(dc.getPageEnd());
            chunk.setRawText(PageTextUtils.textForPageRange(extract, dc.getPageStart(), dc.getPageEnd()));
            chunk.setSummary(dc.getSummary());

            try {
                documentChunkMapper.insert(chunk);
                statuses.add(ChunkIngestionStatus.inserted(chunk.getChunkId(), chunk.getChunkName()));
                insertedCount++;
            } catch (DataIntegrityViolationException e) {
                log.warn("chunk insert failed (likely duplicate chunk_name '{}'): {}",
                        dc.getChunkName(), e.getMessage());
                statuses.add(ChunkIngestionStatus.chunkInsertFailed(dc.getChunkName(), e.getMessage()));
            }
        }

        log.info("ingest done: documentId={} inserted={}/{}",
                doc.getDocumentId(), insertedCount, skeleton.getDetectedChunks().size());

        IngestionResult result = new IngestionResult();
        result.setDocumentId(doc.getDocumentId());
        result.setDocumentName(doc.getDocumentName());
        result.setPageCount(doc.getPageCount());
        result.setDetectedChunkCount(skeleton.getDetectedChunks().size());
        result.setInsertedChunkCount(insertedCount);
        result.setChunks(statuses);
        return result;
    }
}

package com.example.pdftm.service;

import com.example.pdftm.entity.ChunkModel;
import com.example.pdftm.entity.Document;
import com.example.pdftm.entity.DocumentChunk;
import com.example.pdftm.dto.ChunkContext;
import com.example.pdftm.mapper.DocumentChunkMapper;
import com.example.pdftm.mapper.DocumentMapper;
import com.example.pdftm.mapper.ChunkModelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组装"喂给 LLM 的一次性上下文"：documentSummary + 当前 chunk + 当前物模型。
 * PromptBuilder 把 documentSummary 当文档背景拼 prompt。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
public class ChunkContextService {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final ChunkModelMapper chunkModelMapper;

    /**
     * 加载指定 chunk 的"喂给 LLM 的一次性上下文"：骨架 + chunk + 当前物模型。
     *
     * @param chunkId chunk 主键
     * @return 上下文对象；chunkId 或对应 documentId 不存在时抛 {@link IllegalArgumentException}
     */
    public ChunkContext loadByChunkId(Long chunkId) {
        DocumentChunk chunk = documentChunkMapper.selectById(chunkId);
        if (chunk == null) {
            throw new IllegalArgumentException("chunk not found: " + chunkId);
        }
        Document doc = documentMapper.selectById(chunk.getDocumentId());
        if (doc == null) {
            throw new IllegalArgumentException("document not found: " + chunk.getDocumentId());
        }
        ChunkModel current = chunkModelMapper.findByChunkId(chunkId);

        return ChunkContext.builder()
                .documentSummary(doc.getSummary())
                .chunk(chunk)
                .currentThingModel(current)
                .build();
    }
}

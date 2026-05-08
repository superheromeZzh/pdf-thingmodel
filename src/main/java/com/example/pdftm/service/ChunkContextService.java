package com.example.pdftm.service;

import com.example.pdftm.domain.Document;
import com.example.pdftm.domain.DocumentChunk;
import com.example.pdftm.domain.ThingModel;
import com.example.pdftm.dto.ChunkContext;
import com.example.pdftm.mapper.DocumentChunkMapper;
import com.example.pdftm.mapper.DocumentMapper;
import com.example.pdftm.mapper.ThingModelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组装"喂给 LLM 的一次性上下文"：骨架 + 当前 chunk + 当前物模型。
 * skeleton_json 直接整块传过去，PromptBuilder 自己挑 outline / glossary。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
public class ChunkContextService {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final ThingModelMapper thingModelMapper;

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
        ThingModel current = thingModelMapper.findByChunkId(chunkId);

        return ChunkContext.builder()
                .skeleton(doc.getSkeletonJson())
                .chunk(chunk)
                .currentThingModel(current)
                .build();
    }
}

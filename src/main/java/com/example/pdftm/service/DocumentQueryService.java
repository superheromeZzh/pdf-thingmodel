package com.example.pdftm.service;

import com.example.pdftm.domain.DocumentChunk;
import com.example.pdftm.domain.ThingModel;
import com.example.pdftm.dto.ChunkInspectView;
import com.example.pdftm.dto.ChunkListItem;
import com.example.pdftm.dto.DocumentOverview;
import com.example.pdftm.mapper.DocumentChunkMapper;
import com.example.pdftm.mapper.DocumentMapper;
import com.example.pdftm.mapper.ThingModelMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * 读侧查询。承担"展示生成的物模型"场景：
 *   - getOverview      首页卡片
 *   - listChunks       chunk 列表（分页）
 *   - inspectChunk     单 chunk 对照视图（原文 + 物模型）
 *
 * 全部 readOnly 事务。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
public class DocumentQueryService {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final ThingModelMapper thingModelMapper;

    public DocumentOverview getOverview(Long documentId) {
        if (documentId == null) return null;
        return documentMapper.findOverview(documentId);
    }

    public ChunkListPage listChunks(Long documentId, int page, int size) {
        if (documentId == null) {
            return new ChunkListPage(Collections.emptyList(), 0, page, size);
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.min(200, Math.max(1, size));
        int offset = (safePage - 1) * safeSize;

        List<ChunkListItem> items = documentChunkMapper.listChunkSummaries(documentId, offset, safeSize);
        long total = documentChunkMapper.countChunkSummaries(documentId);
        return new ChunkListPage(items, total, safePage, safeSize);
    }

    /**
     * 单 chunk 对照视图：用户点击列表行进入这个接口。
     * 返回 chunk 元信息 + 当前生效物模型。
     */
    public ChunkInspectView inspectChunk(Long chunkId) {
        if (chunkId == null) return null;
        DocumentChunk chunk = documentChunkMapper.selectById(chunkId);
        if (chunk == null) return null;

        ThingModel current = thingModelMapper.findByChunkId(chunkId);

        return ChunkInspectView.builder()
                .chunk(chunk)
                .currentThingModel(current)
                .build();
    }

    @Data
    public static class ChunkListPage {
        private final List<ChunkListItem> items;
        private final long total;
        private final int page;
        private final int size;
    }
}

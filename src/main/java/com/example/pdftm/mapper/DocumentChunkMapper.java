package com.example.pdftm.mapper;

import com.example.pdftm.entity.DocumentChunk;
import com.example.pdftm.vo.ChunkListItem;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * document_chunks 表 mapper。SQL 全部在 src/main/resources/mapper/DocumentChunkMapper.xml。
 */
public interface DocumentChunkMapper {

    /**
     * 按主键查询单条 chunk（含 raw_text 原文）。
     *
     * @param chunkId chunk 主键
     * @return chunk 对象；不存在时返回 null
     */
    DocumentChunk selectById(@Param("chunkId") Long chunkId);

    /**
     * 列出某文档下所有 chunk，按 page_start, chunk_id 升序，不分页。
     *
     * @param documentId 文档主键
     * @return chunk 列表；文档不存在或没有 chunk 时返回空列表
     */
    List<DocumentChunk> listByDocument(@Param("documentId") Long documentId);

    /**
     * 列表页轻量查询：每行带 has_model 标志，故意不返回 raw_text / model 主体。
     *
     * @param documentId 文档主键
     * @param offset     LIMIT/OFFSET 中的 offset，从 0 开始
     * @param size       每页条数
     * @return 当前页的列表项；无数据返回空列表
     */
    List<ChunkListItem> listChunkSummaries(@Param("documentId") Long documentId,
                                           @Param("offset") int offset,
                                           @Param("size") int size);

    /**
     * 配合 {@link #listChunkSummaries(Long, int, int)} 做分页 total 计数。
     *
     * @param documentId 文档主键
     * @return 该文档下的 chunk 总数；文档不存在返回 0
     */
    long countChunkSummaries(@Param("documentId") Long documentId);

    /**
     * 写入新 chunk；执行后 BIGSERIAL 主键回填到入参的 chunkId 字段。
     *
     * @param chunk 待写入对象
     * @return 受影响行数；(document_id, chunk_name) 重复时抛 {@link org.springframework.dao.DataIntegrityViolationException}
     */
    int insert(DocumentChunk chunk);
}

package com.example.pdftm.mapper;

import com.example.pdftm.domain.Document;
import com.example.pdftm.dto.DocumentOverview;
import org.apache.ibatis.annotations.Param;

/**
 * documents 表 mapper。SQL 全部在 src/main/resources/mapper/DocumentMapper.xml。
 */
public interface DocumentMapper {

    /**
     * 按主键查询单条文档元信息（含 skeleton_json 全局骨架）。
     *
     * <p>主要调用方是 ChunkContextService，用 skeleton_json 给 PromptBuilder 拼 LLM 上下文。
     *
     * @param documentId 文档主键
     * @return 文档对象；不存在时返回 null
     */
    Document selectById(@Param("documentId") Long documentId);

    /**
     * 文档首页卡片用：一次聚合算齐 chunk 总数 + 文档元信息 + skeleton_json。
     *
     * <p>底层走 LEFT JOIN document_chunks + GROUP BY，不会因为缺少 chunk 而漏掉文档。
     *
     * @param documentId 文档主键
     * @return 概览 DTO；文档不存在时返回 null
     */
    DocumentOverview findOverview(@Param("documentId") Long documentId);

    /**
     * 写入新文档。BIGSERIAL 主键由数据库分配，回填到入参对象的 {@code documentId} 字段，
     * 调用方拿这个 id 去插 document_chunks。
     *
     * @param document 待写入对象；执行后其 {@code documentId} 会被填上数据库分配的值
     * @return 受影响行数（正常 1）
     */
    int insert(Document document);
}

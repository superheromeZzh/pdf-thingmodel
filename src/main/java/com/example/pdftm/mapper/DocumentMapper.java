package com.example.pdftm.mapper;

import com.example.pdftm.entity.Document;
import com.example.pdftm.vo.DocumentOverview;
import org.apache.ibatis.annotations.Param;

/**
 * documents 表 mapper。SQL 全部在 src/main/resources/mapper/DocumentMapper.xml。
 */
public interface DocumentMapper {

    /**
     * 按主键查询单条文档元信息（含 skeleton_json 全局骨架）。
     *
     * @param documentId 文档主键
     * @return 文档对象；不存在时返回 null
     */
    Document selectById(@Param("documentId") Long documentId);

    /**
     * 一次聚合算齐 chunk 总数 + 文档元信息 + skeleton_json，供首页卡片使用。
     *
     * @param documentId 文档主键
     * @return 概览 DTO；文档不存在时返回 null
     */
    DocumentOverview findOverview(@Param("documentId") Long documentId);

    /**
     * 写入新文档；执行后 BIGSERIAL 主键回填到入参的 documentId 字段。
     *
     * @param document 待写入对象
     * @return 受影响行数
     */
    int insert(Document document);
}

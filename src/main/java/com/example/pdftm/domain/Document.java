package com.example.pdftm.domain;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 一份 PDF 一行；skeleton_json 是全局骨架（阶段 1 便宜 LLM 一次性产出）。
 *
 * skeleton_json 结构契约：
 * <pre>
 * {
 *   "documentMeta":   {...},   // 元信息（产品、版本、文档类型）
 *   "summary":        {...},   // headline + abstract + highlights + scope
 *   "conventions":    {...},   // 默认单位、命名约定、错误码格式
 *   "outline":        [...],   // 章节树（含 type 标签）
 *   "sharedSchemas":  [...],   // 公共数据结构索引
 *   "apiIndex":       [...],   // 扁平 API 列表，给前端跳转用
 *   "glossary":       [...]    // 术语表
 * }
 * </pre>
 *
 * 这一坨整体在每次单 chunk 修改时随 prompt 送给 LLM，
 * 让模型有"全文 anchor + 默认规则"两层全局视野。
 */
@Data
public class Document {

    private Long documentId;

    private String documentName;

    private Integer pageCount;

    private JsonNode skeletonJson;

    private OffsetDateTime createdAt;
}

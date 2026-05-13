package com.example.pdftm.entity;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 一份 PDF 一行；skeleton_json 是全局骨架（阶段 1 便宜 LLM 一次性产出）。
 *
 * skeleton_json 结构契约（极简版）：
 * <pre>
 * { "abstract": "<一段 150-300 字的文档摘要>" }
 * </pre>
 *
 * 取舍：skeleton 只留一段叙述性 abstract 作为编辑期的文档语境。
 * - "规则性"字段（conventions / scope.excludes / glossary）不进 skeleton——
 *   这些会让 LLM 在抽取时瞎填规则，再被下游当真。真正的全局规则
 *   写在 PromptBuilder.buildSystemPrompt 里。
 * - "对 chunk 内容的索引"（outline / sharedSchemas / apiIndex）不进 skeleton——
 *   skeleton 写一次读 N 次但没有刷新机制，索引信息会随 chunk 编辑坍塌。
 *   需要时按需查 document_chunks / thing_models。
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

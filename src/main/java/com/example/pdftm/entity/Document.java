package com.example.pdftm.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 一份 PDF 一行；summary 是阶段 1 便宜 LLM 一次性产出的文档摘要
 * （150-300 字叙述），用作编辑期的文档语境。
 *
 * 取舍：documents 只持久化一段叙述性 summary。
 * - "规则性"字段（默认单位、命名约定、术语表、范围排除等）不存这里——
 *   这些会让 LLM 在抽取时瞎填规则，再被下游当真。真正的全局规则
 *   写在 PromptBuilder.buildSystemPrompt 里。
 * - "对 chunk 内容的索引"（章节树、API 列表、公共 schema 索引）不存这里——
 *   这类数据写一次读 N 次但没有刷新机制，会随 chunk 编辑坍塌。
 *   需要时按需查 document_chunks / thing_models。
 *
 * summary 在每次单 chunk 修改时随 prompt 送给 LLM 作为文档背景。
 * 注意：document_chunks 也有一个 summary 字段，那是 chunk 级一句话摘要——
 * 此处 Document.summary 是文档级一段话摘要，作用域不同。
 */
@Data
public class Document {

    private Long documentId;

    private String documentName;

    private Integer pageCount;

    /** 文档摘要（150-300 字） */
    private String summary;

    private OffsetDateTime createdAt;
}

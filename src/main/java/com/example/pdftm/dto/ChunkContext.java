package com.example.pdftm.dto;

import com.example.pdftm.entity.ChunkModel;
import com.example.pdftm.entity.DocumentChunk;
import lombok.Builder;
import lombok.Data;

/**
 * 喂给 LLM 的一次性上下文。
 *
 * 文档级语境当前只剩一段 documentSummary（来自 documents.summary）；
 * PromptBuilder 直接当背景段拼进 prompt。
 *
 * 注意命名：documentSummary 是文档级摘要（一段 150-300 字），与
 * DocumentChunk.summary（chunk 级一句话摘要）作用域不同——这里加 document 前缀
 * 是为了避免 ChunkContext 内同时持有两层 summary 时读起来混淆。
 */
@Data
@Builder
public class ChunkContext {
    /** 文档级摘要（150-300 字） */
    private String documentSummary;

    /** 当前 chunk 的元信息和原文 */
    private DocumentChunk chunk;

    /** 当前生效物模型；未生成时为 null */
    private ChunkModel currentThingModel;
}

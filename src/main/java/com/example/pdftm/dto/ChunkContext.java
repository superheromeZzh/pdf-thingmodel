package com.example.pdftm.dto;

import com.example.pdftm.entity.ChunkModel;
import com.example.pdftm.entity.DocumentChunk;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * 喂给 LLM 的一次性上下文。
 *
 * skeleton 直接用 documents.skeleton_json 整个 JsonNode 传过去——
 * 极简形态当前只含 { "abstract": "..." }，PromptBuilder 自己挑字段拼 prompt。
 */
@Data
@Builder
public class ChunkContext {
    /** 文档全局骨架（当前结构：{ "abstract": "..." }） */
    private JsonNode skeleton;

    /** 当前 chunk 的元信息和原文 */
    private DocumentChunk chunk;

    /** 当前生效物模型；未生成时为 null */
    private ChunkModel currentThingModel;
}

package com.example.pdftm.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * LLM 输出解析后的结构化结果。
 * 严格对应 PromptBuilder system prompt 里要求的输出格式：
 *   { "patch": [...], "newModel": {...}, "explanation": "...", "warnings": [...] }
 */
@Data
@Builder
public class LlmEditOutput {

    /** RFC 6902 JSON Patch 操作数组（JsonNode 形式，便于直接走 zjsonpatch.apply） */
    private JsonNode patch;

    /** 模型给出的"应用 patch 后的"完整新物模型 */
    private JsonNode newModel;

    /** 模型对修改的简短解释 */
    private String explanation;

    /** 模型自报的风险/不确定项（可空） */
    private List<String> warnings;

    /** 原始返回文本，用于审计与排查 */
    private String rawResponse;
}

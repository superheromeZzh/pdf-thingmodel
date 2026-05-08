package com.example.pdftm.llm;

import lombok.Builder;
import lombok.Data;

/**
 * LLM 调用参数。脱离具体厂商的最小公约数。
 * 各家 SDK 自己再加扩展（function calling、reasoning_effort 等）由实现类处理。
 */
@Data
@Builder
public class LlmCallOptions {
    /** 模型名，如 "claude-sonnet-4-6" / "qwen-max" */
    private String model;

    /** 0.0 适合编辑场景；只有创意类任务才调高 */
    private Double temperature;

    /** 输出 token 上限 */
    private Integer maxTokens;

    /** 让模型按 JSON 严格输出（OpenAI、qwen 都有 response_format=json_object） */
    private Boolean jsonMode;

    public static LlmCallOptions defaultsForEdit() {
        return LlmCallOptions.builder()
                .temperature(0.0)
                .maxTokens(4096)
                .jsonMode(true)
                .build();
    }
}

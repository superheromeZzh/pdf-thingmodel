package com.example.pdftm.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * PromptBuilder 输出。OpenAI / Claude / qwen-max 都接受 system + user 两段消息的形式，
 * 所以这里就直接拆成两段，免得调用方再包一层。
 *
 * imageRefs：可选的多模态附图（PDF 页码切图的对象存储 key）。
 * 调用方负责把这些 key 解析成 base64 或 URL 注入到 user message 的 multimodal content 里——
 * 不同模型 SDK 的多模态输入格式差别太大，本类不做绑定。
 */
@Data
@Builder
public class PromptMessages {
    private String systemPrompt;
    private String userPrompt;

    /** 可选：要带进 prompt 的页码切图 OSS key 列表，按页码升序 */
    private List<String> imageRefs;

    /** 估算的 token 数，便于上层做预算控制和日志 */
    private int estimatedTokens;
}

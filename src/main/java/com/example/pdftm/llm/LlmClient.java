package com.example.pdftm.llm;

import com.example.pdftm.dto.PromptMessages;

/**
 * LLM 调用抽象。本项目对模型本身一无所知，所有具体 SDK（DashScope/Anthropic/OpenAI/本地推理）
 * 都通过实现这个接口接进来。
 *
 * 实现类应该处理：
 *  - 网络重试（5xx / 限流）
 *  - 超时（建议 60s）
 *  - 鉴权
 *  - 多模态：解析 messages.imageRefs，按各家格式注入
 *  - 把厂商特有错误转成统一异常（建议沿用 LlmCallException 自定义体系）
 *
 * 不应该处理：
 *  - 业务级 JSON 解析与校验（这是 LlmEditService 的职责）
 *  - patch 一致性（同上）
 *  - 内容截断（PromptBuilder 已经做了 raw_text 截断）
 */
public interface LlmClient {

    /**
     * 同步发起一次推理。
     *
     * @param messages PromptBuilder 输出的 system + user 双段
     * @param options  调用参数
     * @return 模型原始返回文本（**不**做 JSON 解析；调用方自己处理 ```json``` 围栏等）
     */
    String generate(PromptMessages messages, LlmCallOptions options);
}

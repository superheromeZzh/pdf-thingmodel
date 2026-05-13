package com.example.pdftm.service;

import com.example.pdftm.entity.DocumentChunk;
import com.example.pdftm.entity.ChunkModel;
import com.example.pdftm.dto.ChunkContext;
import com.example.pdftm.dto.PromptMessages;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把 ChunkContext + 用户请求 + schema 拼成 system/user 双消息。
 *
 * 精简版：thing_models 没有版本/审计，所以"最近变更历史"段已经移除。
 */
@Slf4j
@Component
public class PromptBuilder {

    public static final int MAX_RAW_TEXT_CHARS = 12_000;
    private static final int TOKEN_DIVISOR = 3;

    /**
     * 拼装 LLM 编辑物模型用的 system + user 双段消息。
     *
     * @param context           当前 chunk 的上下文（骨架 + chunk + 当前物模型）
     * @param thingModelSchema  物模型 JSON Schema（可空）
     * @param userRequest       用户原话
     * @return 拼好的双段 prompt + 估算 token 数；输入非法时抛 {@link IllegalArgumentException}
     */
    public PromptMessages buildEditPrompt(ChunkContext context,
                                          String thingModelSchema,
                                          String userRequest) {
        if (context == null || context.getChunk() == null) {
            throw new IllegalArgumentException("ChunkContext is required");
        }
        if (userRequest == null || userRequest.isBlank()) {
            throw new IllegalArgumentException("userRequest is required");
        }

        String system = buildSystemPrompt();
        String user = buildUserPrompt(context, thingModelSchema, userRequest);

        int estTokens = (system.length() + user.length()) / TOKEN_DIVISOR;
        if (log.isDebugEnabled()) {
            log.debug("buildEditPrompt: chunkId={} systemChars={} userChars={} estTokens={}",
                    context.getChunk().getChunkId(), system.length(), user.length(), estTokens);
        }

        return PromptMessages.builder()
                .systemPrompt(system)
                .userPrompt(user)
                .estimatedTokens(estTokens)
                .build();
    }

    private String buildSystemPrompt() {
        return """
                你是物模型编辑助手。基于用户提供的 PDF 上下文和当前物模型 JSON，
                按照用户的修改意图产出新的物模型，并解释修改依据。

                # 工作规则
                1. 只修改用户明确要求改动的字段，其它字段必须保持不变。
                2. 输出必须严格符合用户消息里给出的 thing-model JSON Schema。
                3. 默认约定（除非原文或用户明确指定其它）：
                   - 单位：SI；时间秒，温度摄氏
                   - 命名：camelCase
                   - 时间戳：ISO-8601
                   - 响应格式：JSON
                   - 错误码：string
                4. 修改前先在"原文片段"里找依据；找不到依据时不要编造，写到 warnings 里。
                5. 用户仅说"改成 X"时默认指 default 字段；除非用户明确说"上限"/"下限"。
                6. 修改字段时主动校验跨字段约束（如 default 必须落在 [min, max] 区间）。

                # 输出格式（严格 JSON，不要包 markdown 代码块、不要前后多余文本）
                {
                  "patch":      [ <RFC 6902 ops> ],
                  "newModel":   { <完整的新物模型 JSON> },
                  "explanation":"<一两句话说明改了什么以及依据的原文位置>",
                  "warnings":   [ "<可选风险或不确定项>" ]
                }

                后端会校验：把 patch 应用到 currentModel 后必须严格等于 newModel。
                若两者对不上，本次修改将被拒绝并要求重试。
                """;
    }

    private String buildUserPrompt(ChunkContext ctx,
                                   String schema,
                                   String userRequest) {
        StringBuilder sb = new StringBuilder(8 * 1024);
        JsonNode skeleton = ctx.getSkeleton();

        // 1) 文档背景：skeleton 现在只剩一段 abstract
        sb.append("# 文档背景\n");
        String abstractText = text(skeleton, "abstract");
        sb.append(abstractText.isEmpty() ? "(本文档无背景描述)" : abstractText).append('\n');

        // 2) 当前 chunk 元信息
        DocumentChunk chunk = ctx.getChunk();
        sb.append("\n# 当前修改对象\n")
          .append("- 名称: ").append(safe(chunk.getChunkName())).append('\n')
          .append("- 页码: p.").append(chunk.getPageStart())
          .append('-').append(chunk.getPageEnd()).append('\n');
        if (chunk.getSummary() != null && !chunk.getSummary().isBlank()) {
            sb.append("- 摘要: ").append(chunk.getSummary()).append('\n');
        }

        // 3) Schema
        sb.append("\n# 物模型 JSON Schema\n");
        sb.append(schema == null ? "(未提供，按当前 model 结构推断)" : schema).append('\n');

        // 4) 当前物模型
        sb.append("\n# 当前物模型\n");
        ChunkModel current = ctx.getCurrentThingModel();
        if (current == null || current.getThingModel() == null) {
            sb.append("(本 chunk 尚未生成物模型，本次修改将作为初始版本写入)\n{}\n");
        } else {
            sb.append(current.getThingModel().toPrettyString()).append('\n');
        }

        // 5) 原文片段（截断）
        sb.append("\n# 原文片段 (p.").append(chunk.getPageStart())
          .append('-').append(chunk.getPageEnd()).append(")\n");
        String rawText = chunk.getRawText();
        if (rawText == null || rawText.isBlank()) {
            sb.append("(原文未抽取)\n");
        } else if (rawText.length() <= MAX_RAW_TEXT_CHARS) {
            sb.append(rawText).append('\n');
        } else {
            sb.append(rawText, 0, MAX_RAW_TEXT_CHARS)
              .append("\n...[原文截断，省略 ")
              .append(rawText.length() - MAX_RAW_TEXT_CHARS)
              .append(" 字符]\n");
        }

        // 6) 用户请求
        sb.append("\n# 用户本次请求\n").append(userRequest.trim()).append('\n');
        sb.append("\n# 你的输出\n");
        return sb.toString();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = (n == null) ? null : n.get(field);
        return (v == null || v.isNull()) ? "" : v.asText();
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

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
    public static final int MAX_GLOSSARY_ENTRIES = 40;
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
                3. 单位优先采用 SI；时间默认秒，温度默认摄氏。除非用户明确指定其它单位。
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

        // 1) 文档背景（documentMeta + summary）：让模型一句话知道在改哪本书
        sb.append("# 文档背景\n");
        appendDocumentBackground(sb, skeleton);

        // 2) 文档骨架（outline）
        sb.append("\n# 文档骨架\n");
        if (skeleton != null && skeleton.has("outline")) {
            sb.append(skeleton.get("outline").toPrettyString()).append('\n');
        } else {
            sb.append("(本文档暂无骨架信息)\n");
        }

        // 3) 全局约定（conventions）：默认单位/命名/错误码格式
        JsonNode conventions = (skeleton != null) ? skeleton.get("conventions") : null;
        if (conventions != null && !conventions.isEmpty()) {
            sb.append("\n# 全局约定\n");
            sb.append(conventions.toPrettyString()).append('\n');
        }

        // 4) 全局术语表（来自 skeleton.glossary）
        sb.append("\n# 全局术语表\n");
        JsonNode glossary = (skeleton != null) ? skeleton.get("glossary") : null;
        if (glossary == null || !glossary.isArray() || glossary.isEmpty()) {
            sb.append("(无)\n");
        } else {
            int n = Math.min(glossary.size(), MAX_GLOSSARY_ENTRIES);
            for (int i = 0; i < n; i++) {
                JsonNode g = glossary.get(i);
                sb.append("- **").append(text(g, "term")).append("**: ")
                  .append(text(g, "definition")).append('\n');
            }
            if (glossary.size() > n) {
                sb.append("- (省略 ").append(glossary.size() - n).append(" 条)\n");
            }
        }

        // 5) 当前 chunk 元信息
        DocumentChunk chunk = ctx.getChunk();
        sb.append("\n# 当前修改对象\n")
          .append("- 名称: ").append(safe(chunk.getChunkName())).append('\n')
          .append("- 页码: p.").append(chunk.getPageStart())
          .append('-').append(chunk.getPageEnd()).append('\n');
        if (chunk.getSummary() != null && !chunk.getSummary().isBlank()) {
            sb.append("- 摘要: ").append(chunk.getSummary()).append('\n');
        }

        // 6) Schema
        sb.append("\n# 物模型 JSON Schema\n");
        sb.append(schema == null ? "(未提供，按当前 model 结构推断)" : schema).append('\n');

        // 7) 当前物模型
        sb.append("\n# 当前物模型\n");
        ChunkModel current = ctx.getCurrentThingModel();
        if (current == null || current.getThingModel() == null) {
            sb.append("(本 chunk 尚未生成物模型，本次修改将作为初始版本写入)\n{}\n");
        } else {
            sb.append(current.getThingModel().toPrettyString()).append('\n');
        }

        // 8) 原文片段（截断）
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

        // 9) 用户请求
        sb.append("\n# 用户本次请求\n").append(userRequest.trim()).append('\n');
        sb.append("\n# 你的输出\n");
        return sb.toString();
    }

    /**
     * 拼"文档背景"段。优先级：summary.abstract > summary.headline > documentMeta 拼凑 > 占位文本。
     * documentMeta（产品/版本/类型）即使有 summary 也额外列一行——这些 anchor 字段
     * 比段落文本更稳定，模型抓取效率高。
     */
    private void appendDocumentBackground(StringBuilder sb, JsonNode skeleton) {
        JsonNode meta = (skeleton != null) ? skeleton.get("documentMeta") : null;
        JsonNode summary = (skeleton != null) ? skeleton.get("summary") : null;

        // (1) 一行 anchor：产品 + 版本 + 类型
        if (meta != null && !meta.isEmpty()) {
            String product   = text(meta, "product");
            String version   = text(meta, "version");
            String docType   = text(meta, "docType");
            String publisher = text(meta, "publisher");
            StringBuilder line = new StringBuilder();
            if (!product.isEmpty())   line.append(product);
            if (!version.isEmpty())   line.append(' ').append(version);
            if (!docType.isEmpty())   line.append("（").append(docType).append("）");
            if (!publisher.isEmpty()) line.append(" / ").append(publisher);
            if (line.length() > 0) {
                sb.append("- ").append(line).append('\n');
            }
        }

        // (2) 摘要段
        String abstractText = text(summary, "abstract");
        String headline     = text(summary, "headline");
        if (!abstractText.isEmpty()) {
            sb.append(abstractText).append('\n');
        } else if (!headline.isEmpty()) {
            sb.append(headline).append('\n');
        } else if (meta == null || meta.isEmpty()) {
            sb.append("(本文档无背景描述)\n");
        }

        // (3) scope.excludes：明确告诉模型"哪些事不在本文档"，避免编造
        JsonNode scope = (summary == null) ? null : summary.get("scope");
        if (scope != null) {
            JsonNode excludes = scope.get("excludes");
            if (excludes != null && excludes.isArray() && excludes.size() > 0) {
                sb.append("- 不在本文档范围: ");
                for (int i = 0; i < excludes.size(); i++) {
                    if (i > 0) sb.append("、");
                    sb.append(excludes.get(i).asText());
                }
                sb.append('\n');
            }
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = (n == null) ? null : n.get(field);
        return (v == null || v.isNull()) ? "" : v.asText();
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

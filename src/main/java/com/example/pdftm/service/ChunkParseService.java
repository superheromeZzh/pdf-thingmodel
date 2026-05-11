package com.example.pdftm.service;

import com.example.pdftm.entity.Document;
import com.example.pdftm.entity.DocumentChunk;
import com.example.pdftm.dto.PromptMessages;
import com.example.pdftm.dto.LlmCallOptions;
import com.example.pdftm.service.llm.LlmClient;
import com.example.pdftm.common.exception.LlmOutputInvalidException;
import com.example.pdftm.mapper.DocumentChunkMapper;
import com.example.pdftm.mapper.DocumentMapper;
import com.example.pdftm.service.ModificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 * 阶段 3：单个 chunk 首次物模型解析。强模型一次调用 → JSON 校验 → upsert thing_models。
 *
 * 失败有限次重试（带前次错误反馈）。LLM 调用绝不放在事务内——upsertModel 内部各自短事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkParseService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern JSON_FENCE = Pattern.compile(
            "(?s)^\\s*```(?:json)?\\s*(.*?)\\s*```\\s*$");

    /** raw_text 截顶，避免单 chunk 过长把 prompt 撑爆 */
    public static final int MAX_RAW_TEXT_CHARS = 12_000;

    public static final int MAX_ATTEMPTS = 3;

    private final LlmClient llmClient;
    private final ModificationService modificationService;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;

    @Value("${llm.dashscope.strong-model:qwen-max}")
    private String strongModel;

    /**
     * 按 chunkId 触发物模型解析：从库里捞 chunk + 所属文档骨架，再走
     * {@link #parseAndSave(JsonNode, DocumentChunk)}。upsert 语义，已存在的物模型会被覆盖。
     *
     * @param chunkId 已经入库的 chunk 主键
     * @return 解析后的物模型 JSON
     * @throws NoSuchElementException chunk 或所属 document 不存在
     */
    public JsonNode parseChunk(Long chunkId) {
        if (chunkId == null) throw new IllegalArgumentException("chunkId required");
        DocumentChunk chunk = documentChunkMapper.selectById(chunkId);
        if (chunk == null) {
            throw new NoSuchElementException("chunk not found: " + chunkId);
        }
        Document doc = documentMapper.selectById(chunk.getDocumentId());
        if (doc == null) {
            throw new NoSuchElementException(
                    "document not found for chunk " + chunkId + ": documentId=" + chunk.getDocumentId());
        }
        return parseAndSave(doc.getSkeletonJson(), chunk);
    }

    /**
     * 调用强 LLM 解析单个 chunk 的物模型并 upsert 到 thing_models。
     *
     * @param skeletonJson 文档全局骨架
     * @param chunk        当前 chunk（含 chunkId / rawText）
     * @return 解析后的物模型 JSON；连续 {@link #MAX_ATTEMPTS} 次失败抛 {@link LlmOutputInvalidException}
     */
    public JsonNode parseAndSave(JsonNode skeletonJson, DocumentChunk chunk) {
        if (chunk == null || chunk.getChunkId() == null) {
            throw new IllegalArgumentException("chunk with chunkId required");
        }

        LlmCallOptions opts = LlmCallOptions.builder()
                .model(strongModel)
                .temperature(0.0)
                .maxTokens(4096)
                .jsonMode(true)
                .build();

        StringBuilder cumulativeFeedback = new StringBuilder();
        Throwable lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            PromptMessages prompt = buildPrompt(skeletonJson, chunk, cumulativeFeedback.toString());
            String raw;
            try {
                raw = llmClient.generate(prompt, opts);
            } catch (RuntimeException e) {
                log.warn("chunk {} parse attempt {}/{} llm call failed: {}",
                        chunk.getChunkId(), attempt, MAX_ATTEMPTS, e.toString());
                lastError = e;
                if (attempt == MAX_ATTEMPTS) throw e;
                continue;
            }

            try {
                JsonNode model = parseModel(raw);
                modificationService.upsertModel(chunk.getChunkId(), model);
                log.info("chunk {} parsed: attempts={} modelKeys={}",
                        chunk.getChunkId(), attempt,
                        model.isObject() ? model.size() : -1);
                return model;
            } catch (LlmOutputInvalidException e) {
                lastError = e;
                log.warn("chunk {} parse attempt {}/{} invalid output: {}",
                        chunk.getChunkId(), attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    throw new LlmOutputInvalidException(
                            "chunk parse failed after " + MAX_ATTEMPTS + " attempts: " + e.getMessage(), e);
                }
                cumulativeFeedback.append("\n\n[上次输出有问题：")
                        .append(e.getMessage())
                        .append("，请修正后重新输出]");
            }
        }
        throw new LlmOutputInvalidException("unreachable retry loop", lastError);
    }

    // ----------------------------------------------------------- prompt 拼装

    private PromptMessages buildPrompt(JsonNode skeleton, DocumentChunk chunk, String feedback) {
        String system = """
                你是物模型抽取助手。读完用户提供的"文档背景 + 当前 chunk 原文"，
                输出该 chunk 对应的"物模型 JSON"。

                # 输出格式（严格 JSON，不要 markdown 围栏，不要多余文本）
                {
                  "model": { <物模型对象，至少包含 fields 数组或与本文档匹配的字段> },
                  "summary": "<可选：一句话物模型概述>"
                }

                # 工作规则
                1. 字段命名遵循文档"全局约定"（如有）；默认 camelCase。
                2. 单位优先 SI；时间默认秒、温度默认摄氏；除非原文明确指定。
                3. 字段约束（min/max/default/enum 等）必须在原文里有依据；找不到依据宁可留空。
                4. model 必须是对象；输出整体必须是合法 JSON。
                """;

        StringBuilder user = new StringBuilder(8 * 1024);

        user.append("# 文档背景\n");
        appendBackground(user, skeleton);

        user.append("\n# 全局约定\n");
        JsonNode conv = (skeleton == null) ? null : skeleton.get("conventions");
        if (conv != null && !conv.isEmpty()) {
            user.append(conv.toPrettyString()).append('\n');
        } else {
            user.append("(无)\n");
        }

        user.append("\n# 当前 chunk\n");
        user.append("- 名称: ").append(safe(chunk.getChunkName())).append('\n');
        user.append("- 页码: p.").append(chunk.getPageStart()).append('-').append(chunk.getPageEnd()).append('\n');
        if (chunk.getSummary() != null && !chunk.getSummary().isBlank()) {
            user.append("- 一句话摘要: ").append(chunk.getSummary()).append('\n');
        }

        user.append("\n# 原文（截顶 ").append(MAX_RAW_TEXT_CHARS).append(" 字符）\n");
        String raw = chunk.getRawText();
        if (raw == null || raw.isBlank()) {
            user.append("(原文未抽取)\n");
        } else if (raw.length() <= MAX_RAW_TEXT_CHARS) {
            user.append(raw).append('\n');
        } else {
            user.append(raw, 0, MAX_RAW_TEXT_CHARS)
                .append("\n...[原文截断，省略 ")
                .append(raw.length() - MAX_RAW_TEXT_CHARS)
                .append(" 字符]\n");
        }

        if (feedback != null && !feedback.isBlank()) {
            user.append("\n# 反馈\n").append(feedback.trim()).append('\n');
        }
        user.append("\n# 你的输出\n");

        return PromptMessages.builder()
                .systemPrompt(system)
                .userPrompt(user.toString())
                .estimatedTokens((system.length() + user.length()) / 3)
                .build();
    }

    private void appendBackground(StringBuilder sb, JsonNode skeleton) {
        if (skeleton == null) {
            sb.append("(无骨架信息)\n");
            return;
        }
        JsonNode meta = skeleton.get("documentMeta");
        if (meta != null && !meta.isEmpty()) {
            String product = textOr(meta, "product");
            String version = textOr(meta, "version");
            String docType = textOr(meta, "docType");
            if (!product.isEmpty() || !version.isEmpty() || !docType.isEmpty()) {
                sb.append("- ");
                if (!product.isEmpty()) sb.append(product);
                if (!version.isEmpty()) sb.append(' ').append(version);
                if (!docType.isEmpty()) sb.append("（").append(docType).append("）");
                sb.append('\n');
            }
        }
        JsonNode summary = skeleton.get("summary");
        String abs = (summary == null) ? "" : textOr(summary, "abstract");
        if (!abs.isEmpty()) sb.append(abs).append('\n');
    }

    // ----------------------------------------------------------- response 解析

    JsonNode parseModel(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new LlmOutputInvalidException("empty LLM response");
        }
        String stripped = JSON_FENCE.matcher(raw).replaceAll("$1").trim();
        JsonNode root;
        try {
            root = MAPPER.readTree(stripped);
        } catch (Exception e) {
            throw new LlmOutputInvalidException("响应不是合法 JSON: " + e.getMessage(), e);
        }
        JsonNode model = root.get("model");
        if (model == null || !model.isObject()) {
            throw new LlmOutputInvalidException("响应缺少 'model' 对象字段");
        }
        return model;
    }

    private static String textOr(JsonNode n, String field) {
        JsonNode v = (n == null) ? null : n.get(field);
        return (v == null || v.isNull()) ? "" : v.asText("");
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

package com.example.pdftm.service;
import com.example.pdftm.utils.PageTextUtils;
import com.example.pdftm.dto.DetectedChunk;
import com.example.pdftm.dto.ExtractedSkeleton;

import com.example.pdftm.dto.PromptMessages;
import com.example.pdftm.dto.Bookmark;
import com.example.pdftm.dto.Extracted;
import com.example.pdftm.dto.LlmCallOptions;
import com.example.pdftm.service.llm.LlmClient;
import com.example.pdftm.common.error.ErrorCode;
import com.example.pdftm.common.exception.LlmOutputInvalidException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/**
 * 阶段 1：便宜 LLM 一次性产出文档骨架 + 检测到的 chunk 清单。
 *
 * 输入只送：
 *   - 文件名
 *   - 头 5 页 + 尾 5 页正文（context 节流）
 *   - 整本书签（PDFBox 抽取得到，是定位 chunk 边界的主要依据）
 *
 * 输出严格 JSON：
 * <pre>
 * {
 *   "skeleton": { documentMeta, summary, conventions, outline, sharedSchemas, apiIndex, glossary },
 *   "detectedChunks": [ { chunkName, pageStart, pageEnd, summary } ]
 * }
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkeletonExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 防御性：剥掉 ```json ... ``` 围栏，万一模型不听话。 */
    private static final Pattern JSON_FENCE = Pattern.compile(
            "(?s)^\\s*```(?:json)?\\s*(.*?)\\s*```\\s*$");

    /** 喂给 LLM 的"前 N 页" */
    private static final int HEAD_PAGES = 5;
    /** 喂给 LLM 的"后 N 页" */
    private static final int TAIL_PAGES = 5;

    private final LlmClient llmClient;

    @Value("${llm.dashscope.cheap-model:qwen-turbo}")
    private String cheapModel;

    /**
     * 调用便宜 LLM 从文档头尾正文 + 书签里抽出全局骨架与 chunk 清单。
     *
     * @param fileName 文件名（写进 prompt 给模型识别用）
     * @param doc      已解析的文档内容（PDF/DOCX/TXT 任一来源）
     * @return 抽取结果；LLM 输出非法时抛 {@link com.example.pdftm.common.exception.LlmOutputInvalidException}
     */
    public ExtractedSkeleton extract(String fileName, Extracted doc) {
        if (doc == null) throw new IllegalArgumentException("doc required");

        PromptMessages prompt = buildPrompt(fileName, doc);
        LlmCallOptions opts = LlmCallOptions.builder()
                .model(cheapModel)
                .temperature(0.0)
                .maxTokens(8192)
                .jsonMode(true)
                .build();

        String raw = llmClient.generate(prompt, opts);
        ExtractedSkeleton out = parseOutput(raw, doc.getPageCount());
        log.info("skeleton extracted: detectedChunks={} skeletonKeys={}",
                out.getDetectedChunks().size(),
                out.getSkeletonJson() != null ? out.getSkeletonJson().size() : 0);
        return out;
    }

    // ----------------------------------------------------------- prompt 拼装

    private PromptMessages buildPrompt(String fileName, Extracted doc) {
        String system = """
                你是文档骨架抽取助手。读完用户提供的"文件名 + 头尾正文 + 整本书签"，
                按下面 schema 输出严格 JSON（不要 markdown 围栏，不要前后多余文本）。

                # 输出 schema
                {
                  "skeleton": {
                    "documentMeta": {
                      "docType":   "device_api_spec | datasheet | user_manual | sdk_doc",
                      "product":   "...",
                      "version":   "...",
                      "publisher": "...",
                      "publishDate":"YYYY-MM-DD",
                      "language":  "zh | en | ..."
                    },
                    "summary": {
                      "headline":  "<一行 40-60 字>",
                      "abstract":  "<一段 150-300 字>",
                      "highlights":["<3-5 个 bullet>"],
                      "scope":     { "covers":[], "excludes":[], "audience":"..." }
                    },
                    "conventions": {
                      "defaultUnits":   { "time":"second", "temperature":"celsius" },
                      "naming":         "camelCase | snake_case",
                      "responseFormat": "JSON | XML",
                      "errorCodeFormat":"string | int | enum",
                      "timestampFormat":"ISO-8601 | unix",
                      "notes":          ["..."]
                    },
                    "outline":       [{"title":"...", "pageStart":1, "pageEnd":3, "level":1, "type":"overview | api_definition | ..."}],
                    "sharedSchemas": [{"name":"Pagination", "definedAt":{"page":5}}],
                    "apiIndex":      [{"apiName":"GET /...", "page":7, "summary":"..."}],
                    "glossary":      [{"term":"...", "definition":"...", "pageRef":2}]
                  },
                  "detectedChunks": [
                    { "chunkName":"<API 名或章节标题>", "pageStart":1, "pageEnd":3, "summary":"<一句话>" }
                  ]
                }

                # 工作规则
                1. detectedChunks 是后续单 chunk 物模型解析的目标；优先以书签为权威边界，
                   书签缺失时再从首尾页正文推断。
                2. 每个 detectedChunk 必须给 pageStart/pageEnd 闭区间（1-based），pageEnd >= pageStart。
                3. chunkName 必须在本文档内唯一（重名会被数据库 UNIQUE 约束拒绝）。
                4. 没有把握的字段宁可留空字符串/空数组，不要编造。
                5. 输出必须是合法 JSON，可以被 JSON.parse 直接解析。
                """;

        StringBuilder user = new StringBuilder(8 * 1024);
        user.append("# 文件名\n").append(fileName).append('\n');
        user.append("\n# 总页数\n").append(doc.getPageCount()).append('\n');

        user.append("\n# 书签（按出现顺序，深度优先扁平化）\n");
        if (doc.getBookmarks() == null || doc.getBookmarks().isEmpty()) {
            user.append("(本文档无书签；请从正文里推断 chunk 边界)\n");
        } else {
            for (Bookmark b : doc.getBookmarks()) {
                user.append("- ").append("  ".repeat(Math.max(0, b.getLevel())));
                user.append(b.getTitle());
                if (b.getPage() != null) user.append("  (p.").append(b.getPage()).append(')');
                user.append('\n');
            }
        }

        user.append("\n# 头尾正文片段（用于 documentMeta + summary + conventions）\n");
        user.append(PageTextUtils.headAndTailText(doc, HEAD_PAGES, TAIL_PAGES));
        user.append("\n# 你的输出\n");

        return PromptMessages.builder()
                .systemPrompt(system)
                .userPrompt(user.toString())
                .estimatedTokens((system.length() + user.length()) / 3)
                .build();
    }

    // ----------------------------------------------------------- response 解析

    /**
     * 解析骨架抽取响应。整体走"宽进严出"：
     * 顶层结构必须完整（否则抛 {@link LlmOutputInvalidException} 触发重试），
     * 单条 detectedChunk 字段缺失/越界则降级（log + skip 或钳位），不阻断整体。
     */
    ExtractedSkeleton parseOutput(String raw, int totalPages) {
        JsonNode root      = parseJson(raw);
        JsonNode skeleton  = requireField(root, "skeleton",       JsonNode::isObject, "object");
        JsonNode chunksArr = requireField(root, "detectedChunks", JsonNode::isArray,  "array");

        List<DetectedChunk> chunks = StreamSupport.stream(chunksArr.spliterator(), false)
                .map(node -> toDetectedChunk(node, totalPages))
                .flatMap(Optional::stream)
                .toList();

        if (chunks.isEmpty()) {
            throw new LlmOutputInvalidException(ErrorCode.SKELETON_DETECTED_CHUNKS_EMPTY);
        }

        // 强制 ObjectNode 副本：下游 PromptBuilder 会 .get("outline") 等，避免共享引用 + 类型保证
        return ExtractedSkeleton.builder()
                .skeletonJson((ObjectNode) skeleton.deepCopy())
                .detectedChunks(chunks)
                .build();
    }

    /** 剥围栏 → 解析为 JsonNode；失败抛带错误码的异常 */
    private static JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new LlmOutputInvalidException(ErrorCode.LLM_RESPONSE_EMPTY);
        }
        String stripped = JSON_FENCE.matcher(raw).replaceAll("$1").trim();
        try {
            return MAPPER.readTree(stripped);
        } catch (Exception e) {
            throw new LlmOutputInvalidException(ErrorCode.LLM_RESPONSE_NOT_JSON, e, e.getMessage());
        }
    }

    /** 取顶层字段并按类型谓词校验；不存在或类型错抛带错误码的异常 */
    private static JsonNode requireField(JsonNode root,
                                         String field,
                                         Predicate<JsonNode> typeCheck,
                                         String expectedType) {
        JsonNode node = root.get(field);
        if (node == null || !typeCheck.test(node)) {
            throw new LlmOutputInvalidException(ErrorCode.SKELETON_FIELD_INVALID, field, expectedType);
        }
        return node;
    }

    /** 单条 detectedChunk → DetectedChunk；必填缺失返回空，页码越界则钳到合法闭区间 */
    private Optional<DetectedChunk> toDetectedChunk(JsonNode node, int totalPages) {
        String  name = textOr(node, "chunkName", null);
        Integer ps   = intOr (node, "pageStart", null);
        Integer pe   = intOr (node, "pageEnd",   null);
        if (name == null || name.isBlank() || ps == null || pe == null) {
            log.warn("跳过非法 detectedChunk: {}", node);
            return Optional.empty();
        }
        int safeStart = clamp(ps, 1,         totalPages);
        int safeEnd   = clamp(pe, safeStart, totalPages);
        return Optional.of(new DetectedChunk(name.trim(), safeStart, safeEnd, textOr(node, "summary", null)));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }

    private static String textOr(JsonNode n, String field, String dflt) {
        JsonNode v = (n == null) ? null : n.get(field);
        return (v == null || v.isNull()) ? dflt : v.asText(dflt);
    }

    private static Integer intOr(JsonNode n, String field, Integer dflt) {
        JsonNode v = (n == null) ? null : n.get(field);
        if (v == null || v.isNull()) return dflt;
        if (v.isInt() || v.isLong()) return v.asInt();
        try {
            return Integer.parseInt(v.asText().trim());
        } catch (Exception e) {
            return dflt;
        }
    }
}

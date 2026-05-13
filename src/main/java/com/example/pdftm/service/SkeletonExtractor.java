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
 * 输出严格 JSON（顶层扁平）：
 * <pre>
 * {
 *   "summary": "<一段 150-300 字的文档级摘要>",
 *   "detectedChunks": [ { chunkName, pageStart, pageEnd, summary } ]
 * }
 * </pre>
 *
 * 注意 summary 出现在两层：顶层是文档级（一段话），detectedChunks 内是 chunk 级（一句话），
 * 占位文案已明确区分。
 *
 * 设计取舍：只留一段叙述性 summary 作为编辑期的文档语境。
 * 任何"规则性"字段（conventions / scope.excludes / glossary）和
 * "对 chunk 内容的索引"（outline / sharedSchemas / apiIndex）都不抽——
 * 前者会让 LLM 在抽取时瞎填规则，后者会随 chunk 编辑坍塌。
 * 真正的全局规则写在 PromptBuilder.buildSystemPrompt 里。
 *
 * pageEnd 规则：相邻 chunk 在边界页重叠（chunks[i].pageEnd = chunks[i+1].pageStart）；
 * 最后一个 chunk 由 LLM 根据尾部正文判断实际结束页——不要默认延伸到总页数，
 * 因为文档末尾常有附录/索引/版权页等与最后一个 chunk 无关的内容。
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
        log.info("skeleton extracted: detectedChunks={} summaryChars={}",
                out.getDetectedChunks().size(),
                out.getSummary() == null ? 0 : out.getSummary().length());
        return out;
    }

    // ----------------------------------------------------------- prompt 拼装

    private PromptMessages buildPrompt(String fileName, Extracted doc) {
        String system = """
                你是文档骨架抽取助手。读完用户提供的"文件名 + 头尾正文 + 整本书签"，
                按下面 schema 输出严格 JSON（不要 markdown 围栏，不要前后多余文本）。

                # 输出 schema（顶层扁平，无 skeleton 包装）
                {
                  "summary": "<【文档级】一段 150-300 字摘要：覆盖范围、面向的产品/版本、关键内容>",
                  "detectedChunks": [
                    { "chunkName":"<API 名或章节标题>", "pageStart":1, "pageEnd":3, "summary":"<【chunk 级】一句话摘要>" }
                  ]
                }

                # 工作规则
                1. detectedChunks 是后续单 chunk 物模型解析的目标；优先以书签为权威边界，
                   书签缺失时再从首尾页正文推断。文档末尾若有附录/索引/版权页/空白页等
                   与 API 或章节内容无关的部分，**不要为它们生成 chunk**。
                2. detectedChunks 按 pageStart 升序排列，1-based。
                3. pageEnd 规则：
                   - 非最后一个 chunk：pageEnd **必须等于下一个 chunk 的 pageStart**
                     （相邻 chunk 在边界页重叠 1 页，避免漏掉跨页内容）。
                   - 最后一个 chunk：根据"尾部正文片段"判断该 chunk 内容实际结束在哪一页，
                     pageEnd 设为该实际结束页。**不要默认设成总页数**——若尾部明显是
                     附录/索引/版权等不属于该 chunk 的内容，pageEnd 应止于内容真正结束的位置。
                4. chunkName 必须在本文档内唯一（重名会被数据库 UNIQUE 约束拒绝）。
                5. 没有把握的字段宁可留空字符串/空数组，不要编造。
                6. 输出必须是合法 JSON，可以被 JSON.parse 直接解析。
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
        JsonNode chunksArr = requireField(root, "detectedChunks", JsonNode::isArray,  "array");

        // 顶层 summary 可选：缺失/类型错时回退为空串，记一行 warn 即可，不阻断 ingestion
        String docSummary = "";
        JsonNode summaryNode = root.get("summary");
        if (summaryNode != null && summaryNode.isTextual()) {
            docSummary = summaryNode.asText();
        } else if (summaryNode != null) {
            log.warn("文档级 summary 类型异常（非字符串），按空串处理: {}", summaryNode.getNodeType());
        }

        List<DetectedChunk> chunks = StreamSupport.stream(chunksArr.spliterator(), false)
                .map(node -> toDetectedChunk(node, totalPages))
                .flatMap(Optional::stream)
                .toList();

        if (chunks.isEmpty()) {
            throw new LlmOutputInvalidException(ErrorCode.SKELETON_DETECTED_CHUNKS_EMPTY);
        }

        return ExtractedSkeleton.builder()
                .summary(docSummary)
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
        String  chunkName = readText(node, "chunkName");
        Integer pageStart = readInt (node, "pageStart");
        Integer pageEnd   = readInt (node, "pageEnd");
        if (chunkName == null || chunkName.isBlank() || pageStart == null || pageEnd == null) {
            log.warn("跳过非法 detectedChunk: {}", node);
            return Optional.empty();
        }
        int safeStart = clamp(pageStart, 1,         totalPages);
        int safeEnd   = clamp(pageEnd,   safeStart, totalPages);
        return Optional.of(new DetectedChunk(chunkName.trim(), safeStart, safeEnd, readText(node, "summary")));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    /** 取文本字段；字段缺失、JSON null、或值是数组/对象等非文本类型时返回 null */
    private static String readText(JsonNode node, String field) {
        JsonNode value = (node == null) ? null : node.get(field);
        if (value == null || value.isNull()) return null;
        return value.asText(null);                 // 容器类型 → null（Jackson 语义）
    }

    /** 取整数字段；兼容字符串数字（如 "5"）；非法或缺失时返回 null */
    private static Integer readInt(JsonNode node, String field) {
        JsonNode value = (node == null) ? null : node.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isInt() || value.isLong()) return value.asInt();
        try {
            return Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

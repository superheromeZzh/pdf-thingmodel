package com.example.pdftm.ingestion;

import com.example.pdftm.dto.PromptMessages;
import com.example.pdftm.llm.LlmCallOptions;
import com.example.pdftm.llm.LlmClient;
import com.example.pdftm.llm.LlmOutputInvalidException;
import com.example.pdftm.pdf.PdfTextExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 阶段 1：便宜 LLM 一次性产出文档骨架 + 检测到的 chunk 清单。
 *
 * <p>输入只送：
 *   - 文件名
 *   - 头 5 页 + 尾 5 页正文（context 节流）
 *   - 整本书签（PDFBox 抽取得到，是定位 chunk 边界的主要依据）
 *
 * <p>输出严格 JSON：
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

    public ExtractedSkeleton extract(String fileName, PdfTextExtractor.Extracted pdf) {
        if (pdf == null) throw new IllegalArgumentException("pdf required");

        PromptMessages prompt = buildPrompt(fileName, pdf);
        LlmCallOptions opts = LlmCallOptions.builder()
                .model(cheapModel)
                .temperature(0.0)
                .maxTokens(8192)
                .jsonMode(true)
                .build();

        String raw = llmClient.generate(prompt, opts);
        ExtractedSkeleton out = parseOutput(raw, pdf.getPageCount());
        log.info("skeleton extracted: detectedChunks={} skeletonKeys={}",
                out.getDetectedChunks().size(),
                out.getSkeletonJson() != null ? out.getSkeletonJson().size() : 0);
        return out;
    }

    // ----------------------------------------------------------- prompt 拼装

    private PromptMessages buildPrompt(String fileName, PdfTextExtractor.Extracted pdf) {
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
        user.append("\n# 总页数\n").append(pdf.getPageCount()).append('\n');

        user.append("\n# 书签（按出现顺序，深度优先扁平化）\n");
        if (pdf.getBookmarks() == null || pdf.getBookmarks().isEmpty()) {
            user.append("(本 PDF 无书签；请从正文里推断 chunk 边界)\n");
        } else {
            for (PdfTextExtractor.Bookmark b : pdf.getBookmarks()) {
                user.append("- ").append("  ".repeat(Math.max(0, b.getLevel())));
                user.append(b.getTitle());
                if (b.getPage() != null) user.append("  (p.").append(b.getPage()).append(')');
                user.append('\n');
            }
        }

        user.append("\n# 头尾正文片段（用于 documentMeta + summary + conventions）\n");
        user.append(PdfTextExtractor.headAndTailText(pdf, HEAD_PAGES, TAIL_PAGES));
        user.append("\n# 你的输出\n");

        return PromptMessages.builder()
                .systemPrompt(system)
                .userPrompt(user.toString())
                .estimatedTokens((system.length() + user.length()) / 3)
                .build();
    }

    // ----------------------------------------------------------- response 解析

    ExtractedSkeleton parseOutput(String raw, int totalPages) {
        if (raw == null || raw.isBlank()) {
            throw new LlmOutputInvalidException("empty LLM response");
        }
        String stripped = JSON_FENCE.matcher(raw).replaceAll("$1").trim();

        JsonNode root;
        try {
            root = MAPPER.readTree(stripped);
        } catch (Exception e) {
            throw new LlmOutputInvalidException("骨架抽取响应不是合法 JSON: " + e.getMessage(), e);
        }

        JsonNode skeleton = root.get("skeleton");
        if (skeleton == null || !skeleton.isObject()) {
            throw new LlmOutputInvalidException("骨架抽取响应缺 'skeleton' 对象字段");
        }
        JsonNode chunksNode = root.get("detectedChunks");
        if (chunksNode == null || !chunksNode.isArray() || chunksNode.isEmpty()) {
            throw new LlmOutputInvalidException("骨架抽取响应缺 'detectedChunks' 数组（或为空）");
        }

        List<DetectedChunk> chunks = new ArrayList<>();
        for (JsonNode c : chunksNode) {
            String name = textOr(c, "chunkName", null);
            Integer ps = intOr(c, "pageStart", null);
            Integer pe = intOr(c, "pageEnd", null);
            String summary = textOr(c, "summary", null);
            if (name == null || name.isBlank() || ps == null || pe == null) {
                log.warn("跳过非法 detectedChunk: {}", c.toString());
                continue;
            }
            // 钳到合法页码范围；闭区间 + 顺序
            int safeStart = Math.max(1, Math.min(ps, totalPages));
            int safeEnd   = Math.max(safeStart, Math.min(pe, totalPages));
            chunks.add(new DetectedChunk(name.trim(), safeStart, safeEnd, summary));
        }
        if (chunks.isEmpty()) {
            throw new LlmOutputInvalidException("detectedChunks 全部非法/被过滤");
        }

        // skeletonJson 强制是对象，避免后续 PromptBuilder.skeleton.get("outline") 之类炸 NPE
        ObjectNode skeletonObj = skeleton.deepCopy();
        return ExtractedSkeleton.builder()
                .skeletonJson(skeletonObj)
                .detectedChunks(chunks)
                .build();
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

package com.example.pdftm.service;

import com.example.pdftm.domain.ChunkModel;
import com.example.pdftm.dto.ChunkContext;
import com.example.pdftm.dto.EditPreview;
import com.example.pdftm.dto.LlmEditOutput;
import com.example.pdftm.dto.ModelDiff;
import com.example.pdftm.dto.PromptMessages;
import com.example.pdftm.llm.LlmCallOptions;
import com.example.pdftm.llm.LlmClient;
import com.example.pdftm.llm.LlmOutputInvalidException;
import com.example.pdftm.llm.PatchInconsistentException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.zjsonpatch.JsonPatch;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 自然语言修改的总编排（"用户说一句话→新模型 upsert+前后对照返回"）。
 *
 * 数据流：
 *   loadContext → buildPrompt → callLlm → parseOutput
 *                → schemaValidate → patchEqualsNewModel
 *                → ModificationService.upsertModel (事务内)
 *                → ModelDiffService.diff(before, after)
 *                → 返回 EditPreview 给前端
 *
 * LLM 调用绝不放在事务内（持锁 + 持连接 几十秒会拖垮连接池）。
 * 只有 upsertModel 是事务边界。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmEditService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 防御性：剥掉 ```json ... ``` 围栏 */
    private static final Pattern JSON_FENCE = Pattern.compile(
            "(?s)^\\s*```(?:json)?\\s*(.*?)\\s*```\\s*$");

    public static final int MAX_ATTEMPTS = 3;

    private final ChunkContextService chunkContextService;
    private final ModificationService modificationService;
    private final ModelDiffService modelDiffService;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;

    /**
     * 自然语言修改：基于当前物模型 + 用户请求生成新模型并落库，返回前后对照视图。
     *
     * @param chunkId          目标 chunk 主键
     * @param userRequest      用户原话
     * @param thingModelSchema 物模型 JSON Schema（可空，传 null 则跳过 schema 校验）
     * @param options          LLM 调用参数（可空，使用 {@link LlmCallOptions#defaultsForEdit()}）
     * @return 前后对照视图；连续 {@link #MAX_ATTEMPTS} 次失败抛 {@link LlmOutputInvalidException}
     */
    public EditPreview edit(Long chunkId,
                            String userRequest,
                            String thingModelSchema,
                            LlmCallOptions options) {

        ChunkContext ctx = chunkContextService.loadByChunkId(chunkId);
        if (ctx.getCurrentThingModel() == null) {
            throw new IllegalStateException(
                    "chunk " + chunkId + " has no current ChunkModel; run parse first");
        }
        final JsonNode currentModel = ctx.getCurrentThingModel().getThingModel();

        LlmCallOptions opts = (options != null) ? options : LlmCallOptions.defaultsForEdit();
        StringBuilder cumulativeRequest = new StringBuilder(userRequest);
        Throwable lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            PromptMessages prompt = promptBuilder.buildEditPrompt(
                    ctx, thingModelSchema, cumulativeRequest.toString());

            String raw;
            try {
                raw = llmClient.generate(prompt, opts);
            } catch (RuntimeException e) {
                log.warn("attempt {}/{}: llm call failed: {}", attempt, MAX_ATTEMPTS, e.toString());
                lastError = e;
                if (attempt == MAX_ATTEMPTS) throw e;
                continue;
            }

            try {
                LlmEditOutput out = parseOutput(raw);
                validateNewModel(out.getNewModel(), thingModelSchema);
                verifyPatchEqualsNewModel(currentModel, out.getPatch(), out.getNewModel());

                ChunkModel saved = modificationService.upsertModel(chunkId, out.getNewModel());
                ModelDiff diff = modelDiffService.diff(currentModel, saved.getThingModel());

                log.info("edit success: chunkId={} attempts={} changes={}",
                        chunkId, attempt, diff.getChanges().size());

                return EditPreview.builder()
                        .chunkId(chunkId)
                        .beforeModel(currentModel)
                        .afterModel(saved.getThingModel())
                        .diff(diff)
                        .explanation(out.getExplanation())
                        .warnings(out.getWarnings())
                        .attempts(attempt)
                        .build();

            } catch (LlmOutputInvalidException | PatchInconsistentException e) {
                lastError = e;
                log.warn("attempt {}/{}: {} (raw len={})", attempt, MAX_ATTEMPTS,
                        e.getMessage(), raw == null ? 0 : raw.length());
                if (attempt == MAX_ATTEMPTS) {
                    throw new LlmOutputInvalidException(
                            "Edit failed after " + MAX_ATTEMPTS + " attempts: " + e.getMessage(), e);
                }
                cumulativeRequest.append("\n\n[上一次输出有问题，请修正后重新输出: ")
                        .append(e.getMessage()).append("]");
            }
        }
        throw new LlmOutputInvalidException("unreachable retry loop", lastError);
    }

    // ---------------------------------------------------------------- helpers

    LlmEditOutput parseOutput(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new LlmOutputInvalidException("empty LLM response");
        }
        String stripped = JSON_FENCE.matcher(raw).replaceAll("$1").trim();

        JsonNode root;
        try {
            root = MAPPER.readTree(stripped);
        } catch (Exception e) {
            throw new LlmOutputInvalidException("response is not valid JSON: " + e.getMessage(), e);
        }

        JsonNode patch = root.get("patch");
        JsonNode newModel = root.get("newModel");
        if (patch == null || !patch.isArray()) {
            throw new LlmOutputInvalidException("missing or non-array 'patch' field");
        }
        if (newModel == null || !newModel.isObject()) {
            throw new LlmOutputInvalidException("missing or non-object 'newModel' field");
        }

        String explanation = root.has("explanation") ? root.get("explanation").asText("") : "";
        List<String> warnings = new ArrayList<>();
        if (root.has("warnings") && root.get("warnings").isArray()) {
            for (JsonNode w : root.get("warnings")) warnings.add(w.asText());
        }

        return LlmEditOutput.builder()
                .patch(patch)
                .newModel(newModel)
                .explanation(explanation)
                .warnings(warnings)
                .rawResponse(raw)
                .build();
    }

    void validateNewModel(JsonNode newModel, String schemaText) {
        if (schemaText == null || schemaText.isBlank()) return;
        try {
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            JsonSchema schema = factory.getSchema(schemaText);
            Set<ValidationMessage> errors = schema.validate(newModel);
            if (!errors.isEmpty()) {
                String summary = errors.stream()
                        .limit(5)
                        .map(ValidationMessage::getMessage)
                        .collect(Collectors.joining("; "));
                throw new LlmOutputInvalidException(
                        "newModel violates schema (" + errors.size() + " errors): " + summary);
            }
        } catch (LlmOutputInvalidException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmOutputInvalidException("schema validation crashed: " + e.getMessage(), e);
        }
    }

    void verifyPatchEqualsNewModel(JsonNode currentModel, JsonNode patch, JsonNode newModel) {
        if (currentModel == null) currentModel = MAPPER.createObjectNode();
        JsonNode applied;
        try {
            applied = JsonPatch.apply(patch, currentModel);
        } catch (Exception e) {
            throw new PatchInconsistentException("patch failed to apply: " + e.getMessage());
        }
        if (!applied.equals(newModel)) {
            throw new PatchInconsistentException(
                    "applying patch to currentModel does not yield newModel");
        }
    }
}

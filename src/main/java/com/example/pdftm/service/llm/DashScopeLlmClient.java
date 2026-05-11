package com.example.pdftm.service.llm;
import com.example.pdftm.dto.LlmCallOptions;
import com.example.pdftm.common.exception.LlmCallException;
import com.example.pdftm.common.exception.LlmOutputInvalidException;
import com.example.pdftm.common.exception.PatchInconsistentException;

import com.example.pdftm.dto.PromptMessages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 阿里云灵积（DashScope）OpenAI 兼容模式接入。
 *
 * 不引入 dashscope-sdk-java，直接走 HTTP（端点：{@code /chat/completions}）。
 * 这样切其它 OpenAI 兼容服务（DeepSeek / Moonshot / 本地 vLLM ...）只要改 base-url + api-key + model 名。
 *
 * 本类只负责"把 system+user 两段 prompt 发出去、把 message.content 取回来"，
 * JSON 解析与一致性校验是 LlmEditService / SkeletonExtractor / ChunkParseService 的事。
 */
@Slf4j
@Component
public class DashScopeLlmClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;
    private final int timeoutSeconds;

    public DashScopeLlmClient(
            @Value("${llm.dashscope.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${llm.dashscope.api-key:}") String apiKey,
            @Value("${llm.dashscope.cheap-model:qwen-turbo}") String defaultModel,
            @Value("${llm.dashscope.timeout-seconds:120}") int timeoutSeconds) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.defaultModel = defaultModel;
        this.timeoutSeconds = timeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        if (this.apiKey.isEmpty()) {
            log.warn("DASHSCOPE_API_KEY 未配置；任何 LLM 调用都会立即失败。本地无 key 调试时这是预期。");
        }
    }

    @Override
    public String generate(PromptMessages messages, LlmCallOptions options) {
        if (apiKey.isEmpty()) {
            throw new LlmCallException("DASHSCOPE_API_KEY 未配置（application.yml: llm.dashscope.api-key）");
        }
        if (messages == null || messages.getUserPrompt() == null || messages.getUserPrompt().isBlank()) {
            throw new LlmCallException("PromptMessages.userPrompt 不能为空");
        }

        String model = (options != null && options.getModel() != null && !options.getModel().isBlank())
                ? options.getModel()
                : defaultModel;

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);

        ArrayNode msgs = body.putArray("messages");
        if (messages.getSystemPrompt() != null && !messages.getSystemPrompt().isBlank()) {
            msgs.addObject().put("role", "system").put("content", messages.getSystemPrompt());
        }
        msgs.addObject().put("role", "user").put("content", messages.getUserPrompt());

        if (options != null) {
            if (options.getTemperature() != null) body.put("temperature", options.getTemperature());
            if (options.getMaxTokens() != null) body.put("max_tokens", options.getMaxTokens());
            if (Boolean.TRUE.equals(options.getJsonMode())) {
                body.putObject("response_format").put("type", "json_object");
            }
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        long t0 = System.currentTimeMillis();
        HttpResponse<String> resp;
        try {
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new LlmCallException("DashScope 网络调用失败: " + e.getMessage(), e);
        }
        long elapsed = System.currentTimeMillis() - t0;

        if (resp.statusCode() / 100 != 2) {
            throw new LlmCallException(
                    "DashScope HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 500));
        }

        try {
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new LlmCallException(
                        "DashScope 响应缺少 choices[0].message.content: " + truncate(resp.body(), 500));
            }
            String text = content.asText();
            if (log.isDebugEnabled()) {
                log.debug("dashscope ok: model={} elapsedMs={} respChars={}",
                        model, elapsed, text == null ? 0 : text.length());
            }
            return text;
        } catch (LlmCallException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmCallException("DashScope 响应解析失败: " + e.getMessage(), e);
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "...(truncated)" : s;
    }
}

package com.example.pdftm.common.error;

import java.text.MessageFormat;

/**
 * 业务错误码枚举。
 *
 * 同时承载两件事：
 *   1. {@code code} —— i18n 资源 key（对应 messages*.properties 里同名条目）
 *   2. {@code defaultMessage} —— 兜底中文模板，用于无 MessageSource 的场景：
 *      日志、LLM 重试反馈、单元测试。
 *
 * 参数化占位符遵循 {@link MessageFormat} 语法（{0}、{1} ...）。
 */
public enum ErrorCode {

    // ── LLM 通用 ──────────────────────────────────────────────────
    LLM_RESPONSE_EMPTY              ("llm.response.empty",            "LLM 响应为空"),
    LLM_RESPONSE_NOT_JSON           ("llm.response.notJson",          "响应不是合法 JSON：{0}"),
    LLM_RESPONSE_NO_JSON_OBJECT     ("llm.response.noJsonObject",     "响应中找不到 JSON 对象"),

    // ── 骨架抽取 (SkeletonExtractor) ──────────────────────────────
    SKELETON_FIELD_INVALID          ("skeleton.field.invalid",        "字段 ''{0}'' 缺失或类型不符（期望 {1}）"),
    SKELETON_DETECTED_CHUNKS_EMPTY  ("skeleton.detectedChunks.empty", "detectedChunks 为空或全部非法"),

    // ── 物模型编辑 (LlmEditService) ──────────────────────────────
    EDIT_FIELD_INVALID              ("edit.field.invalid",            "编辑响应字段 ''{0}'' 缺失或类型不符（期望 {1}）"),
    EDIT_SCHEMA_VIOLATION           ("edit.schema.violation",         "newModel 不符 schema（{0} 项错误）：{1}"),
    EDIT_PATCH_APPLY_FAILED         ("edit.patch.applyFailed",        "patch 无法应用到 currentModel：{0}"),
    EDIT_PATCH_INCONSISTENT         ("edit.patch.inconsistent",       "patch 应用结果与 newModel 不一致"),

    // ── LLM 调用层 (DashScopeLlmClient) ───────────────────────────
    LLM_CALL_API_KEY_MISSING        ("llm.call.apiKeyMissing",        "DASHSCOPE_API_KEY 未配置"),
    LLM_CALL_NETWORK_FAILED         ("llm.call.networkFailed",        "LLM 网络调用失败：{0}"),
    LLM_CALL_HTTP_ERROR             ("llm.call.httpError",            "LLM HTTP 状态 {0}：{1}"),
    ;

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /** i18n 资源 key */
    public String code() {
        return code;
    }

    /** 用兜底中文模板格式化；MessageSource 解析失败时也回退到这里 */
    public String format(Object... args) {
        return (args == null || args.length == 0)
                ? defaultMessage
                : MessageFormat.format(defaultMessage, args);
    }
}

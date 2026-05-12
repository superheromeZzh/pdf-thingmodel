package com.example.pdftm.common.exception;

import com.example.pdftm.common.error.ErrorCode;

/**
 * LLM 输出无法解析、缺字段、或 newModel 不符 schema。
 * 这是"业务可重试"的异常——通常一次重试就能解决，多次失败再升级为人工介入。
 *
 * 携带 {@link ErrorCode} + args，便于上层按 Locale 国际化展示；
 * {@link #getMessage()} 返回兜底中文模板，给日志和 LLM 重试反馈使用。
 */
public class LlmOutputInvalidException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object[] args;

    public LlmOutputInvalidException(ErrorCode code, Object... args) {
        super(code.format(args));
        this.errorCode = code;
        this.args = (args == null) ? new Object[0] : args.clone();
    }

    public LlmOutputInvalidException(ErrorCode code, Throwable cause, Object... args) {
        super(code.format(args), cause);
        this.errorCode = code;
        this.args = (args == null) ? new Object[0] : args.clone();
    }

    /** @deprecated 使用 {@link #LlmOutputInvalidException(ErrorCode, Object...)} 以支持国际化 */
    @Deprecated
    public LlmOutputInvalidException(String message) {
        super(message);
        this.errorCode = null;
        this.args = new Object[0];
    }

    /** @deprecated 使用 {@link #LlmOutputInvalidException(ErrorCode, Throwable, Object...)} 以支持国际化 */
    @Deprecated
    public LlmOutputInvalidException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = null;
        this.args = new Object[0];
    }

    /** 可能为 null（来自旧版 String 构造器） */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object[] getArgs() {
        return args.clone();
    }
}

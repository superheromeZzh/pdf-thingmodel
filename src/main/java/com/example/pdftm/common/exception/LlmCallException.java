package com.example.pdftm.common.exception;

import com.example.pdftm.common.error.ErrorCode;

/**
 * LlmClient 实现类抛出的统一异常基类，用于厂商特定错误的归一化封装。
 * 这是"基础设施异常"——网络/鉴权/HTTP 状态错误等，与"业务输出无效"是不同语义，不可重试。
 */
public class LlmCallException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object[] args;

    public LlmCallException(ErrorCode code, Object... args) {
        super(code.format(args));
        this.errorCode = code;
        this.args = (args == null) ? new Object[0] : args.clone();
    }

    public LlmCallException(ErrorCode code, Throwable cause, Object... args) {
        super(code.format(args), cause);
        this.errorCode = code;
        this.args = (args == null) ? new Object[0] : args.clone();
    }

    /** @deprecated 使用 {@link #LlmCallException(ErrorCode, Object...)} 以支持国际化 */
    @Deprecated
    public LlmCallException(String message) {
        super(message);
        this.errorCode = null;
        this.args = new Object[0];
    }

    /** @deprecated 使用 {@link #LlmCallException(ErrorCode, Throwable, Object...)} 以支持国际化 */
    @Deprecated
    public LlmCallException(String message, Throwable cause) {
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

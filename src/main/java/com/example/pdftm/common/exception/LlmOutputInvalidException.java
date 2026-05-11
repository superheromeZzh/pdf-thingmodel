package com.example.pdftm.common.exception;

/**
 * LLM 输出无法被解析、缺字段、或 newModel 不符 schema。
 * 这是"业务可重试"的异常——通常一次重试就能解决；
 * 多次失败再升级为"需要人工介入"的死信流程。
 */
public class LlmOutputInvalidException extends RuntimeException {
    public LlmOutputInvalidException(String message) { super(message); }
    public LlmOutputInvalidException(String message, Throwable cause) { super(message, cause); }
}

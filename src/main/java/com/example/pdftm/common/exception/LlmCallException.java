package com.example.pdftm.common.exception;

/**
 * LlmClient 实现类抛出的统一异常基类，用于厂商特定错误的归一化封装。
 */
public class LlmCallException extends RuntimeException {
    public LlmCallException(String message) { super(message); }
    public LlmCallException(String message, Throwable cause) { super(message, cause); }
}

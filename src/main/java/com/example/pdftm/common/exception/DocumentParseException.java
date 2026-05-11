package com.example.pdftm.common.exception;

/**
 * 文档解析失败的统一异常（PDF/DOCX/TXT 任一格式抽取失败都用这个）。
 *
 * 由 {@link DocumentExtractor#extract(byte[])} 抛出；上层在 controller
 * 把它映射为 400。
 */
public class DocumentParseException extends RuntimeException {
    public DocumentParseException(String message) { super(message); }
    public DocumentParseException(String message, Throwable cause) { super(message, cause); }
}

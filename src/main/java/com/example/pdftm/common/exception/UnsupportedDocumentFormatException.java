package com.example.pdftm.common.exception;

/**
 * 检测不出可识别格式（既不是 PDF，也不是 DOCX，也不是 TXT），
 * 或注册表里找不到该格式对应的 {@link DocumentExtractor} 时抛出。
 *
 * controller 把它映射为 400 unsupported_format。
 */
public class UnsupportedDocumentFormatException extends RuntimeException {
    public UnsupportedDocumentFormatException(String message) { super(message); }
}

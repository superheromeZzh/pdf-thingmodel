package com.example.pdftm.controller.advice;

import com.example.pdftm.common.error.ErrorCode;
import com.example.pdftm.common.exception.LlmCallException;
import com.example.pdftm.common.exception.LlmOutputInvalidException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * LLM 相关异常的全局映射。多个 controller 都会触发 LLM 调用，统一在这里转 502 + i18n 化消息。
 *
 * Locale 通过 {@link LocaleContextHolder} 拿，Spring Boot 默认装配 {@code AcceptHeaderLocaleResolver}，
 * 客户端用 {@code Accept-Language: zh-CN} 或 {@code en} 即可切换。
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class LlmExceptionHandler {

    private final MessageSource messageSource;

    @ExceptionHandler(LlmCallException.class)
    public ResponseEntity<Map<String, Object>> handleLlmCall(LlmCallException e) {
        log.warn("llm call failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(body("llm_call_failed", e.getErrorCode(), e.getArgs(), e.getMessage()));
    }

    @ExceptionHandler(LlmOutputInvalidException.class)
    public ResponseEntity<Map<String, Object>> handleLlmOutput(LlmOutputInvalidException e) {
        log.warn("llm output invalid: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(body("llm_output_invalid", e.getErrorCode(), e.getArgs(), e.getMessage()));
    }

    private Map<String, Object> body(String kind, ErrorCode code, Object[] args, String fallback) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", kind);
        body.put("code", code == null ? null : code.code());
        body.put("message", resolve(code, args, fallback));
        return body;
    }

    /** code 为空（来自旧版 String 异常构造器）时直接返回 fallback */
    private String resolve(ErrorCode code, Object[] args, String fallback) {
        if (code == null) return fallback;
        Locale locale = LocaleContextHolder.getLocale();
        try {
            return messageSource.getMessage(code.code(), args, locale);
        } catch (NoSuchMessageException ex) {
            return code.format(args);
        }
    }
}

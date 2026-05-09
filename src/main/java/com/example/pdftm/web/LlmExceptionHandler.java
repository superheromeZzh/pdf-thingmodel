package com.example.pdftm.web;

import com.example.pdftm.llm.LlmCallException;
import com.example.pdftm.llm.LlmOutputInvalidException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * LLM 相关异常的全局映射。多个 controller 都会触发 LLM 调用，统一在这里转 502。
 */
@Slf4j
@RestControllerAdvice
public class LlmExceptionHandler {

    @ExceptionHandler(LlmCallException.class)
    public ResponseEntity<Map<String, String>> handleLlmCall(LlmCallException e) {
        log.warn("llm call failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "llm_call_failed", "message", e.getMessage()));
    }

    @ExceptionHandler(LlmOutputInvalidException.class)
    public ResponseEntity<Map<String, String>> handleLlmOutput(LlmOutputInvalidException e) {
        log.warn("llm output invalid: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "llm_output_invalid", "message", e.getMessage()));
    }
}

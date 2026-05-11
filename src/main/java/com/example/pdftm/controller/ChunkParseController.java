package com.example.pdftm.controller;

import com.example.pdftm.entity.DocumentChunk;
import com.example.pdftm.service.ChunkParseService;
import com.example.pdftm.mapper.DocumentChunkMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 物模型解析触发入口。上传接口只负责把 PDF 拆成 documents + document_chunks，
 * 这里负责"对已经存在的 chunk 跑强 LLM，写 thing_models"——可重试、按需触发。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ChunkParseController {

    private final ChunkParseService chunkParseService;
    private final DocumentChunkMapper documentChunkMapper;

    /**
     * 解析单个 chunk 的物模型。upsert 语义：已经存在的 thing_model 会被覆盖。
     *
     * @return 200 + {chunkId, status:"parsed", model}；chunk 不存在 → 404
     */
    @PostMapping("/chunks/{chunkId}/parse")
    public ResponseEntity<Map<String, Object>> parseChunk(@PathVariable Long chunkId) {
        JsonNode model = chunkParseService.parseChunk(chunkId);
        return ResponseEntity.ok(Map.of(
                "chunkId", chunkId,
                "status", "parsed",
                "model", model));
    }

    /**
     * 批量解析一篇文档下的所有 chunk。同步串行，单 chunk 失败不阻塞其它。
     *
     * @return 200 + 每个 chunk 的解析状态列表
     */
    @PostMapping("/documents/{documentId}/parse")
    public ResponseEntity<Map<String, Object>> parseDocument(@PathVariable Long documentId) {
        List<DocumentChunk> chunks = documentChunkMapper.listByDocument(documentId);
        if (chunks.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "documentId", documentId,
                    "totalChunks", 0,
                    "parsedCount", 0,
                    "results", List.of()));
        }

        List<Map<String, Object>> results = new ArrayList<>(chunks.size());
        int parsed = 0;
        for (DocumentChunk c : chunks) {
            try {
                chunkParseService.parseChunk(c.getChunkId());
                results.add(Map.of(
                        "chunkId", c.getChunkId(),
                        "chunkName", c.getChunkName(),
                        "status", "parsed"));
                parsed++;
            } catch (Exception e) {
                log.warn("chunk {} parse failed: {}", c.getChunkId(), e.toString());
                results.add(Map.of(
                        "chunkId", c.getChunkId(),
                        "chunkName", c.getChunkName() == null ? "" : c.getChunkName(),
                        "status", "parse_failed",
                        "message", e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        return ResponseEntity.ok(Map.of(
                "documentId", documentId,
                "totalChunks", chunks.size(),
                "parsedCount", parsed,
                "results", results));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(404).body(Map.of("error", "not_found", "message", e.getMessage()));
    }
}

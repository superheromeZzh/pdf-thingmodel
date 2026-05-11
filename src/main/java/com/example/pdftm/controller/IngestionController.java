package com.example.pdftm.controller;

import com.example.pdftm.common.enums.DocumentFormat;
import com.example.pdftm.service.extract.DocumentFormatDetector;
import com.example.pdftm.common.exception.DocumentParseException;
import com.example.pdftm.common.exception.UnsupportedDocumentFormatException;
import com.example.pdftm.vo.IngestionResult;
import com.example.pdftm.service.IngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 文档上传入口：multipart 上传文档（PDF/DOCX/TXT），同步跑"文档抽取 + 骨架抽取 + chunk 入库"。
 * 物模型解析不在这里跑（一次只解析骨架，速度可控），由后续 {@code POST /chunks/{chunkId}/parse} 触发。
 *
 * 同步耗时取决于文档大小 + 骨架抽取的便宜 LLM 一次调用，通常十秒级；
 * 客户端需要相应放宽超时。要异步化时切到 @Async 或队列即可。
 */
@Slf4j
@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;
    private final DocumentFormatDetector formatDetector;

    /**
     * 接收 multipart 上传的文档，按 magic bytes/后缀自动识别格式后跑 ingestion 全流程。
     *
     * @param file 上传的文档文件（multipart 字段名 file）
     * @return 200 + IngestionResult；空文件或不支持的格式返回 400
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionResult> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        byte[] bytes = file.getBytes();
        String fileName = (file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank())
                ? file.getOriginalFilename()
                : "uploaded";

        DocumentFormat format = formatDetector.detect(fileName, bytes);
        IngestionResult result = ingestionService.ingest(fileName, bytes, format);
        return ResponseEntity.ok(result);
    }

    // ---------------------------------------------------------- error mapping

    @ExceptionHandler(DocumentParseException.class)
    public ResponseEntity<Map<String, String>> handleParse(DocumentParseException e) {
        log.warn("document parse failed: {}", e.getMessage());
        return ResponseEntity.badRequest().body(
                Map.of("error", "document_parse_failed", "message", e.getMessage()));
    }

    @ExceptionHandler(UnsupportedDocumentFormatException.class)
    public ResponseEntity<Map<String, String>> handleUnsupported(UnsupportedDocumentFormatException e) {
        log.warn("unsupported document format: {}", e.getMessage());
        return ResponseEntity.badRequest().body(
                Map.of("error", "unsupported_format", "message", e.getMessage()));
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<Map<String, String>> handleNotImplemented(UnsupportedOperationException e) {
        log.warn("extractor not implemented: {}", e.getMessage());
        return ResponseEntity.status(501).body(
                Map.of("error", "not_implemented", "message", e.getMessage()));
    }
}

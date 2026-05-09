package com.example.pdftm.web;

import com.example.pdftm.ingestion.IngestionResult;
import com.example.pdftm.ingestion.IngestionService;
import com.example.pdftm.pdf.PdfTextExtractor;
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
import java.io.InputStream;
import java.util.Map;

/**
 * 文档上传入口：multipart 上传 PDF，同步跑"PDF 抽取 + 骨架抽取 + chunk 入库"。
 * 物模型解析不在这里跑（一次只解析骨架，速度可控），由后续 {@code POST /chunks/{chunkId}/parse} 触发。
 *
 * 同步耗时取决于 PDF 大小 + 骨架抽取的便宜 LLM 一次调用，通常十秒级；
 * 客户端需要相应放宽超时。要异步化时切到 @Async 或队列即可。
 */
@Slf4j
@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;

    /**
     * 接收 multipart 上传的 PDF，同步跑 ingestion 全流程。
     *
     * @param file 上传的 PDF 文件（multipart 字段名 file）
     * @return 200 + IngestionResult；空文件或非 PDF 返回 400；读取上传流失败时抛 {@link IOException}
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionResult> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (!isProbablyPdf(file)) {
            return ResponseEntity.badRequest().build();
        }
        String name = (file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank())
                ? file.getOriginalFilename()
                : "uploaded.pdf";

        IngestionResult result = ingestionService.ingest(name, file.getBytes());
        return ResponseEntity.ok(result);
    }

    /** 通过 magic bytes %PDF- 识别真 PDF；后缀名不可信。 */
    private boolean isProbablyPdf(MultipartFile f) throws IOException {
        try (InputStream is = f.getInputStream()) {
            byte[] head = is.readNBytes(5);
            return head.length >= 5
                    && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F' && head[4] == '-';
        }
    }

    // ---------------------------------------------------------- error mapping

    @ExceptionHandler(PdfTextExtractor.PdfParseException.class)
    public ResponseEntity<Map<String, String>> handlePdfParse(PdfTextExtractor.PdfParseException e) {
        log.warn("pdf parse failed: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", "pdf_parse_failed", "message", e.getMessage()));
    }
}

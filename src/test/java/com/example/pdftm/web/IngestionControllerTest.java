package com.example.pdftm.web;

import com.example.pdftm.ingestion.ChunkIngestionStatus;
import com.example.pdftm.ingestion.IngestionResult;
import com.example.pdftm.ingestion.IngestionService;
import com.example.pdftm.pdf.TestPdfBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller 切面测试：用 standalone MockMvc 跑，绕开 Spring Boot 全局上下文（不需要 DB / LLM 真实 bean）。
 *
 * <p>覆盖 multipart 上传的关键路径：合法 PDF → 200、空文件 / 非 PDF → 400。
 * 也接收 -Dtest.pdf=/path/to/foo.pdf 用真实文件跑端到端 multipart 流程。
 */
class IngestionControllerTest {

    private MockMvc mockMvc;
    private IngestionService ingestionService;

    @BeforeEach
    void setUp() {
        ingestionService = mock(IngestionService.class);
        IngestionController controller = new IngestionController(ingestionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("合法 PDF（TestPdfBuilder 构造）→ 200，IngestionService 收到字节流")
    void uploadValidPdfReturns200() throws Exception {
        byte[] pdfBytes = TestPdfBuilder.simpleThreePagePdf();
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", pdfBytes);

        when(ingestionService.ingest(anyString(), any(byte[].class)))
                .thenReturn(makeFakeResult(42L, "test.pdf"));

        mockMvc.perform(multipart("/documents").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(42L))
                .andExpect(jsonPath("$.documentName").value("test.pdf"))
                .andExpect(jsonPath("$.detectedChunkCount").value(1))
                .andExpect(jsonPath("$.insertedChunkCount").value(1));

        verify(ingestionService).ingest(
                eq("test.pdf"),
                argThat(bytes -> bytes.length == pdfBytes.length));
    }

    @Test
    @DisplayName("空文件 → 400")
    void uploadEmptyFileReturns400() throws Exception {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(multipart("/documents").file(empty))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("非 PDF（缺 %PDF- magic bytes）→ 400")
    void uploadNonPdfReturns400() throws Exception {
        MockMultipartFile fake = new MockMultipartFile(
                "file", "fake.pdf", "application/pdf",
                "this is plain text masquerading as a PDF".getBytes());

        mockMvc.perform(multipart("/documents").file(fake))
                .andExpect(status().isBadRequest());
    }

    /**
     * 真 PDF 文件 multipart 上传 smoke test：把字节交给 controller，验证 magic-byte 校验通过、
     * IngestionService 拿到了完整字节。
     *
     * <p>命令行加 -Dtest.pdf=/abs/path/to/foo.pdf 才会跑。
     */
    @Test
    @EnabledIfSystemProperty(named = "test.pdf", matches = ".+")
    @DisplayName("用 -Dtest.pdf=... 指向的真实 PDF 走一遍 multipart 上传")
    void uploadRealPdfFromSystemProperty() throws Exception {
        String pdfPath = System.getProperty("test.pdf");
        byte[] bytes = Files.readAllBytes(Path.of(pdfPath));
        String fileName = Path.of(pdfPath).getFileName().toString();

        MockMultipartFile file = new MockMultipartFile(
                "file", fileName, "application/pdf", bytes);

        when(ingestionService.ingest(anyString(), any(byte[].class)))
                .thenReturn(makeFakeResult(1L, fileName));

        MvcResult result = mockMvc.perform(multipart("/documents").file(file))
                .andExpect(status().isOk())
                .andReturn();

        verify(ingestionService).ingest(
                eq(fileName),
                argThat(b -> b.length == bytes.length));

        System.out.println("=== 真实 PDF multipart 上传成功 ===");
        System.out.println("文件: " + pdfPath + "  (" + bytes.length + " bytes)");
        System.out.println("响应: " + result.getResponse().getContentAsString());
    }

    private static IngestionResult makeFakeResult(long docId, String fileName) {
        IngestionResult r = new IngestionResult();
        r.setDocumentId(docId);
        r.setDocumentName(fileName);
        r.setPageCount(3);
        r.setDetectedChunkCount(1);
        r.setInsertedChunkCount(1);
        r.setChunks(List.of(ChunkIngestionStatus.inserted(101L, "Chapter 1")));
        return r;
    }

}

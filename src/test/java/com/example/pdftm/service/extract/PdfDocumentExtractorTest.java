package com.example.pdftm.service.extract;
import com.example.pdftm.common.enums.DocumentFormat;
import com.example.pdftm.dto.Extracted;
import com.example.pdftm.dto.Bookmark;
import com.example.pdftm.common.exception.DocumentParseException;
import com.example.pdftm.utils.PageTextUtils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单测：直接验证 PdfDocumentExtractor 的行为，不依赖 Spring 上下文。
 *
 * <p>测试 PDF 由 {@link TestPdfBuilder} 在运行时构造（避免提交二进制）；
 * 想用自己的 PDF 跑可以加 -Dtest.pdf=/path/to/file.pdf
 * 触发 {@link #parsesRealPdfFromSystemProperty}。
 */
class PdfDocumentExtractorTest {

    private final PdfDocumentExtractor extractor = new PdfDocumentExtractor();

    @Test
    @DisplayName("supportedFormat 返回 PDF")
    void supportsPdfFormat() {
        assertThat(extractor.supportedFormat()).isEqualTo(DocumentFormat.PDF);
    }

    @Test
    @DisplayName("3 页无书签 PDF：pageCount + 每页文本都正确")
    void extractsThreePagePdf() {
        byte[] pdf = TestPdfBuilder.simpleThreePagePdf();

        Extracted extract = extractor.extract(pdf);

        assertThat(extract.getPageCount()).isEqualTo(3);
        assertThat(extract.getPageTexts()).hasSize(3);
        assertThat(extract.getPageTexts().get(0)).contains("PAGE_1_MARKER");
        assertThat(extract.getPageTexts().get(1)).contains("PAGE_2_MARKER");
        assertThat(extract.getPageTexts().get(2)).contains("PAGE_3_MARKER");
        assertThat(extract.getBookmarks()).isEmpty();
    }

    @Test
    @DisplayName("带书签 PDF：每个书签的 title 和 page 都能取到")
    void extractsBookmarks() {
        byte[] pdf = TestPdfBuilder.pdfWithBookmarks();

        Extracted extract = extractor.extract(pdf);

        assertThat(extract.getPageCount()).isEqualTo(5);
        List<Bookmark> marks = extract.getBookmarks();
        assertThat(marks).hasSize(3);

        assertThat(marks.get(0).getTitle()).isEqualTo("Overview");
        assertThat(marks.get(0).getPage()).isEqualTo(1);
        assertThat(marks.get(0).getLevel()).isZero();

        assertThat(marks.get(1).getTitle()).isEqualTo("Device API");
        assertThat(marks.get(1).getPage()).isEqualTo(3);

        assertThat(marks.get(2).getTitle()).isEqualTo("Appendix");
        assertThat(marks.get(2).getPage()).isEqualTo(5);
    }

    @Test
    @DisplayName("空字节流抛 DocumentParseException")
    void emptyBytesFails() {
        assertThatThrownBy(() -> extractor.extract(new byte[0]))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("空");

        assertThatThrownBy(() -> extractor.extract(null))
                .isInstanceOf(DocumentParseException.class);
    }

    @Test
    @DisplayName("非 PDF 字节流抛 DocumentParseException")
    void garbageBytesFails() {
        byte[] garbage = "this is not a PDF, just plain text".getBytes();

        assertThatThrownBy(() -> extractor.extract(garbage))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("PDF 解析失败");
    }

    @Test
    @DisplayName("textForPageRange：正常区间拼接，越界自动钳到合法范围")
    void textForPageRangeClipsOutOfRange() {
        Extracted extract = extractor.extract(TestPdfBuilder.simpleThreePagePdf());

        // 完整区间
        String all = PageTextUtils.textForPageRange(extract, 1, 3);
        assertThat(all).contains("PAGE_1_MARKER", "PAGE_2_MARKER", "PAGE_3_MARKER");

        // 中间一页
        String middle = PageTextUtils.textForPageRange(extract, 2, 2);
        assertThat(middle).contains("PAGE_2_MARKER");
        assertThat(middle).doesNotContain("PAGE_1_MARKER", "PAGE_3_MARKER");

        // 越界向上
        String beyondUpper = PageTextUtils.textForPageRange(extract, 2, 999);
        assertThat(beyondUpper).contains("PAGE_2_MARKER", "PAGE_3_MARKER");

        // 越界向下
        String beyondLower = PageTextUtils.textForPageRange(extract, -5, 1);
        assertThat(beyondLower).contains("PAGE_1_MARKER");
        assertThat(beyondLower).doesNotContain("PAGE_2_MARKER");
    }

    @Test
    @DisplayName("textForPageRange：extract 为 null 返回空串")
    void textForPageRangeNullReturnsEmpty() {
        assertThat(PageTextUtils.textForPageRange(null, 1, 1)).isEmpty();
    }

    @Test
    @DisplayName("headAndTailText：文档够长时只拿头部 + 尾部")
    void headAndTailTextLongDoc() {
        Extracted extract = extractor.extract(TestPdfBuilder.pdfWithBookmarks());
        // 5 页，head=2, tail=2 → 拿 1,2 + 4,5，跳过 3
        String text = PageTextUtils.headAndTailText(extract, 2, 2);

        assertThat(text).contains("PAGE_1_MARKER", "PAGE_2_MARKER", "PAGE_4_MARKER", "PAGE_5_MARKER");
        assertThat(text).doesNotContain("PAGE_3_MARKER");
        assertThat(text).contains("=== 前 2 页 ===", "=== 后 2 页 ===");
    }

    @Test
    @DisplayName("headAndTailText：文档总页数 ≤ head+tail 时退化为整篇")
    void headAndTailTextShortDoc() {
        Extracted extract = extractor.extract(TestPdfBuilder.simpleThreePagePdf());
        // 3 页，head=5, tail=5 → 5+5 ≥ 3，应当退化为整篇
        String text = PageTextUtils.headAndTailText(extract, 5, 5);

        assertThat(text).contains("PAGE_1_MARKER", "PAGE_2_MARKER", "PAGE_3_MARKER");
        assertThat(text).doesNotContain("=== 前", "=== 后");
    }

    /**
     * 用真实 PDF 文件触发的 smoke test。
     *
     * <p>命令行加 -Dtest.pdf=/abs/path/to/foo.pdf 才会跑；不加就 skip。
     * 用法：mvn test -Dtest=PdfDocumentExtractorTest#parsesRealPdfFromSystemProperty -Dtest.pdf=/path/to/foo.pdf
     */
    @Test
    @EnabledIfSystemProperty(named = "test.pdf", matches = ".+")
    @DisplayName("用 -Dtest.pdf=... 指向的真实 PDF 跑一遍解析，打印页数+书签")
    void parsesRealPdfFromSystemProperty() throws Exception {
        String pdfPath = System.getProperty("test.pdf");
        byte[] bytes = Files.readAllBytes(Path.of(pdfPath));

        Extracted extract = extractor.extract(bytes);

        assertThat(extract.getPageCount()).isPositive();
        assertThat(extract.getPageTexts()).hasSize(extract.getPageCount());

        System.out.println("=== 真实 PDF 解析结果 ===");
        System.out.println("路径: " + pdfPath);
        System.out.println("总页数: " + extract.getPageCount());
        System.out.println("书签数: " + extract.getBookmarks().size());
        for (Bookmark b : extract.getBookmarks()) {
            System.out.println("  - " + "  ".repeat(b.getLevel())
                    + b.getTitle() + (b.getPage() != null ? "  (p." + b.getPage() + ')' : ""));
        }
        // 第 1 页前 200 字符样本
        String firstPage = extract.getPageTexts().get(0);
        System.out.println("第 1 页前 200 字符: "
                + (firstPage.length() <= 200 ? firstPage : firstPage.substring(0, 200) + "..."));
    }
}

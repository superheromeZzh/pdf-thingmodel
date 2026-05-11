package com.example.pdftm.service.extract;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试用：在测试运行时用 PDFBox 拼出"真实"的 PDF 字节流，避免把二进制 PDF 提交进仓库。
 *
 * <p>用法：
 * <pre>
 *   byte[] pdf = new TestPdfBuilder()
 *           .addPage("PAGE_1_MARKER\nIntroduction text.")
 *           .addBookmark("Chapter 1", 1)
 *           .addPage("PAGE_2_MARKER\nMore text.")
 *           .toBytes();
 * </pre>
 */
public final class TestPdfBuilder {

    private final List<String> pageTexts = new ArrayList<>();
    private final List<BookmarkSpec> bookmarks = new ArrayList<>();

    /**
     * 追加一页，页内文本为 {@code text}（每行一个 showText）。
     * 只能用 ASCII / Latin-1 字符——Helvetica 是标准字体不支持 CJK。
     *
     * @param text 页内文本
     * @return this
     */
    public TestPdfBuilder addPage(String text) {
        pageTexts.add(text == null ? "" : text);
        return this;
    }

    /**
     * 追加一个顶层书签，指向 {@code page1Based} 页（1-based）。
     *
     * @param title       书签标题
     * @param page1Based  指向的页码（1-based）
     * @return this
     */
    public TestPdfBuilder addBookmark(String title, int page1Based) {
        bookmarks.add(new BookmarkSpec(title, page1Based));
        return this;
    }

    /**
     * 渲染成 PDF 字节流。
     *
     * @return 完整的 PDF 字节
     */
    public byte[] toBytes() {
        if (pageTexts.isEmpty()) {
            throw new IllegalStateException("at least one page required");
        }
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(72, 720);
                    String[] lines = text.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (i > 0) cs.newLineAtOffset(0, -16);
                        cs.showText(lines[i]);
                    }
                    cs.endText();
                }
            }

            if (!bookmarks.isEmpty()) {
                PDDocumentOutline outline = new PDDocumentOutline();
                doc.getDocumentCatalog().setDocumentOutline(outline);
                for (BookmarkSpec spec : bookmarks) {
                    PDOutlineItem item = new PDOutlineItem();
                    item.setTitle(spec.title);
                    PDPageFitDestination dest = new PDPageFitDestination();
                    dest.setPage(doc.getPage(spec.page1Based - 1));
                    item.setDestination(dest);
                    outline.addLast(item);
                }
                outline.openNode();
            }

            doc.save(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("test pdf build failed", e);
        }
    }

    // ----------------------------------------------------------- 常用 fixture

    /** 3 页，无书签；每页有形如 PAGE_N_MARKER 的可断言标记。 */
    public static byte[] simpleThreePagePdf() {
        return new TestPdfBuilder()
                .addPage("PAGE_1_MARKER\nIntroduction.")
                .addPage("PAGE_2_MARKER\nDetails about the device.")
                .addPage("PAGE_3_MARKER\nAppendix.")
                .toBytes();
    }

    /** 5 页 + 3 个书签；模拟"真实手册"形态。 */
    public static byte[] pdfWithBookmarks() {
        return new TestPdfBuilder()
                .addPage("PAGE_1_MARKER\nOverview.")
                .addPage("PAGE_2_MARKER\nGetting Started.")
                .addPage("PAGE_3_MARKER\nDevice API: query temperature.")
                .addPage("PAGE_4_MARKER\nDevice API: set threshold.")
                .addPage("PAGE_5_MARKER\nAppendix and glossary.")
                .addBookmark("Overview", 1)
                .addBookmark("Device API", 3)
                .addBookmark("Appendix", 5)
                .toBytes();
    }

    private record BookmarkSpec(String title, int page1Based) {}
}

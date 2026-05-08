package com.example.pdftm.pdf;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 用 PDFBox 把 PDF 字节流变成"分页文本 + 目录（书签）+ 总页数"。
 *
 * 本类只做"PDF → 结构化文本"，不写库、不调 LLM；上层 IngestionService 拼这些片段
 * 喂给 SkeletonExtractor。
 */
@Slf4j
@Component
public class PdfTextExtractor {

    /**
     * 从 PDF 字节流抽取所有页文本 + 书签树 + 页数。
     *
     * @param pdfBytes 整个 PDF 文件的字节
     * @return 解析结果；如果 PDF 解析失败抛 {@link PdfParseException}
     */
    public Extracted extract(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new PdfParseException("PDF 字节流为空");
        }
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int pageCount = doc.getNumberOfPages();
            log.info("pdf parse: pages={} bytes={}", pageCount, pdfBytes.length);

            // 逐页抽文本；setStartPage/setEndPage 是 1-based
            PDFTextStripper stripper = new PDFTextStripper();
            List<String> pageTexts = new ArrayList<>(pageCount);
            for (int i = 1; i <= pageCount; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                pageTexts.add(stripper.getText(doc));
            }

            List<Bookmark> bookmarks = extractBookmarks(doc);
            return new Extracted(pageCount, pageTexts, bookmarks);
        } catch (IOException e) {
            throw new PdfParseException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 取闭区间 [pageStart, pageEnd] 内（1-based）的拼接文本，越界自动钳到合法范围。
     *
     * @param extract   PDF 解析结果
     * @param pageStart 起始页（1-based）
     * @param pageEnd   结束页（1-based，闭区间）
     * @return 拼接后的文本；extract 为 null 时返回空串
     */
    public static String textForPageRange(Extracted extract, int pageStart, int pageEnd) {
        if (extract == null || extract.getPageTexts() == null) return "";
        int total = extract.getPageTexts().size();
        int start = Math.max(1, pageStart);
        int end = Math.min(total, Math.max(start, pageEnd));
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            sb.append(extract.getPageTexts().get(i - 1));
            if (!sb.toString().endsWith("\n")) sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 取首 headPages 页 + 末 tailPages 页的拼接文本，文档不够长时退化为整篇返回。
     *
     * @param extract   PDF 解析结果
     * @param headPages 取头部多少页
     * @param tailPages 取尾部多少页
     * @return 拼接后的文本
     */
    public static String headAndTailText(Extracted extract, int headPages, int tailPages) {
        int total = extract.getPageCount();
        if (total <= headPages + tailPages) {
            return textForPageRange(extract, 1, total);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== 前 ").append(headPages).append(" 页 ===\n");
        sb.append(textForPageRange(extract, 1, headPages));
        sb.append("\n=== 后 ").append(tailPages).append(" 页 ===\n");
        sb.append(textForPageRange(extract, total - tailPages + 1, total));
        return sb.toString();
    }

    private List<Bookmark> extractBookmarks(PDDocument doc) {
        PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
        if (outline == null) return Collections.emptyList();
        List<Bookmark> result = new ArrayList<>();
        walk(outline.getFirstChild(), 0, doc, result);
        return result;
    }

    private void walk(PDOutlineItem item, int level, PDDocument doc, List<Bookmark> out) {
        while (item != null) {
            Integer page = resolvePage(item, doc);
            String title = item.getTitle();
            if (title != null && !title.isBlank()) {
                out.add(new Bookmark(title.trim(), level, page));
            }
            if (item.getFirstChild() != null) {
                walk(item.getFirstChild(), level + 1, doc, out);
            }
            item = item.getNextSibling();
        }
    }

    /** 解析书签指向的页码为 1-based int；解析不出来时返回 null。 */
    private Integer resolvePage(PDOutlineItem item, PDDocument doc) {
        try {
            PDPageDestination dest = (item.getDestination() instanceof PDPageDestination)
                    ? (PDPageDestination) item.getDestination()
                    : null;
            if (dest == null && item.getAction() != null) {
                // GoTo action 也可能内嵌目标
                PDPage p = item.findDestinationPage(doc);
                if (p != null) {
                    return doc.getPages().indexOf(p) + 1;
                }
                return null;
            }
            if (dest == null) return null;
            PDPage page = dest.getPage();
            if (page != null) {
                return doc.getPages().indexOf(page) + 1;
            }
            int idx = dest.getPageNumber();
            return idx >= 0 ? idx + 1 : null;
        } catch (IOException e) {
            log.debug("resolve bookmark page failed for '{}': {}", item.getTitle(), e.toString());
            return null;
        }
    }

    // ----------------------------------------------------------- value types

    @Data
    public static class Extracted {
        /** PDF 总页数（1-based 计数下的最大值） */
        private final int pageCount;
        /** 长度等于 pageCount，第 i 项是第 i+1 页的纯文本（PDFBox 抽取） */
        private final List<String> pageTexts;
        /** PDF 书签树（深度优先扁平化）；没有目录时为空列表 */
        private final List<Bookmark> bookmarks;
    }

    @Data
    public static class Bookmark {
        /** 书签标题 */
        private final String title;
        /** 嵌套深度，0 = 顶层 */
        private final int level;
        /** 指向的 1-based 页码；解析失败返回 null */
        private final Integer page;
    }

    /** PDF 解析失败统一异常 */
    public static class PdfParseException extends RuntimeException {
        public PdfParseException(String message) { super(message); }
        public PdfParseException(String message, Throwable cause) { super(message, cause); }
    }
}

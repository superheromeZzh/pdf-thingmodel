package com.example.pdftm.service.extract;
import com.example.pdftm.common.enums.DocumentFormat;
import com.example.pdftm.dto.Extracted;
import com.example.pdftm.dto.Bookmark;
import com.example.pdftm.common.exception.DocumentParseException;
import com.example.pdftm.common.exception.UnsupportedDocumentFormatException;

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
import java.util.List;

/**
 * 用 PDFBox 把 PDF 字节流变成"分页文本 + 目录（书签）+ 总页数"。
 *
 * 本类只做"PDF → 结构化文本"，不写库、不调 LLM；上层 IngestionService 拼这些片段
 * 喂给 SkeletonExtractor。
 *
 * PDF 的"逻辑页"和"物理页"1:1，所以这里直接走 PDFBox 的物理页号。
 */
@Slf4j
@Component
public class PdfDocumentExtractor implements DocumentExtractor {

    @Override
    public DocumentFormat supportedFormat() {
        return DocumentFormat.PDF;
    }

    /**
     * 从 PDF 字节流抽取所有页文本 + 书签树 + 页数。
     *
     * @param pdfBytes 整个 PDF 文件的字节
     * @return 解析结果；如果 PDF 解析失败抛 {@link DocumentParseException}
     */
    @Override
    public Extracted extract(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new DocumentParseException("PDF 字节流为空");
        }
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int pageCount = doc.getNumberOfPages();
            log.info("pdf parse: pages={} bytes={}", pageCount, pdfBytes.length);

            return new Extracted(
                    pageCount,
                    extractPageTexts(doc, pageCount),
                    extractBookmarks(doc));
        } catch (IOException e) {
            throw new DocumentParseException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------- private helpers

    /** 逐页抽文本；PDFTextStripper 是有状态的，setStartPage/setEndPage 后 getText 取该页。 */
    private static List<String> extractPageTexts(PDDocument doc, int pageCount) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        List<String> pageTexts = new ArrayList<>(pageCount);
        for (int page = 1; page <= pageCount; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            pageTexts.add(stripper.getText(doc));
        }
        return pageTexts;
    }

    /** 抽书签并扁平化。无目录时返回空列表（不为 null）。 */
    private static List<Bookmark> extractBookmarks(PDDocument doc) {
        PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
        if (outline == null) return List.of();

        List<Bookmark> collected = new ArrayList<>();
        flattenOutline(outline.getFirstChild(), 0, doc, collected);
        return collected;
    }

    /** 深度优先遍历书签树，把每一项附加到 collected。 */
    private static void flattenOutline(PDOutlineItem first, int level,
                                       PDDocument doc, List<Bookmark> collected) {
        for (PDOutlineItem item = first; item != null; item = item.getNextSibling()) {
            String title = item.getTitle();
            if (title != null && !title.isBlank()) {
                collected.add(new Bookmark(title.trim(), level, resolveBookmarkPage(item, doc)));
            }
            if (item.getFirstChild() != null) {
                flattenOutline(item.getFirstChild(), level + 1, doc, collected);
            }
        }
    }

    /**
     * 解析书签指向的 1-based 页码；解析不出来时返回 null。
     *
     * 两条路径：
     *   1. item.getDestination() 直接是 PDPageDestination —— 大多数 PDF 走这条
     *   2. 走 GoTo action 嵌套 destination —— 部分工具生成的 PDF 用这种
     */
    private static Integer resolveBookmarkPage(PDOutlineItem item, PDDocument doc) {
        try {
            if (item.getDestination() instanceof PDPageDestination dest) {
                PDPage page = dest.getPage();
                if (page != null) {
                    return pageIndex1Based(doc, page);
                }
                int explicitIndex = dest.getPageNumber();
                return explicitIndex >= 0 ? explicitIndex + 1 : null;
            }
            if (item.getAction() != null) {
                PDPage page = item.findDestinationPage(doc);
                if (page != null) {
                    return pageIndex1Based(doc, page);
                }
            }
            return null;
        } catch (IOException e) {
            log.debug("resolve bookmark page failed for '{}': {}", item.getTitle(), e.toString());
            return null;
        }
    }

    private static int pageIndex1Based(PDDocument doc, PDPage page) {
        return doc.getPages().indexOf(page) + 1;
    }
}

package com.example.pdftm.utils;

import com.example.pdftm.dto.Extracted;

import java.util.List;

/**
 * 围绕 {@link Extracted} 做"按页区间拼接文本"的纯静态工具。
 *
 * 与具体源格式（PDF / DOCX / TXT）无关，只依赖 {@link Extracted} 暴露的
 * "逻辑页文本数组 + 总页数"形状，所以 chunk 装载、prompt 拼装、骨架抽取的
 * 头尾片段裁切等场景都可以复用。
 */
public final class PageTextUtils {

    private PageTextUtils() {}

    /**
     * 取闭区间 {@code [pageStart, pageEnd]}（1-based）内的拼接文本，
     * 越界自动钳到合法范围。
     *
     * @param extract   抽取结果
     * @param pageStart 起始页（1-based）
     * @param pageEnd   结束页（1-based，闭区间）
     * @return 拼接后的文本；extract 为 null 时返回空串
     */
    public static String textForPageRange(Extracted extract, int pageStart, int pageEnd) {
        if (extract == null || extract.getPageTexts() == null) return "";

        List<String> pageTexts = extract.getPageTexts();
        int safeStart = Math.max(1, pageStart);
        int safeEnd = Math.min(pageTexts.size(), Math.max(safeStart, pageEnd));

        StringBuilder sb = new StringBuilder();
        for (int page = safeStart; page <= safeEnd; page++) {
            String pageText = pageTexts.get(page - 1);
            sb.append(pageText);
            // 保证页与页之间至少一个换行——抽取器给出的页文本可能不含 trailing \n
            if (!pageText.endsWith("\n")) sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 取首 {@code headPages} 页 + 末 {@code tailPages} 页的拼接文本，
     * 文档不够长时退化为整篇返回。
     */
    public static String headAndTailText(Extracted extract, int headPages, int tailPages) {
        int totalPages = extract.getPageCount();
        if (totalPages <= headPages + tailPages) {
            return textForPageRange(extract, 1, totalPages);
        }
        return "=== 前 " + headPages + " 页 ===\n"
                + textForPageRange(extract, 1, headPages)
                + "\n=== 后 " + tailPages + " 页 ===\n"
                + textForPageRange(extract, totalPages - tailPages + 1, totalPages);
    }
}

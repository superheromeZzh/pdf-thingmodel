package com.example.pdftm.dto;

import lombok.Data;

/**
 * 文档大纲条目（书签）。
 *
 * 含义在不同来源里略有差异，但语义统一：
 * <ul>
 *   <li>PDF：来源于 PDF 书签树（PDFBox 抽取得到）</li>
 *   <li>DOCX：来源于 Heading 1/2/3... 段落样式</li>
 *   <li>TXT：一般为空</li>
 * </ul>
 */
@Data
public class Bookmark {
    /** 标题文本 */
    private final String title;
    /** 嵌套深度，0 = 顶层 */
    private final int level;
    /** 指向的 1-based 逻辑页码；解析不出来时为 null */
    private final Integer page;
}

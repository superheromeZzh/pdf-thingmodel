package com.example.pdftm.dto;

import lombok.Data;

import java.util.List;

/**
 * 文档抽取结果——一切下游链路（骨架抽取、chunk 入库、prompt 拼装）只认这个形状，
 * 与具体源格式（PDF / DOCX / TXT）无关。
 *
 * <h2>"逻辑页"语义合同</h2>
 * 不同源格式对"页"的定义不一样，但 {@link Extracted} 把它们抹平成"逻辑页"：
 * <ul>
 *   <li>{@link #pageCount} ≥ 1 且等于 {@code pageTexts.size()}</li>
 *   <li>{@code pageTexts.get(i)} 是第 {@code i+1} 个逻辑单元的纯文本</li>
 *   <li>PDF：物理页 1:1 映射逻辑页</li>
 *   <li>DOCX：建议按 Heading 1 切；无 Heading 时按每 N 段落（如 30 段）一个逻辑页</li>
 *   <li>TXT：建议按固定行数窗口（如 200 行/页）</li>
 *   <li>{@link #bookmarks} 可空；DOCX 由 Heading 层级推出，TXT 一般为空</li>
 * </ul>
 *
 * 按页区间裁切文本由 {@code com.example.pdftm.utils.PageTextUtils} 负责，本类只承载数据。
 */
@Data
public class Extracted {

    /** 总逻辑页数，等于 {@code pageTexts.size()} */
    private final int pageCount;

    /** 长度等于 {@link #pageCount}，第 {@code i} 项是第 {@code i+1} 个逻辑页的纯文本 */
    private final List<String> pageTexts;

    /** 文档大纲；没有则为空列表（不为 null） */
    private final List<Bookmark> bookmarks;
}

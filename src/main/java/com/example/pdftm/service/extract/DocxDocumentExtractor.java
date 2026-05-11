package com.example.pdftm.service.extract;
import com.example.pdftm.common.enums.DocumentFormat;
import com.example.pdftm.dto.Extracted;
import com.example.pdftm.dto.Bookmark;
import com.example.pdftm.common.exception.DocumentParseException;
import com.example.pdftm.common.exception.UnsupportedDocumentFormatException;

import org.springframework.stereotype.Component;

/**
 * DOCX 文档抽取器——占位实现。
 *
 * <p>真正实现要做的事（后续 PR）：</p>
 * <ul>
 *   <li>引入 {@code org.apache.poi:poi-ooxml} 依赖</li>
 *   <li>用 XWPFDocument 遍历段落 + 表格，按 Heading 1 切"逻辑页"
 *       （无 Heading 时按每 N 段落一个逻辑页的窗口策略）</li>
 *   <li>由 Heading 1/2/3 推出 {@link Bookmark} 层级</li>
 *   <li>遵守 {@link Extracted} 的"逻辑页"语义合同</li>
 * </ul>
 */
@Component
public class DocxDocumentExtractor implements DocumentExtractor {

    @Override
    public DocumentFormat supportedFormat() {
        return DocumentFormat.DOCX;
    }

    @Override
    public Extracted extract(byte[] bytes) {
        throw new UnsupportedOperationException(
                "DOCX 抽取尚未实现；后续 PR 会接入 Apache POI (XWPF)");
    }
}

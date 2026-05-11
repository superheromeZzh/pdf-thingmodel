package com.example.pdftm.service.extract;
import com.example.pdftm.common.enums.DocumentFormat;
import com.example.pdftm.dto.Extracted;
import com.example.pdftm.dto.Bookmark;
import com.example.pdftm.common.exception.DocumentParseException;
import com.example.pdftm.common.exception.UnsupportedDocumentFormatException;

import org.springframework.stereotype.Component;

/**
 * TXT 文档抽取器——占位实现。
 *
 * <p>真正实现要做的事（后续 PR）：</p>
 * <ul>
 *   <li>UTF-8 解码字节流（带 BOM 处理）</li>
 *   <li>按固定行数窗口（如 200 行/页）切"逻辑页"</li>
 *   <li>{@link Bookmark} 一般留空（或可选用正则识别 Markdown 风格 heading）</li>
 *   <li>遵守 {@link Extracted} 的"逻辑页"语义合同</li>
 * </ul>
 */
@Component
public class TxtDocumentExtractor implements DocumentExtractor {

    @Override
    public DocumentFormat supportedFormat() {
        return DocumentFormat.TXT;
    }

    @Override
    public Extracted extract(byte[] bytes) {
        throw new UnsupportedOperationException(
                "TXT 抽取尚未实现；后续 PR 按行窗口切分");
    }
}

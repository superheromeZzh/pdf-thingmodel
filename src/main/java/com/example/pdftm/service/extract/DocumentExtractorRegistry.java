package com.example.pdftm.service.extract;
import com.example.pdftm.common.enums.DocumentFormat;
import com.example.pdftm.dto.Extracted;
import com.example.pdftm.dto.Bookmark;
import com.example.pdftm.common.exception.DocumentParseException;
import com.example.pdftm.common.exception.UnsupportedDocumentFormatException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按 {@link DocumentFormat} 分发到对应的 {@link DocumentExtractor} 实现。
 *
 * Spring 启动时把容器里所有 {@link DocumentExtractor} bean 收集到 {@code List<DocumentExtractor>}，
 * 构造期建查找表；同一个 format 出现两次 → 启动失败（fail fast）。
 */
@Slf4j
@Component
public class DocumentExtractorRegistry {

    private final Map<DocumentFormat, DocumentExtractor> byFormat;

    public DocumentExtractorRegistry(List<DocumentExtractor> extractors) {
        Map<DocumentFormat, DocumentExtractor> map = new EnumMap<>(DocumentFormat.class);
        for (DocumentExtractor ex : extractors) {
            DocumentFormat fmt = ex.supportedFormat();
            DocumentExtractor prev = map.put(fmt, ex);
            if (prev != null) {
                throw new IllegalStateException(
                        "重复的 DocumentExtractor 注册到同一格式: " + fmt
                                + "（" + prev.getClass().getSimpleName()
                                + " vs " + ex.getClass().getSimpleName() + ")");
            }
        }
        this.byFormat = Map.copyOf(map);
        log.info("DocumentExtractorRegistry: registered {} extractors: {}",
                byFormat.size(), byFormat.keySet());
    }

    /**
     * 取指定格式的抽取器；找不到时抛 {@link UnsupportedDocumentFormatException}。
     */
    public DocumentExtractor get(DocumentFormat format) {
        DocumentExtractor ex = byFormat.get(format);
        if (ex == null) {
            throw new UnsupportedDocumentFormatException(
                    "未注册该格式的 DocumentExtractor: " + format);
        }
        return ex;
    }
}

package com.example.pdftm.service.extract;
import com.example.pdftm.common.enums.DocumentFormat;
import com.example.pdftm.dto.Extracted;
import com.example.pdftm.dto.Bookmark;
import com.example.pdftm.common.exception.DocumentParseException;
import com.example.pdftm.common.exception.UnsupportedDocumentFormatException;

/**
 * 文档抽取器：把某种源格式的字节流解析成统一的 {@link Extracted}。
 *
 * <p>每个实现类只负责一种 {@link DocumentFormat}（由 {@link #supportedFormat()} 自报家门），
 * 由 Spring 自动收集到 {@link DocumentExtractorRegistry} 里供按格式分发。</p>
 *
 * <h2>实现约定</h2>
 * <ul>
 *   <li>{@link #extract(byte[])} 必须遵守 {@link Extracted} 的"逻辑页"语义合同</li>
 *   <li>解析失败统一抛 {@link DocumentParseException}（不要抛 IOException 或裸 RuntimeException）</li>
 *   <li>不要在抽取阶段做"骨架抽取 / chunk 切分"——那是 {@code SkeletonExtractor} 的工作；
 *       本类只产出"分页文本数组 + 总页数 + 可选大纲"这一层原始结构</li>
 *   <li>必须是无状态的、线程安全的（典型 @Component 单例）</li>
 * </ul>
 */
public interface DocumentExtractor {

    /** 本实现支持哪种格式（用于 {@link DocumentExtractorRegistry} 建查找表） */
    DocumentFormat supportedFormat();

    /**
     * 把字节流解析为统一的 {@link Extracted}。
     *
     * @param bytes 整个文件的字节流（已读到内存）
     * @return 解析结果；解析失败抛 {@link DocumentParseException}
     */
    Extracted extract(byte[] bytes);
}

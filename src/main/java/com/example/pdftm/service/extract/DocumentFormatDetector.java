package com.example.pdftm.service.extract;
import com.example.pdftm.common.enums.DocumentFormat;
import com.example.pdftm.dto.Extracted;
import com.example.pdftm.dto.Bookmark;
import com.example.pdftm.common.exception.DocumentParseException;
import com.example.pdftm.common.exception.UnsupportedDocumentFormatException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 通过 magic bytes + 文件名后缀判定上传文件的 {@link DocumentFormat}。
 *
 * 优先级：
 * <ol>
 *   <li>PDF: 头 5 字节 {@code %PDF-}（后缀名不参考——magic bytes 即可定真伪）</li>
 *   <li>DOCX: 头 4 字节 ZIP 签名 {@code PK\x03\x04} + 后缀 {@code .docx}
 *       （避免把普通 jar/zip 误判为 docx）</li>
 *   <li>TXT: 后缀 {@code .txt} / {@code .md}（先简单走后缀；后续可加二进制启发式）</li>
 *   <li>其它：抛 {@link UnsupportedDocumentFormatException}</li>
 * </ol>
 */
@Slf4j
@Component
public class DocumentFormatDetector {

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};

    /**
     * 判定字节流的格式。
     *
     * @param fileName 上传时的原始文件名（可空；空时仅靠 magic bytes 判定）
     * @param bytes    整个文件的字节流（不为 null/空）
     * @return 判定到的 {@link DocumentFormat}
     * @throws UnsupportedDocumentFormatException 任何格式都不匹配
     */
    public DocumentFormat detect(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new UnsupportedDocumentFormatException("文件为空");
        }
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);

        if (startsWith(bytes, PDF_MAGIC)) {
            return DocumentFormat.PDF;
        }
        if (startsWith(bytes, ZIP_MAGIC) && lowerName.endsWith(".docx")) {
            return DocumentFormat.DOCX;
        }
        if (lowerName.endsWith(".txt") || lowerName.endsWith(".md")) {
            return DocumentFormat.TXT;
        }
        throw new UnsupportedDocumentFormatException(
                "无法识别文档格式（fileName='" + fileName + "', firstBytes="
                        + describeFirstBytes(bytes) + "）");
    }

    private static boolean startsWith(byte[] bytes, byte[] magic) {
        if (bytes.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) return false;
        }
        return true;
    }

    /** 把头 6 字节渲染成可读 hex，调试错误日志用。 */
    private static String describeFirstBytes(byte[] bytes) {
        int n = Math.min(6, bytes.length);
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }
}

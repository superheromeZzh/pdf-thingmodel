package com.example.pdftm.ingestion;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * IngestionService 的总返回。controller 直接 JSON 序列化给前端。
 *
 * 能走到这一步说明文档主体已经成功 INSERT；chunks 列表里逐项标注每个 chunk
 * 是入库 + 物模型解析都成功，还是哪一步失败。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IngestionResult {
    private Long documentId;
    private String documentName;
    private Integer pageCount;

    /** 检测到的 chunk 数量 */
    private Integer detectedChunkCount;

    /** 物模型解析成功的 chunk 数量 */
    private Integer parsedChunkCount;

    /** 每个 chunk 的最终状态 */
    private List<ChunkIngestionStatus> chunks;
}

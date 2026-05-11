package com.example.pdftm.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * IngestionService 的总返回。controller 直接 JSON 序列化给前端。
 *
 * 能走到这一步说明文档主体已经成功 INSERT；chunks 列表里逐项标注每个 chunk
 * 是入库成功还是失败。物模型解析不在本接口做，需要调用方后续按 chunkId 显式触发。
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

    /** 入库成功的 chunk 数量 */
    private Integer insertedChunkCount;

    /** 每个 chunk 的入库状态 */
    private List<ChunkIngestionStatus> chunks;
}

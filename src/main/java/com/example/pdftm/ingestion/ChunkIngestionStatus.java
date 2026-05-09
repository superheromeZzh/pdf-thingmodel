package com.example.pdftm.ingestion;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个 chunk 在上传 ingestion 流程里的最终入库状态。一份文档可能有几十个 chunk，
 * 部分入库失败不影响其他成功——前端按这个列表渲染"X 个入库成功 / Y 个失败"。
 *
 * 物模型解析不在上传里做，由后续 {@code POST /chunks/{chunkId}/parse} 显式触发，
 * 因此本结构不再表达解析成败。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkIngestionStatus {

    /** chunk 主键；INSERT chunks 失败时为 null */
    private Long chunkId;

    private String chunkName;

    /** inserted / chunk_insert_failed */
    private String status;

    /** 失败原因；status=inserted 时为 null */
    private String message;

    public static ChunkIngestionStatus inserted(Long chunkId, String chunkName) {
        return new ChunkIngestionStatus(chunkId, chunkName, "inserted", null);
    }

    public static ChunkIngestionStatus chunkInsertFailed(String chunkName, String message) {
        return new ChunkIngestionStatus(null, chunkName, "chunk_insert_failed", message);
    }
}

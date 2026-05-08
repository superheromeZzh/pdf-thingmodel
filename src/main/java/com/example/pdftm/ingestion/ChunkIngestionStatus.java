package com.example.pdftm.ingestion;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个 chunk 在 ingestion 流程里的最终状态。一份文档可能有几十个 chunk，
 * 部分失败不影响其他成功——前端按这个列表渲染"X 个解析成功 / Y 个失败"。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkIngestionStatus {

    /** chunk 主键；INSERT chunks 失败时为 null */
    private Long chunkId;

    private String chunkName;

    /** parsed / parse_failed / chunk_insert_failed */
    private String status;

    /** 失败原因；status=parsed 时为 null */
    private String message;

    public static ChunkIngestionStatus parsed(Long chunkId, String chunkName) {
        return new ChunkIngestionStatus(chunkId, chunkName, "parsed", null);
    }

    public static ChunkIngestionStatus parseFailed(Long chunkId, String chunkName, String message) {
        return new ChunkIngestionStatus(chunkId, chunkName, "parse_failed", message);
    }

    public static ChunkIngestionStatus chunkInsertFailed(String chunkName, String message) {
        return new ChunkIngestionStatus(null, chunkName, "chunk_insert_failed", message);
    }
}

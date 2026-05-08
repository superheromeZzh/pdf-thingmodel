package com.example.pdftm.domain;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 一个 chunk 当前生效的物模型。chunk_id 即主键（1:1 chunk）。
 * 不保留版本/审计 —— 写入是 upsert，最后写者胜。
 */
@Data
public class ThingModel {

    private Long chunkId;

    private JsonNode model;

    private OffsetDateTime createdAt;

    /** 触发器 trg_set_updated_at 在 UPDATE 时自动写入；upsert 走 ON CONFLICT DO UPDATE 时也会触发 */
    private OffsetDateTime updatedAt;
}

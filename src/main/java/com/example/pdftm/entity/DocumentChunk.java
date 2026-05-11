package com.example.pdftm.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 文档的一段（一个 API / 一个章节）。物模型挂在 thing_models 上，每 chunk 一行。
 */
@Data
public class DocumentChunk {

    private Long chunkId;

    private Long documentId;

    private String chunkName;

    private Integer pageStart;

    private Integer pageEnd;

    private String rawText;

    private String summary;

    private OffsetDateTime createdAt;
}

package com.example.pdftm.vo;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 文档概览。前端首页那张卡片的数据来源。
 */
@Data
public class DocumentOverview {
    private Long documentId;
    private String documentName;
    private Integer pageCount;

    /** 文档级摘要（150-300 字）；前端文档卡片可直接显示 */
    private String summary;

    /** 文档总 chunk 数；LEFT JOIN 聚合得出 */
    private Integer totalChunks;

    private OffsetDateTime createdAt;
}

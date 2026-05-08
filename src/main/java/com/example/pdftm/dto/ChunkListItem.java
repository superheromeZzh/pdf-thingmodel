package com.example.pdftm.dto;

import lombok.Data;

/**
 * chunk 列表行。**故意不含物模型 JSON**——列表页只需要轻量信息，
 * 拿到 chunkId 后再点详情接口取完整物模型。
 */
@Data
public class ChunkListItem {
    private Long chunkId;
    private String chunkName;
    private Integer pageStart;
    private Integer pageEnd;
    private String summary;

    /** 该 chunk 是否已经生成物模型（thing_models 里有没有对应行） */
    private Boolean hasModel;
}

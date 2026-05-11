package com.example.pdftm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 阶段 1 骨架抽取的"副产物"：检测到的 chunk 描述。
 * 不入库，仅用于驱动阶段 2 的 document_chunks INSERT。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DetectedChunk {
    /** 该 chunk 的名称（API 名 / 章节标题；写入 document_chunks.chunk_name） */
    private String chunkName;

    /** 1-based 起始页 */
    private Integer pageStart;

    /** 1-based 结束页（闭区间） */
    private Integer pageEnd;

    /** 一句话摘要（写入 document_chunks.summary，可空） */
    private String summary;
}

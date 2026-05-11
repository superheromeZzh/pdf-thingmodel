package com.example.pdftm.vo;

import com.example.pdftm.entity.ChunkModel;
import com.example.pdftm.entity.DocumentChunk;
import lombok.Builder;
import lombok.Data;

/**
 * 用户点开一个 chunk 时一次返回所有展示所需。
 *
 * 前端典型布局：
 *   左：chunk.rawText（原文）
 *   右：currentThingModel.thingModel（解析后的物模型 JSON 或表单）
 */
@Data
@Builder
public class ChunkInspectView {

    /** chunk 元信息（页码、名称、原文等） */
    private DocumentChunk chunk;

    /** 当前生效物模型；未生成时为 null */
    private ChunkModel currentThingModel;
}

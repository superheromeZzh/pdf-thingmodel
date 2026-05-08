package com.example.pdftm.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 修改完成后的"前后对照"返回。前端拿这一份就能渲染：
 *
 *   ┌──────────────┬──────────────┐
 *   │   Before     │   After      │
 *   │  {model JSON}│  {model JSON}│
 *   └──────────────┴──────────────┘
 *      diff.changes:
 *       ✎ fields[0].default: 60 → 30
 *      explanation: "依据 p.12 ..."
 *      warnings:    []
 *
 * 到达这一步时新模型已经写入 thing_models（覆盖式 upsert，无版本历史）。
 */
@Data
@Builder
public class EditPreview {
    private Long chunkId;

    private JsonNode beforeModel;
    private JsonNode afterModel;

    private ModelDiff diff;

    /** LLM 给的解释；可空 */
    private String explanation;

    /** LLM 自报的风险/警告；可空 */
    private List<String> warnings;

    /** LLM 流程的实际尝试次数 */
    private Integer attempts;
}

package com.example.pdftm.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 两份物模型之间的字段级差异。
 *
 * - changes：人类可读的条目，前端可以直接渲染列表/卡片
 * - summary：一句话总结（"改动 1 项：default 值"），用于折叠展示
 * - rawPatch：RFC 6902 patch ops 原始数组，前端做更精细的 diff 渲染（颜色高亮、嵌套展开）时用
 */
@Data
@Builder
public class ModelDiff {
    private List<ChangeItem> changes;
    private String summary;
    private JsonNode rawPatch;
}

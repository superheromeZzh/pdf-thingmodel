package com.example.pdftm.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * 一条人类可读的差异条目。前端拿到一组 ChangeItem 就可以直接渲染：
 *
 *   ✚ 新增字段 fields[3]（type=int, name=timeout）
 *   ✎ 修改 fields[0].default：60 → 30
 *   ✕ 删除字段 fields[2]（旧值: {...}）
 */
@Data
@Builder
public class ChangeItem {

    /** added / removed / modified */
    private String type;

    /** JSON Pointer 路径，例如 "/fields/0/default" 或 OGNL 风格 "fields[0].default" */
    private String path;

    /** 修改前的值（type=added 时为 null） */
    private JsonNode before;

    /** 修改后的值（type=removed 时为 null） */
    private JsonNode after;

    /** 一句话中文描述，前端兜底展示 */
    private String description;
}

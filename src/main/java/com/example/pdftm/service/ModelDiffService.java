package com.example.pdftm.service;

import com.example.pdftm.dto.ChangeItem;
import com.example.pdftm.dto.ModelDiff;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.zjsonpatch.DiffFlags;
import com.flipkart.zjsonpatch.JsonDiff;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 把两份物模型 JSON 之间的差异转换成对前端友好的 ModelDiff。
 *
 * 输入：before / after（任一可空，分别表示新建 / 删除整体）
 * 输出：
 *   - changes: 一组人类可读的 ChangeItem
 *   - summary: 一句话总结
 *   - rawPatch: RFC 6902 patch 数组（前端要做精细高亮时用）
 *
 * 实现思路：用 zjsonpatch.JsonDiff.asJson(before, after) 算 RFC 6902 patch，
 * 再把 ops 翻译成 ChangeItem。zjsonpatch 默认会输出 add/remove/replace/copy/move，
 * 这里用 OMIT_MOVE_OPERATION + OMIT_COPY_OPERATION 让差异只剩三种基础类型，
 * 前端展示更直观（move/copy 在物模型场景下很少见，强行展开成 add+remove 反而清晰）。
 */
@Service
public class ModelDiffService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final EnumSet<DiffFlags> DIFF_FLAGS = EnumSet.of(
            DiffFlags.OMIT_MOVE_OPERATION,
            DiffFlags.OMIT_COPY_OPERATION
    );

    /**
     * 计算 before → after 的差异。任意一边为 null 时按空对象处理。
     */
    public ModelDiff diff(JsonNode before, JsonNode after) {
        JsonNode b = (before == null) ? MAPPER.createObjectNode() : before;
        JsonNode a = (after == null)  ? MAPPER.createObjectNode() : after;

        JsonNode patch = JsonDiff.asJson(b, a, DIFF_FLAGS);
        List<ChangeItem> changes = new ArrayList<>();

        for (JsonNode op : patch) {
            String operation = op.path("op").asText();
            String path = op.path("path").asText();
            JsonNode value = op.get("value");
            JsonNode fromValue = op.get("fromValue");   // zjsonpatch 在 replace/remove 时会带上原值

            switch (operation) {
                case "add":
                    changes.add(ChangeItem.builder()
                            .type("added")
                            .path(path)
                            .before(null)
                            .after(value)
                            .description("新增 " + humanizePath(path))
                            .build());
                    break;
                case "remove":
                    changes.add(ChangeItem.builder()
                            .type("removed")
                            .path(path)
                            .before(fromValue)
                            .after(null)
                            .description("删除 " + humanizePath(path))
                            .build());
                    break;
                case "replace":
                    changes.add(ChangeItem.builder()
                            .type("modified")
                            .path(path)
                            .before(fromValue)
                            .after(value)
                            .description(describeReplace(path, fromValue, value))
                            .build());
                    break;
                default:
                    // test 等其它 op 直接忽略；正常 diff 不会产生
            }
        }

        return ModelDiff.builder()
                .changes(changes)
                .summary(buildSummary(changes))
                .rawPatch(patch)
                .build();
    }

    // ----------------------------------------------------- description helpers

    /** "/fields/0/default" → "fields[0].default" */
    static String humanizePath(String jsonPointer) {
        if (jsonPointer == null || jsonPointer.isEmpty() || jsonPointer.equals("/")) {
            return "(根)";
        }
        StringBuilder sb = new StringBuilder();
        String[] parts = jsonPointer.split("/");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            String unescaped = part.replace("~1", "/").replace("~0", "~");
            if (unescaped.matches("\\d+")) {
                sb.append('[').append(unescaped).append(']');
            } else {
                if (sb.length() > 0) sb.append('.');
                sb.append(unescaped);
            }
        }
        return sb.toString();
    }

    private static String describeReplace(String path, JsonNode before, JsonNode after) {
        String human = humanizePath(path);
        String beforeStr = stringify(before);
        String afterStr = stringify(after);
        return human + ": " + beforeStr + " → " + afterStr;
    }

    /** 紧凑展示一个 JsonNode 值，避免日志/UI 里显示大段 JSON */
    private static String stringify(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isValueNode()) return node.asText();
        String s = node.toString();
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }

    private static String buildSummary(List<ChangeItem> changes) {
        if (changes.isEmpty()) return "无变更";
        if (changes.size() == 1) return changes.get(0).getDescription();
        long added    = changes.stream().filter(c -> "added".equals(c.getType())).count();
        long removed  = changes.stream().filter(c -> "removed".equals(c.getType())).count();
        long modified = changes.stream().filter(c -> "modified".equals(c.getType())).count();
        StringBuilder sb = new StringBuilder("共 ").append(changes.size()).append(" 处变更");
        if (added    > 0) sb.append("，新增 ").append(added);
        if (removed  > 0) sb.append("，删除 ").append(removed);
        if (modified > 0) sb.append("，修改 ").append(modified);
        return sb.toString();
    }
}

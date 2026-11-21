/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.flag;
import static com.pixelmc.wenzhouai.service.WzData.number;
import static com.pixelmc.wenzhouai.service.WzData.string;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pixelmc.wenzhouai.network.WzBlockReference;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/** Turns verified references and tool receipt metadata into public timeline copy. */
final class WzBlockToolActivity {
    private static final String READ = "read_block_field";
    private static final Pattern SAFE_FIELD = Pattern.compile("[\\p{L}\\p{N}_:.\\-/~]{1,32}");

    private record Selected(String id, String coordinates) {}

    static WzToolActivity start(String kind, JsonObject args, JsonArray blocks) {
        String ref = string(args, "ref");
        Selected selected = null;
        for (var row : blocks) {
            if (!row.isJsonObject()) continue;
            JsonObject block = row.getAsJsonObject();
            if (ref.equals(string(block, "ref"))) {
                selected = selection(block);
                break;
            }
        }
        String target = "所选方块";
        if (selected != null) {
            String name = WenzhouReadTools.itemDisplayName(selected.id());
            target = name == null || name.isBlank() ? selected.id() : name;
            if (repeated(blocks, selected.id())) target += " (" + selected.coordinates() + ")";
        }
        String path = fieldPath(string(args, "path"));
        if (!path.isEmpty()) target += " / " + path;
        return READ.equals(kind)
                ? new WzToolActivity("读取方块字段", target, "block-field")
                : new WzToolActivity("查看方块数据", target, "block-fields");
    }

    private static Selected selection(JsonObject block) {
        var marker = WzBlockReference.MARKER.matcher(string(block, "reference"));
        if (!marker.matches()) return null;
        return new Selected(marker.group(1), marker.group(2) + ", " + marker.group(3) + ", " + marker.group(4));
    }

    private static boolean repeated(JsonArray blocks, String id) {
        int matches = 0;
        for (var row : blocks) {
            if (!row.isJsonObject()) continue;
            Selected other = selection(row.getAsJsonObject());
            if (other != null && id.equals(other.id()) && ++matches > 1) return true;
        }
        return false;
    }

    private static String fieldPath(String pointer) {
        if (!pointer.startsWith("/") || pointer.length() > 256) return "";
        String[] segments = pointer.substring(1).split("/", -1);
        var labels = new ArrayList<String>();
        for (String raw : segments) {
            String segment = raw.replace("~1", "/").replace("~0", "~");
            if (!SAFE_FIELD.matcher(segment).matches()) break;
            labels.add(fieldName(segment));
        }
        if (labels.size() < segments.length) labels.add("…");
        if (labels.size() > 4) {
            return String.join(" / ", labels.get(0), labels.get(1), "…",
                    labels.get(labels.size() - 2), labels.get(labels.size() - 1));
        }
        return String.join(" / ", labels);
    }

    private static String fieldName(String segment) {
        return switch (segment.toLowerCase(Locale.ROOT)) {
            case "state", "blockstate", "block_state", "properties" -> "方块状态";
            case "blockentity", "block_entity" -> "方块实体";
            case "blockentitytype", "block_entity_type" -> "实体类型";
            case "nbt" -> "NBT";
            case "containercontents", "items", "inventory" -> "物品栏";
            case "containerscope" -> "容器范围";
            case "container" -> "容器";
            case "dimension" -> "维度";
            case "facing" -> "朝向";
            case "waterlogged" -> "含水状态";
            case "slot" -> "槽位";
            case "count" -> "数量";
            case "itemid" -> "物品 ID";
            case "damage" -> "损耗";
            case "spawner" -> "刷怪笼";
            case "entitytype" -> "实体类型";
            case "spawndelay", "delay" -> "生成延迟";
            default -> segment;
        };
    }

    static WzEvidenceSummary detail(String kind, JsonObject args, JsonObject result) {
        return switch (string(result, "status")) {
            case "ok" -> successful(kind, result);
            case "forbidden" -> new WzEvidenceSummary("查阅已停止", "查阅已停止");
            case "stale" -> new WzEvidenceSummary("查阅未完成", "引用已失效 · 请重新选择方块");
            case "limit_reached" -> new WzEvidenceSummary("查阅受限", "已达本轮方块数据查阅上限");
            case "invalid_request" -> new WzEvidenceSummary("查阅未完成", "方块引用或字段路径无效");
            default -> new WzEvidenceSummary("查阅未完成", "所选方块或字段暂不可用");
        };
    }

    private static WzEvidenceSummary successful(String kind, JsonObject result) {
        boolean read = READ.equals(kind);
        boolean items = result.has("items") && result.get("items").isJsonArray();
        var rows = result.get(items ? "items" : "fields");
        boolean truncated = flag(result, "truncated");
        if (rows == null || !rows.isJsonArray()) {
            return read ? new WzEvidenceSummary("已读取", "已读取 1 项字段" + (truncated ? " · 字段值已截断" : ""))
                    : new WzEvidenceSummary("已查阅", "共 " + Math.max(0, number(result, "total")) + " 项字段");
        }
        int count = rows.getAsJsonArray().size();
        long total = Math.max(count, number(result, "total")), offset = Math.max(0, number(result, "offset"));
        String unit = items ? "个条目" : "项字段";
        String detail = "共 " + total + " " + unit;
        if (offset > 0 || count < total || flag(result, "hasMore")) {
            detail = "本页 " + count + " " + unit + " · " + detail;
            if (flag(result, "hasMore")) detail += " · 还有后续";
        }
        int restricted = 0;
        for (var row : rows.getAsJsonArray()) {
            if (row.isJsonObject() && "forbidden".equals(string(row.getAsJsonObject(), "availability"))) restricted++;
        }
        if (restricted > 0) detail += " · 本页 " + restricted + " 项受限";
        if (truncated) detail += " · 已达本页返回上限";
        return new WzEvidenceSummary(read ? "已读取" : "已查阅", detail);
    }

    private WzBlockToolActivity() {}
}

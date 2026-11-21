package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Keeps coverage warnings and references to identical large fields within this model turn. */
final class WzTurnEvidence {
    private static final Pattern GLOBAL_DENIAL = Pattern.compile(
            "(?:生存(?:模式)?(?:中|下|里)?|正常游玩|常规生存)(?:完全|根本|也|是)?(?:无法|不能|不可|没法|拿不|得不)(?:直接)?(?:获得|获取|取得|拿到|到)"
            + "|(?:没有|不存在|无)(?:任何|正常|常规|可用的?)?(?:的)?生存(?:模式)?(?:中)?(?:获取|获得|取得)(?:途径|方式|方法|渠道|来源)"
            + "|(?:所有|全部)(?:获取|获得)?(?:途径|渠道|来源)(?:均|都|已经|已)?(?:排除|查尽|查完)"
            + "|(?:只能|仅能|只有)(?:通过|使用|用|在|靠)?(?:创造|指令|命令|管理员)"
            + "|(?:查尽|排除了?)(?:本服)?(?:所有|全部)(?:来源|途径|获取方式)");
    private static final Pattern QUALIFIED = Pattern.compile(
            "(?:不|无法|很难)(?:能|足以)?(?:确认|确定|断定|断言|证明|说明|排除)|不等于|并非|并不是|(?:尚未|有待)(?:确认|核实)");
    private final Set<String> gaps = new LinkedHashSet<>();
    private final Map<String, String> firstOccurrence = new HashMap<>();
    private final Map<String, JsonObject> referenced = new HashMap<>();

    WzTurnEvidence(JsonObject overview) {
        if (overview == null) return;
        observe(overview);
        if (overview.has("recipe_material_acquisition"))
            observeAcquisition(overview.getAsJsonObject("recipe_material_acquisition"));
        if ("live".equals(WzData.string(overview, "status")) && overview.has("items"))
            for (var row : overview.getAsJsonArray("items")) {
                var item = row.getAsJsonObject();
                referenced.put(WzData.string(item, "item"), item);
            }
    }

    JsonObject selectedItem(String id) {
        JsonObject selected = referenced.get(id);
        if (selected == null) return null;
        var result = new JsonObject();
        result.addProperty("status", "live");
        result.addProperty("query", id);
        result.addProperty("unique_item", id);
        result.addProperty("message", "玩家已选定此物品。配方资料见本轮物品引用。");
        return result;
    }

    void observeAcquisition(JsonObject result) {
        observe(result);
        if (!"live".equals(WzData.string(result, "status"))) gaps.add("事件产物来源尚未核实");
    }

    void observe(JsonObject result) {
        if (WzData.number(result, "unreadable") > 0) gaps.add("部分资料未能解析");
        if (WzData.flag(result, "time_limit_reached")) gaps.add("查询超时");
        if (WzData.flag(result, "truncated")) gaps.add("查询结果被截断");
        var coverage = WzData.object(result, "coverage");
        if (WzData.flag(coverage, "partial") || WzData.flag(coverage, "dynamicSourcesUnresolved")
                || "timeout".equals(WzData.string(result, "status")))
            gaps.add("查询只覆盖部分事件");
    }

    String modelReceipt(String call, JsonObject result) {
        observe(result);
        JsonObject projected = result.deepCopy();
        for (String field : new String[]{"output", "pack_check", "pack_changes"}) {
            JsonElement value = result.get(field);
            if (value == null) continue;
            String content = value.toString();
            if (content.length() < 320) continue;
            String previous = firstOccurrence.putIfAbsent(field + content, call);
            if (previous != null) {
                projected.remove(field);
                projected.addProperty(field + "_same_as_tool_call", previous);
            }
        }
        return projected.toString();
    }

    String review(String answer) {
        if (gaps.isEmpty()) return "";
        // Review affirmative assertions only. A player can legitimately ask about an unverified claim.
        for (String clause : answer.replace("*", "").split("[。！？!?；;\\n]")) {
            if (GLOBAL_DENIAL.matcher(clause.replace(" ", "")).find()
                    && !QUALIFIED.matcher(clause).find())
                return String.join("、", gaps);
        }
        return "";
    }
}

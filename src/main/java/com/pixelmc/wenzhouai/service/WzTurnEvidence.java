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
    private static final Pattern GLOBAL_DENIAL =
            Pattern.compile(
                    "(?:生存(?:模式)?(?:中|下|里)?|正常游玩|常规生存)(?:完全|根本|也|是)?(?:无法|不能|不可|没法|拿不|得不)(?:直接)?(?:获得|获取|取得|拿到|到)"
                        + "|(?:没有|不存在|无)(?:任何|正常|常规|可用的?)?(?:的)?生存(?:模式)?(?:中)?(?:获取|获得|取得)(?:途径|方式|方法|渠道|来源)"
                        + "|(?:所有|全部)(?:获取|获得)?(?:途径|渠道|来源)(?:均|都|已经|已)?(?:排除|查尽|查完)"
                        + "|(?:只能|仅能|只有)(?:通过|使用|用|在|靠)?(?:创造|指令|命令|管理员)"
                        + "|(?:查尽|排除了?)(?:本服)?(?:所有|全部)(?:来源|途径|获取方式)");
    private static final Pattern QUALIFIED =
            Pattern.compile(
                    "(?:不|无法|很难)(?:能|足以)?(?:确认|确定|断定|断言|证明|说明|排除)|不等于|并非|并不是|(?:尚未|有待)(?:确认|核实)");
    private static final Pattern EXCLUSIVE_DROP =
            Pattern.compile(
                    "(?:一只|单只|一击|一次击杀|每只|同一次击杀).{0,30}(?:不可能|无法|不能|绝不|必定只|只能|0[%％])"
                            + "|(?:不可能|无法|不能|绝不).{0,12}(?:同时掉|一次掉齐|同时获得)");
    private boolean lootPool;
    private final Set<String> gaps = new LinkedHashSet<>();
    private final Map<String, String> firstOccurrence = new HashMap<>();
    private final Map<String, JsonObject> referenced = new HashMap<>();

    private final Map<String, JsonObject> exactItems = new HashMap<>();

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

    JsonObject findItem(String query, WenzhouChat.DescribedLookup lookup) throws Exception {
        var selected = selectedItem(query);
        if (selected != null) return selected;
        var cached = exactItems.get(query);
        if (cached != null) {
            var result = cached.deepCopy();
            result.addProperty("query", query);
            if (query.equals(WzData.string(result, "unique_item")))
                result.addProperty("match", "id");
            return result;
        }
        var result = lookup.query("live", "find_item", query, 1, "");
        String id = WzData.string(result, "unique_item");
        if (!id.isEmpty()
                && !result.has("entity_matches")
                && "live".equals(WzData.string(result, "status"))) {
            var copy = result.deepCopy();
            exactItems.put(query, copy);
            exactItems.put(id, copy);
        }
        return result;
    }

    void observeAcquisition(JsonObject result) {
        observe(result);
        if (!"live".equals(WzData.string(result, "status"))) gaps.add("事件产物来源尚未核实");
    }

    void observe(JsonObject result) {
        if ("loaded_loot_pools".equals(WzData.string(result, "probability_scope"))) lootPool = true;
        var changes = WzData.object(result, "pack_changes");
        if (WzData.number(changes, "unreadable") > 0 || WzData.flag(changes, "more"))
            gaps.add("部分战利品修改尚未核实");
        if (result.has("loot_table_evidence") && result.get("loot_table_evidence").isJsonObject())
            observe(result.getAsJsonObject("loot_table_evidence"));
        for (String branch :
                new String[] {
                    "recipes",
                    "loot_sources",
                    "chest_sources",
                    "worldgen",
                    "shop",
                    "quests",
                    "trades"
                }) {
            if (result.has(branch) && result.get(branch).isJsonObject())
                observe(result.getAsJsonObject(branch));
        }
        if (WzData.number(result, "unreadable") > 0) gaps.add("部分资料未能解析");
        if ("index_building".equals(WzData.string(result, "status"))) gaps.add("结构容器关系尚未核实");
        if (WzData.flag(result, "time_limit_reached")) gaps.add("查询超时");
        if (WzData.flag(result, "truncated")) gaps.add("查询结果被截断");
        var coverage = WzData.object(result, "coverage");
        if (WzData.flag(coverage, "partial")
                || WzData.flag(coverage, "dynamicSourcesUnresolved")
                || "timeout".equals(WzData.string(result, "status"))) gaps.add("查询只覆盖部分事件");
    }

    String modelReceipt(String call, JsonObject result) {
        observe(result);
        JsonObject projected = result.deepCopy();
        for (String field : new String[] {"output", "pack_check", "pack_changes"}) {
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
        if (lootPool
                && EXCLUSIVE_DROP.matcher(answer.replace("*", "")).find()
                && !Pattern.compile(
                                "(?:仅|只|限于|单看|只看|仅靠|靠|就|按|根据|从|这个|该|此|上述|同一|额外)(?:本服|当前|这|该|个|一|已核实的|额外|掉落|战利品|奖励|有效|生效的|运行中的|所查|实体){0,8}池(?:本身|内|中)?")
                        .matcher(answer)
                        .find()) return "表内概率不能用来排除其他掉落事件。请把单只是否能同时掉齐的断言明确限定在所查池内";
        if (gaps.isEmpty()) return "";
        // Review affirmative assertions only. A player can legitimately ask about an unverified
        // claim.
        for (String clause : answer.replace("*", "").split("[。！？!?；;\\n]")) {
            if (GLOBAL_DENIAL.matcher(clause.replace(" ", "")).find()
                    && !QUALIFIED.matcher(clause).find()) return String.join("、", gaps);
        }
        return "";
    }
}

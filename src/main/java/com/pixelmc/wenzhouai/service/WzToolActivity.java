package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.StringJoiner;
import static com.pixelmc.wenzhouai.service.WzData.string;

/** Public projection of a tool call. Memory bodies and command text stay out of the timeline. */
record WzToolActivity(String action, String target, String icon) {
    static WzToolActivity start(
            String mode,
            String kind,
            String target,
            String purpose,
            JsonObject args,
            String selectedSummary) {
        return switch (kind) {
            case "structure_generation" ->
                    new WzToolActivity("查询结构生成", target.equals("*") ? "" : target, "structure");
            case "structure_chests" ->
                    new WzToolActivity(
                            "查阅结构宝箱", target.equals("*") ? "" : target, "structure-loot");
            case "chest_sources" ->
                    new WzToolActivity(
                            "反查宝箱来源", target.equals("*") ? "" : target, "structure-loot");
            case "trade_offers" ->
                    new WzToolActivity("查阅商人交易", target.equals("*") ? "" : target, "trade");
            case "merchant_offers" ->
                    new WzToolActivity("查看当前报价", target.equals("*") ? "" : target, "trade");
            case "quest_search" ->
                    new WzToolActivity("检索任务手册", target.equals("*") ? "" : target, "quest");
            case "quest_rewards" ->
                    new WzToolActivity("反查任务奖励", target.equals("*") ? "" : target, "reward");
            case "quest_detail" ->
                    new WzToolActivity("查阅任务进度", target.equals("*") ? "" : target, "quest");
            case "shop_balance" ->
                    new WzToolActivity("查询商店余额", target.equals("*") ? "" : target, "wallet");
            case "shop_categories" ->
                    new WzToolActivity("浏览商店分区", target.equals("*") ? "" : target, "store");
            case "shop_entries" ->
                    new WzToolActivity("检索商店交易", target.equals("*") ? "" : target, "store");
            case "shop_item" ->
                    new WzToolActivity("反查商店商品", target.equals("*") ? "" : target, "store-search");
            case "shop_detail" ->
                    new WzToolActivity("查看商品详情", target.equals("*") ? "" : target, "store");
            case "shop_quote" ->
                    new WzToolActivity("核算交易数量", target.equals("*") ? "" : target, "quote");
            case "food_book_stats" -> new WzToolActivity("统计手册食物", target, "food-book");
            case "worldgen_sources" -> new WzToolActivity("查询自然生成地点", target, "worldgen");
            case "entity_spawns" -> new WzToolActivity("查询生物生成", target, "spawn");
            case "biome_tag" -> new WzToolActivity("查询群系标签", target, "biome");
            case "machine_conversions" -> new WzToolActivity("查询机器转换", target, "conversion");
            case "find_item" -> new WzToolActivity("查找物品", target, "item");
            case "item_overview" -> new WzToolActivity("查阅引用物品", target.replace(",", "、"), "item");
            case "tag", "search_tags" -> new WzToolActivity("查询标签", target, "tag");
            case "item_tags" -> new WzToolActivity("反查物品标签", target, "tags");
            case "locate_gameplay" -> new WzToolActivity("定位玩法位置", target, "locate");
            case "read_method" -> new WzToolActivity("定位玩法位置", target.split("#", 2)[0], "locate");
            case "pack_changes" -> new WzToolActivity("检索整合包修改", target, "pack-changes");
            case "recipe" ->
                    new WzToolActivity("mod".equals(mode) ? "核对配方来源" : "查询配方", target, "bench");
            case "recipes_using_item" -> new WzToolActivity("查询材料用途", target, "bench");
            case "recipes_for_item" -> new WzToolActivity("查询产物配方", target, "bench");
            case "food_effects" -> new WzToolActivity("查询食用效果", target, "food");
            case "loot_sources" -> new WzToolActivity("反查掉落来源", target, "loot");
            case "loot_table" -> new WzToolActivity("查询战利品表", target, "loot");
            case "search_memory" -> new WzToolActivity("检索记忆库", keywords(args), "memory-search");
            case "read_memory" ->
                    new WzToolActivity(
                            "检索记忆正文",
                            selectedSummary.isEmpty() ? "所选条目" : selectedSummary,
                            "memory-read");
            case "save_memory" ->
                    new WzToolActivity("保存记忆", string(args, "summary"), "memory-write");
            case "skip_memory" -> new WzToolActivity("确认记忆", "", "memory-write");
            case "calculate" -> new WzToolActivity("计算", purpose, "calculator");
            case "command" -> new WzToolActivity("运行命令", purpose, "command");
            default -> new WzToolActivity("查阅玩法资料", "", "book");
        };
    }

    WzToolActivity finished(String kind, JsonObject result) {
        if (kind.equals("merchant_offers") && string(result,"provenance").equals("last_seen"))
            return new WzToolActivity("查看上次商人报价",target,icon);
        if (result.has("entry") || result.has("quest")) {
            var row = result.get(result.has("entry") ? "entry" : "quest");
            if (row.isJsonObject() && !string(row.getAsJsonObject(), "title").isBlank())
                return new WzToolActivity(action, string(row.getAsJsonObject(), "title"), icon);
        }
        if (kind.equals("read_memory") && !string(result, "summary").isBlank())
            return new WzToolActivity(action, string(result, "summary"), icon);
        if (kind.equals("save_memory") && result.has("saved") && result.has("created"))
            return result.get("created").getAsBoolean()
                    ? new WzToolActivity("写入新的记忆", target, "memory-add")
                    : new WzToolActivity("修改已有记忆", target, "memory-edit");
        return this;
    }

    String label() {
        String text = target.replaceAll("[\\p{Cc}\\p{Cf}]+", " ").strip();
        if (text.codePointCount(0, text.length()) > 160)
            text = text.substring(0, text.offsetByCodePoints(0, 159)) + "…";
        return text.isEmpty() ? action : action + " · " + text;
    }

    private static String keywords(JsonObject args) {
        if (!args.has("keywords") || !args.get("keywords").isJsonArray()) return "";
        var words = new StringJoiner("、");
        for (JsonElement word : args.getAsJsonArray("keywords"))
            if (word.isJsonPrimitive() && word.getAsJsonPrimitive().isString())
                words.add(word.getAsString());
        return words.length() == 0 ? "全部条目" : words.toString();
    }
}

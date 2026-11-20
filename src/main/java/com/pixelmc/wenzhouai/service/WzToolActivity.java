package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.StringJoiner;
import static com.pixelmc.wenzhouai.service.WzData.string;

/** Public projection of a tool call. Memory bodies and command text stay out of the timeline. */
record WzToolActivity(String action, String target, String icon) {
    static WzToolActivity start(String mode, String kind, String target, String purpose,
                                JsonObject args, String selectedSummary) {
        return switch (kind) {
            case "find_item" -> new WzToolActivity("查找物品", target, "item");
            case "item_overview" -> new WzToolActivity("查阅引用物品", target.replace(",", "、"), "item");
            case "tag", "search_tags" -> new WzToolActivity("查询标签", target, "tag");
            case "item_tags" -> new WzToolActivity("反查物品标签", target, "tags");
            case "locate_gameplay" -> new WzToolActivity("定位玩法位置", target, "locate");
            case "read_method" -> new WzToolActivity("定位玩法位置", target.split("#", 2)[0], "locate");
            case "pack_changes" -> new WzToolActivity("检索整合包修改", target, "pack-changes");
            case "recipe" -> new WzToolActivity("mod".equals(mode) ? "核对配方来源" : "查询配方", target, "bench");
            case "recipes_for_item" -> new WzToolActivity("查询产物配方", target, "bench");
            case "food_effects" -> new WzToolActivity("查询食用效果", target, "food");
            case "loot_sources" -> new WzToolActivity("反查掉落来源", target, "loot");
            case "loot_table" -> new WzToolActivity("查询战利品表", target, "loot");
            case "search_memory" -> new WzToolActivity("检索记忆库", keywords(args), "memory-search");
            case "read_memory" -> new WzToolActivity("检索记忆正文", selectedSummary.isEmpty() ? "所选条目" : selectedSummary, "memory-read");
            case "save_memory" -> new WzToolActivity("保存记忆", string(args, "summary"), "memory-write");
            case "calculate" -> new WzToolActivity("计算", purpose, "calculator");
            case "command" -> new WzToolActivity("运行命令", purpose, "command");
            default -> new WzToolActivity("查阅玩法资料", "", "book");
        };
    }

    WzToolActivity finished(String kind, JsonObject result) {
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
            if (word.isJsonPrimitive() && word.getAsJsonPrimitive().isString()) words.add(word.getAsString());
        return words.length() == 0 ? "全部条目" : words.toString();
    }
}

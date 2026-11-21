package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.*;

import com.google.gson.JsonObject;

record WzEvidenceSummary(String outcome, String detail) {
    static WzEvidenceSummary from(String mode, String kind, JsonObject result) {
        String status = string(result, "status");
        if (WzManagerGameplay.TOOLS.contains(kind) && !kind.equals("food_book_stats")) {
            if (kind.equals("merchant_offers") && status.equals("last_seen"))
                return new WzEvidenceSummary("已查阅","上次交易界面的报价 · "+number(result,"ageMs")/1000+" 秒前");
            if(!status.equals("live"))return new WzEvidenceSummary(status.equals("mod_not_installed")?"未安装":"未取得结果",string(result,"message"));
            if (kind.equals("event_outputs")) {
                var coverage = object(result, "coverage");
                return new WzEvidenceSummary("已查询", "事件线索 " + number(result, "total") + " 项"
                        + (flag(coverage, "partial") ? " · 部分来源未读" : ""));
            }
            if (result.has("entry")) {
                var entry = result.getAsJsonObject("entry");
                String detail = switch (string(entry,"available_now")) {
                    case "yes" -> "当前可以交易";
                    case "no" -> "当前交易条件未满足";
                    default -> "有交易条件待核实";
                };
                return new WzEvidenceSummary("已核对",detail);
            }
            String group = switch (kind) {
                case "shop_item" -> "匹配的商店交易";
                case "quest_search" -> "可见任务";
                case "quest_rewards" -> "匹配的任务奖励";
                case "merchant_offers" -> "当前商人报价";
                case "trade_offers" -> "可能出现的交易";
                case "chest_sources","structure_chests" -> "宝箱来源线索";
                default -> "查询结果";
            };
            return new WzEvidenceSummary("已查询",group+" "+number(result,"total")+" 项 · 第 "+Math.max(1,number(result,"page"))+" 页");
        }
        if (kind.equals("food_book_stats") && status.equals("mod_not_installed"))
            return new WzEvidenceSummary("未安装", WzFoodBook.MISSING);
        if (kind.equals("skip_memory") && result.has("skipped"))
            return new WzEvidenceSummary("未写入", "未写入 · " + string(result, "skipped"));
        if (kind.equals("save_memory") && status.equals("invalid_request"))
            return new WzEvidenceSummary("正在整理", "正在调整玩法笔记");
        if ("memory".equals(mode) && !status.isEmpty())
            return new WzEvidenceSummary("记忆操作未完成", string(result, "message"));
        if ("pack_changes".equals(status))
            return new WzEvidenceSummary("已检索", WzPackChanges.caption(result));
        if (result.has("suggestions"))
            return new WzEvidenceSummary("已查阅", "精确标签未命中 · 相关标签 " + number(result.getAsJsonObject("suggestions"), "total") + " 项");
        if ("memory".equals(mode) && status.isEmpty()) {
            if (result.has("matches")) return new WzEvidenceSummary("已检索", "匹配 " + number(result, "total") + " 条玩法笔记");
            if (result.has("saved")) return new WzEvidenceSummary("已更新", "已保存到当前整合包档案");
            return new WzEvidenceSummary("已读取", "当前整合包的玩法笔记");
        }
        return switch (status) {
            case "calculated" -> new WzEvidenceSummary("已计算",
                    string(result, "expression") + " = " + string(result, "result"));
            case "invalid_calculation" -> new WzEvidenceSummary("无法计算", string(result, "message"));
            case "live" ->
                    switch (kind) {
                        case "food_book_stats" -> {
                            var personal = result.getAsJsonObject("personal");
                            String detail = personal != null && "live".equals(string(personal,"status"))
                                    ? "已收集 " + number(personal,"collected") + " / " + number(result,"book_total") + " 种"
                                    : "手册可收集 " + number(result,"book_total") + " 种食物";
                            yield new WzEvidenceSummary("已统计", detail);
                        }
                        case "worldgen_sources" -> new WzEvidenceSummary("已查询", number(result, "total") == 0
                                ? "已核对的地物中未找到匹配" : "匹配 " + number(result, "biome_count") + " 个群系");
                        case "entity_spawns" -> new WzEvidenceSummary("已查询", "群系表 " + number(result, "biome_entries") + " 项 · 生成配置 " + number(result, "targeted_config_rules") + " 条");
                        case "biome_tag" -> new WzEvidenceSummary("已核对", "群系标签含 " + number(result, "total") + " 个群系");
                        case "machine_conversions" -> new WzEvidenceSummary("已核对", "铜锅转换匹配 " + number(result, "total") + " 个烹饪配方");
                        case "find_item" -> {
                            String id = string(result, "unique_item");
                            long count = number(result, "total");
                            if (!id.isEmpty()) {
                                JsonObject item = result.getAsJsonArray("candidates").get(0).getAsJsonObject();
                                yield new WzEvidenceSummary("已找到", string(item, "mod_name") + " · " + id);
                            }
                            if (count == 0) yield new WzEvidenceSummary("未找到", "本服物品中没有匹配项");
                            yield new WzEvidenceSummary("已查阅", "找到 " + count + " 项候选物品");
                        }
                        case "recipes_using_item" -> new WzEvidenceSummary("已核对", "材料或工具匹配 " + number(result, "total") + " 项");
                        case "recipes_for_item" ->
                                new WzEvidenceSummary(
                                        "已核对",
                                        "本服已加载配方 · 产物匹配 "
                                                + Math.max(0, number(result, "total"))
                                                + " 项"
                                                + (number(result, "unreadable") > 0
                                                        ? " · 部分动态产出未核实"
                                                        : ""));
                        case "search_tags", "item_tags" -> new WzEvidenceSummary("已查阅", "本服标签匹配 " + number(result, "total") + " 项");
                        case "item_overview" -> new WzEvidenceSummary("已核对", "引用物品与已加载的产物配方");
                        case "food_effects" -> new WzEvidenceSummary("已读取", "本服默认物品栈的食用属性");
                        case "loot_sources" -> new WzEvidenceSummary("已查阅", "战利品候选表 · " + number(result, "total") + " 张"
                                + (number(result, "unreadable") > 0 ? " · 未解析 " + number(result, "unreadable") + " 张" : ""));
                        case "loot_table" -> new WzEvidenceSummary("已读取", "本服战利品表定义 · 第 " + number(result, "page") + " 页");
                        case "tag" ->
                                new WzEvidenceSummary(
                                        "已核对",
                                        "本服生效标签 · 共 "
                                                + Math.max(0, number(result, "total"))
                                                + " 个条目");
                        default -> new WzEvidenceSummary("已核对", "本服当前生效的配方");
                    };
            case "partial" -> new WzEvidenceSummary("部分核对", "本服配方已加载 · 工序细节需继续查阅");
            case "recipe_evidence" -> {
                boolean live = result.has("live_fields"), pack = result.has("pack_json");
                yield new WzEvidenceSummary(
                        live || pack ? "已核对" : "部分核对",
                        live && pack
                                ? "当前配方实例字段与最高优先级数据包定义"
                                : live
                                        ? "当前配方实例字段"
                                        : pack ? "最高优先级数据包定义 · 未取得实例字段" : "本服已加载配方 · 未取得槽位证据");
            }
            case "sandbox_output" -> new WzEvidenceSummary(
                    flag(result, "time_limit_reached") ? "查询超时"
                            : flag(result, "truncated") ? "部分查阅"
                            : number(result, "exit_code") == 0 ? "已查阅" : "命令未成功",
                    result.has("truncated") && result.get("truncated").getAsBoolean()
                            ? "玩法资料沙箱 · 输出已截断" : "玩法资料沙箱 · 退出码 " + number(result, "exit_code"));
            case "installed_code" -> new WzEvidenceSummary("已查阅", "当前安装模组的实现资料 · 运行规则仍需结合本服数据");
            case "matched_recipe_fields" -> new WzEvidenceSummary("已核对", "随包定义与本服生效配方一致");
            case "resource_difference", "modified" ->
                    new WzEvidenceSummary("发现差异", "随包定义与本服生效数据可能不同");
            case "not_found", "not_registered", "not_active", "empty" ->
                    new WzEvidenceSummary("未找到", "本服当前数据没有匹配项");
            default ->
                    new WzEvidenceSummary(
                            "暂无法核对", "mod".equals(mode) ? "随包配方来源查阅未完成" : "本次只读查阅未取得可核实的结果");
        };
    }
}

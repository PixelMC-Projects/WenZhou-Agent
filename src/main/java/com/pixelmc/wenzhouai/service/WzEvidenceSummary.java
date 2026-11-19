package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.*;

import com.google.gson.JsonObject;

record WzEvidenceSummary(String outcome, String detail) {
    static WzEvidenceSummary from(String mode, String kind, JsonObject result) {
        String status = string(result, "status");
        return switch (status) {
            case "calculated" -> new WzEvidenceSummary("已计算",
                    string(result, "expression") + " = " + string(result, "result"));
            case "invalid_calculation" -> new WzEvidenceSummary("无法计算", string(result, "message"));
            case "live" ->
                    switch (kind) {
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
                        case "recipes_for_item" ->
                                new WzEvidenceSummary(
                                        "已核对",
                                        "本服已加载配方 · 固定产物匹配 "
                                                + Math.max(0, number(result, "total"))
                                                + " 项"
                                                + (number(result, "unreadable") > 0
                                                        ? " · 部分动态产出未核实"
                                                        : ""));
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

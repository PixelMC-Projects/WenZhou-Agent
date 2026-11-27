/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

record WzUsage(String cost, String note, List<Row> rows, String displayCost, String costLabel) {
    private static final WzUsage EMPTY = from(List.of(), false);
    private static final WzUsage EMPTY_TERRA = from(List.of(), true);

    /** Turns and their receipts are append-only until the selected conversation changes. */
    static final class Cache {
        private record Stamp(
                WzConversation.Turn turn, int receiptCount, boolean answered, boolean missing) {
            boolean matches(WzConversation.Turn current) {
                return turn == current
                        && receiptCount == current.receipts.size()
                        && answered == (current.reply != null)
                        && missing == current.usageMissing;
            }
        }

        private List<Stamp> stamps = List.of();
        private WzUsage usage = EMPTY;
        private boolean terra;

        WzUsage get(List<WzConversation.Turn> turns, boolean showTerra) {
            if (terra == showTerra && turns.size() == stamps.size()) {
                int index = 0;
                while (index < turns.size() && stamps.get(index).matches(turns.get(index))) index++;
                if (index == turns.size()) return usage;
            }
            if (turns.isEmpty()) {
                clear();
            } else {
                stamps =
                        turns.stream()
                                .map(
                                        turn ->
                                                new Stamp(
                                                        turn,
                                                        turn.receipts.size(),
                                                        turn.reply != null,
                                                        turn.usageMissing))
                                .toList();
                usage = from(turns, showTerra);
            }
            terra = showTerra;
            if (turns.isEmpty()) usage = terra ? EMPTY_TERRA : EMPTY;
            return usage;
        }

        void clear() {
            stamps = List.of();
            usage = EMPTY;
            terra = false;
        }
    }

    record Row(String label, String value) {}

    static WzUsage from(List<WzConversation.Turn> turns, boolean terra) {
        long hit = 0, miss = 0, output = 0, reasoning = 0, nano = 0;
        int planReports = 0, deepSeekReports = 0;
        boolean missing = false, known = false, hasProjectCharges = false;
        long projectCharges = 0;
        Set<String> seen = new HashSet<>();
        for (WzConversation.Turn turn : turns) {
            boolean terraTurn = WzModelPicker.TERRA.equals(turn.modelSelection)
                    || turn.modelSelection.startsWith(WzModelPicker.TERRA + "/");
            if (terraTurn == terra)
                missing |= turn.usageMissing || turn.reply != null && turn.receipts.isEmpty();
            for (JsonElement item : turn.receipts) {
                if (!item.isJsonObject()) continue;
                JsonObject receipt = item.getAsJsonObject();
                String billing = WzConversation.str(receipt, "billing");
                String id = WzConversation.str(receipt, "id");
                if (id.isEmpty() || !seen.add(id)) continue;
                boolean project = "openai_api".equals(billing);
                if (!terra && project) {
                    hasProjectCharges = true;
                    long usd = WzConversation.number(receipt, "nanoUsd", -1);
                    projectCharges = projectCharges < 0 || usd < 0 ? -1 : projectCharges + usd;
                }
                if (terra != project) continue;
                long h = WzConversation.number(receipt, "hit", -1);
                long m = WzConversation.number(receipt, "miss", -1);
                long o = WzConversation.number(receipt, "output", -1);
                if (h < 0 || m < 0 || o < 0) {
                    missing = true;
                    continue;
                }
                known = true;
                hit += h;
                miss += m;
                output += o;
                long r = WzConversation.number(receipt, "reasoning", -1);
                reasoning = reasoning < 0 || r < 0 ? -1 : reasoning + r;
                missing |= r < 0;
                if ("chatgpt_plan".equals(billing)) {
                    planReports++;
                } else {
                    deepSeekReports++;
                    long n = WzConversation.number(receipt, terra ? "nanoUsd" : "nanoYuan", -1);
                    missing |= n < 0;
                    nano = nano < 0 || n < 0 ? -1 : nano + n;
                }
            }
        }
        boolean unavailable = missing && !known;
        NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.SIMPLIFIED_CHINESE);
        NumberFormat money = NumberFormat.getNumberInstance(Locale.SIMPLIFIED_CHINESE);
        money.setMinimumFractionDigits(terra ? 4 : 3);
        money.setMaximumFractionDigits(terra ? 4 : 3);
        money.setRoundingMode(java.math.RoundingMode.HALF_UP);
        boolean planOnly = planReports > 0 && deepSeekReports == 0;
        boolean mixed = planReports > 0 && deepSeekReports > 0;
        String cost = planOnly ? "ChatGPT 套餐"
                : unavailable || nano < 0 ? "—" : (terra ? "$" : "¥") + money.format(BigDecimal.valueOf(nano, 9));
        String costRow = planOnly ? "计费来源" : mixed ? "DeepSeek 累计费用" : terra ? "Terra 累计费用（美元）" : "累计费用";
        NumberFormat percent = NumberFormat.getPercentInstance(Locale.SIMPLIFIED_CHINESE);
        percent.setMaximumFractionDigits(1);
        String note = !missing ? "" : unavailable ? "用量未记录" : "仅含已记录用量";
        if (mixed) note = "套餐 " + numbers.format(planReports) + " 次" + (missing ? " · 用量不全" : "");
        var rows = new ArrayList<>(List.of(
                        new Row("未命中输入", unavailable ? "—" : numbers.format(miss)),
                        new Row("命中输入", unavailable ? "—" : numbers.format(hit)),
                        new Row("输出", unavailable ? "—" : numbers.format(output)),
                        new Row(
                                "其中思考",
                                unavailable || reasoning < 0 ? "—" : numbers.format(reasoning)),
                        new Row(
                                "缓存命中率",
                                hit + miss == 0
                                        ? "—"
                                        : percent.format((double) hit / (hit + miss))),
                        new Row(costRow, cost)));
        if (hasProjectCharges) {
            money.setMinimumFractionDigits(4);
            money.setMaximumFractionDigits(4);
            rows.add(new Row("Terra 累计费用（美元）", projectCharges < 0 ? "—"
                    : "$" + money.format(BigDecimal.valueOf(projectCharges, 9))));
        }
        return new WzUsage(cost, note, List.copyOf(rows), missing ? cost + " *" : cost,
                planOnly ? "本对话用量计入 ChatGPT 套餐" : "本对话" + costRow + " " + cost);
    }
}

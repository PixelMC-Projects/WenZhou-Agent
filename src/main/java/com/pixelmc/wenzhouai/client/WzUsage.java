/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

record WzUsage(String cost, String note, List<Row> rows, String displayCost, String costLabel) {
    private static final WzUsage EMPTY = from(List.of());

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

        WzUsage get(List<WzConversation.Turn> turns) {
            if (turns.size() == stamps.size()) {
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
                usage = from(turns);
            }
            return usage;
        }

        void clear() {
            stamps = List.of();
            usage = EMPTY;
        }
    }

    record Row(String label, String value) {}

    static WzUsage from(List<WzConversation.Turn> turns) {
        long hit = 0, miss = 0, output = 0, reasoning = 0, nano = 0;
        boolean missing = false, known = false;
        Set<String> seen = new HashSet<>();
        for (WzConversation.Turn turn : turns) {
            missing |= turn.usageMissing;
            if (turn.reply != null && turn.receipts.isEmpty()) missing = true;
            for (JsonElement item : turn.receipts) {
                if (!item.isJsonObject()) continue;
                JsonObject receipt = item.getAsJsonObject();
                String id = WzConversation.str(receipt, "id");
                if (id.isEmpty() || !seen.add(id)) continue;
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
                long n = WzConversation.number(receipt, "nanoYuan", -1);
                missing |= r < 0 || n < 0;
                nano = nano < 0 || n < 0 ? -1 : nano + n;
            }
        }
        boolean unavailable = missing && !known;
        NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.SIMPLIFIED_CHINESE);
        NumberFormat yen = NumberFormat.getNumberInstance(Locale.SIMPLIFIED_CHINESE);
        yen.setMinimumFractionDigits(3);
        yen.setMaximumFractionDigits(3);
        yen.setRoundingMode(java.math.RoundingMode.HALF_UP);
        String cost = unavailable || nano < 0 ? "—" : "¥" + yen.format(BigDecimal.valueOf(nano, 9));
        NumberFormat percent = NumberFormat.getPercentInstance(Locale.SIMPLIFIED_CHINESE);
        percent.setMaximumFractionDigits(1);
        String note = !missing ? "" : unavailable ? "用量未记录" : "仅含已记录用量";
        return new WzUsage(
                cost,
                note,
                List.of(
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
                        new Row("累计费用", cost)),
                note.isEmpty() ? cost : cost + " *",
                "本对话累计费用 " + cost);
    }
}

/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

record WzQuota(boolean enabled, boolean unlimited, boolean exhausted, float percent,
               long resetAt, String label, String resetLabel) {
    static final WzQuota OFF = new WzQuota(false, false, false, 0, 0, "", "");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    static WzQuota from(JsonObject data) {
        return from(data, false);
    }

    static WzQuota terra(JsonObject data) {
        return from(data, true);
    }

    boolean visible() {
        return enabled || unlimited || exhausted;
    }

    private static WzQuota from(JsonObject data, boolean terra) {
        if (data == null) return OFF;
        boolean enabled = WzConversation.flag(data, "enabled");
        boolean reportedUnlimited = WzConversation.flag(data, "unlimited");
        boolean reportedExhausted = WzConversation.flag(data, "exhausted");
        boolean exhausted = (terra || !reportedUnlimited) && reportedExhausted;
        boolean unlimited = reportedUnlimited && !exhausted;
        if (!enabled && !(terra && (unlimited || exhausted))) return OFF;
        JsonElement value = data.get("remainingPercent");
        float percent = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? Math.max(0, Math.min(100, value.getAsFloat())) : 0;
        if (terra && exhausted) percent = 0;
        long at = unlimited ? 0 : WzConversation.number(data, "resetAt", 0);
        String remaining = percent > 0 && percent < 1 ? "不足 1%" : Math.round(percent) + "%";
        String label = terra
                ? unlimited ? "Terra 美元额度不限" : "Terra 美元剩余额度 " + remaining
                : unlimited ? "额度不限" : "本周剩余额度 " + remaining;
        String reset = at > 0 ? "重置时间 " + TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
                : exhausted ? "当前没有可用额度" : terra ? "" : "首次提问后开始计时";
        return new WzQuota(enabled, unlimited, exhausted, percent, at, label, reset);
    }
}

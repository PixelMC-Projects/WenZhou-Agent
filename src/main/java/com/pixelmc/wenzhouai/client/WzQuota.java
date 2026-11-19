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
        if (data == null || !WzConversation.flag(data, "enabled")) return OFF;
        boolean unlimited = WzConversation.flag(data, "unlimited");
        boolean exhausted = !unlimited && WzConversation.flag(data, "exhausted");
        JsonElement value = data.get("remainingPercent");
        float percent = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? Math.max(0, Math.min(100, value.getAsFloat())) : 0;
        long at = WzConversation.number(data, "resetAt", 0);
        String remaining = percent > 0 && percent < 1 ? "不足 1%" : Math.round(percent) + "%";
        String label = unlimited ? "额度不限" : "本周剩余额度 " + remaining;
        String reset = at > 0 ? "重置时间 " + TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
                : exhausted ? "当前未分配可用额度" : "首次提问后开始计时";
        return new WzQuota(true, unlimited, exhausted, percent, at, label, reset);
    }
}

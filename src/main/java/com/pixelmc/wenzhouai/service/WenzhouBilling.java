package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Flash prices in nano-yuan per token, shared with the two Web previews. */
final class WenzhouBilling {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final JsonObject TARIFF = loadTariff();

    private static volatile JsonObject rates = TARIFF.getAsJsonObject("ratesNanoCnyPerToken");

    static void loadCached(java.nio.file.Path directory) {
        try {
            var cached = WzData.read(directory.resolve("flash-rates.json"));
            if (cached.size() == 0) return;
            WzOfficialPricing.validate(cached);
            rates = cached;
        } catch (RuntimeException invalid) {
            com.mojang.logging.LogUtils.getLogger().warn("WenZhou pricing cache unavailable; using bundled rates");
        }
    }

    static void refresh(java.nio.file.Path directory) {
        try {
            JsonObject verified = WzOfficialPricing.fetch();
            WzData.write(directory.resolve("flash-rates.json"), verified);
            rates = verified;
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        } catch (Exception unavailable) {
            com.mojang.logging.LogUtils.getLogger().warn("WenZhou could not refresh official Flash pricing; retaining last rates");
        }
    }

    private static JsonObject loadTariff() {
        String path = "/assets/wenzhouai/wenzhou/flash-pricing.json";
        try (InputStream source = WenzhouBilling.class.getResourceAsStream(path)) {
            if (source == null) throw new IllegalStateException("Missing Flash tariff");
            return JsonParser.parseReader(new InputStreamReader(source, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read Flash tariff", e);
        }
    }

    static JsonObject quote(long time) {
        ZonedDateTime local = Instant.ofEpochMilli(time).atZone(BEIJING);
        String period = "offPeak";
        if (local.getDayOfWeek() != DayOfWeek.SATURDAY
                && local.getDayOfWeek() != DayOfWeek.SUNDAY
                && isPeakHour(local.getHour())) {
            JsonArray holidays =
                    TARIFF.getAsJsonObject("holidays")
                            .getAsJsonArray(String.valueOf(local.getYear()));
            if (holidays == null) return new JsonObject();
            String monthDay = local.toLocalDate().toString().substring(5);
            boolean holiday = false;
            for (JsonElement entry : holidays) {
                JsonArray range = entry.getAsJsonArray();
                if (monthDay.compareTo(range.get(0).getAsString()) >= 0
                        && monthDay.compareTo(range.get(1).getAsString()) <= 0) {
                    holiday = true;
                    break;
                }
            }
            if (!holiday) period = "peak";
        }
        JsonObject quote = rates.getAsJsonObject(period).deepCopy();
        quote.addProperty("period", period);
        quote.addProperty("startedAt", time);
        return quote;
    }

    static long cost(JsonObject quote, long hits, long misses, long output) {
        if (!quote.has("period")) return -1;
        try {
            long input = Math.addExact(Math.multiplyExact(hits, quote.get("hit").getAsLong()),
                    Math.multiplyExact(misses, quote.get("miss").getAsLong()));
            return Math.addExact(input, Math.multiplyExact(output, quote.get("output").getAsLong()));
        } catch (ArithmeticException invalidUsage) {
            return -1;
        }
    }

    private static boolean isPeakHour(int hour) {
        for (JsonElement entry : TARIFF.getAsJsonArray("peakHoursBeijing")) {
            JsonArray range = entry.getAsJsonArray();
            if (hour >= range.get(0).getAsInt() && hour < range.get(1).getAsInt()) return true;
        }
        return false;
    }

    private WenzhouBilling() {}
}

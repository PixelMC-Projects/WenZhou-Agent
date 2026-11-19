package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Refresh only the six Flash rates. A changed table or schedule needs review. */
final class WzOfficialPricing {
    private static final URI SOURCE = URI.create("https://api-docs.deepseek.com/zh-cn/quick_start/pricing.md");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    static JsonObject fetch() throws Exception {
        var request = HttpRequest.newBuilder(SOURCE).timeout(Duration.ofSeconds(8)).GET().build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 || response.body().length() > 128_000)
            throw new IllegalArgumentException("Flash pricing document unavailable");
        return parse(response.body());
    }

    static JsonObject parse(String document) {
        String schedule = document.replaceAll("\\s+", "");
        if (!schedule.contains("北京时间周一至周五（不含中国法定节假日）9:00-12:00、14:00-18:00为高峰时段"))
            throw new IllegalArgumentException("Unrecognized Flash pricing schedule");
        JsonObject result = new JsonObject();
        result.add("peak", new JsonObject());
        result.add("offPeak", new JsonObject());
        int column = -1;
        for (String line : document.split("\\R")) {
            if (!line.startsWith("|")) continue;
            var cells = Arrays.stream(line.split("\\|")).map(String::strip).toList();
            if (cells.size() < 5) continue;
            if (cells.get(1).equals("模型")) {
                for (int i = 2; i < cells.size(); i++)
                    if (cells.get(i).matches("deepseek-flash(?:\\(\\d+\\))?")) column = i;
            }
            if (!cells.get(1).startsWith("价格(") || column < 0 || column >= cells.size()) continue;
            String unit = cells.get(2).replace(" ", "");
            String key = switch (unit) {
                case "百万tokens输入/（缓存命中）" -> "hit";
                case "百万tokens输入/（缓存未命中）" -> "miss";
                case "百万tokens输出" -> "output";
                default -> throw new IllegalArgumentException("Unrecognized Flash billing unit");
            };
            String band = switch (cells.get(3)) {
                case "空闲时段" -> "offPeak";
                case "高峰时段" -> "peak";
                default -> throw new IllegalArgumentException("Unrecognized Flash pricing period");
            };
            String amount = cells.get(column);
            if (!amount.matches("[0-9]+(?:\\.[0-9]+)?元") || result.getAsJsonObject(band).has(key))
                throw new IllegalArgumentException("Invalid Flash rate");
            // One yuan per million tokens is 1,000 nano-yuan per token.
            long rate = new BigDecimal(amount.substring(0, amount.length() - 1)).movePointRight(3).longValueExact();
            result.getAsJsonObject(band).addProperty(key, rate);
        }
        validate(result);
        return result;
    }

    static void validate(JsonObject rates) {
        for (String band : List.of("peak", "offPeak")) {
            JsonObject row = rates.getAsJsonObject(band);
            for (String key : List.of("hit", "miss", "output")) {
                if (row == null || !row.has(key) || !row.get(key).isJsonPrimitive()
                        || !row.getAsJsonPrimitive(key).isNumber()
                        || row.get(key).getAsBigDecimal().longValueExact() < 0)
                    throw new IllegalArgumentException("Incomplete Flash rates");
            }
        }
    }

    private WzOfficialPricing() {}
}

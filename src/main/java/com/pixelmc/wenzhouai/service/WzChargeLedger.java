package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.*;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

final class WzChargeLedger {
    private final Path path;

    WzChargeLedger(Path path) {
        this.path = path;
    }

    synchronized void record(UUID player, JsonObject receipt) {
        JsonObject entries = read(path);
        prune(entries);
        String day = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toString();
        JsonObject daily = object(entries, day);
        String id = player.toString();
        JsonObject usage = object(daily, id);
        usage.addProperty("reports", number(usage, "reports") + 1);
        for (String metric : List.of("hit", "miss", "output", "reasoning", "nanoYuan")) {
            if (receipt.has(metric))
                usage.addProperty(metric, number(usage, metric) + number(receipt, metric));
        }
        if (!receipt.has("nanoYuan")) usage.addProperty("unpriced", number(usage, "unpriced") + 1);
        if (!receipt.has("reasoning"))
            usage.addProperty("reasoningMissing", number(usage, "reasoningMissing") + 1);
        daily.add(id, usage);
        entries.add(day, daily);
        write(path, entries);
    }

    synchronized JsonObject account(UUID player) {
        JsonObject entries = read(path);
        prune(entries);
        JsonObject result = new JsonObject();
        entries.entrySet()
                .forEach(
                        entry ->
                                object(entry.getValue().getAsJsonObject(), player.toString())
                                        .entrySet()
                                        .forEach(
                                                metric ->
                                                        result.addProperty(
                                                                metric.getKey(),
                                                                number(result, metric.getKey())
                                                                        + metric.getValue()
                                                                                .getAsLong())));
        return result;
    }

    synchronized void sweep() {
        JsonObject entries = read(path);
        int size = entries.size();
        prune(entries);
        if (entries.size() != size) write(path, entries);
    }

    private static void prune(JsonObject entries) {
        String cutoff =
                Instant.now()
                        .minus(29, ChronoUnit.DAYS)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .toString();
        entries.keySet().removeIf(day -> day.compareTo(cutoff) < 0);
    }
}

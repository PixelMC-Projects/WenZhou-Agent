package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.number;
import static com.pixelmc.wenzhouai.service.WzData.read;
import static com.pixelmc.wenzhouai.service.WzData.string;
import static com.pixelmc.wenzhouai.service.WzData.write;

import com.google.gson.JsonObject;
import com.pixelmc.wenzhouai.manager.WzDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Durable handoff of priced provider receipts to Manager. Replays use the original receipt ID. */
final class WzQuotaLedger {
    private final Path pending;
    private final Path legacy;
    private final WzDirectory directory;
    // Retained until either the local file or the database acknowledges ownership.
    private final Map<UUID, Map<String, JsonObject>> unwritten = new HashMap<>();

    WzQuotaLedger(Path root, WzDirectory directory) {
        this.directory = directory;
        this.pending = root.resolve("quota-pending");
        this.legacy = root.resolve("quota-legacy.json");
        // Snapshot exactly once before the old rolling ledger starts accepting new receipts.
        if (!Files.exists(legacy)) {
            JsonObject totals = new JsonObject();
            JsonObject previous = read(root.resolve("usage.json"));
            for (var day : previous.entrySet()) {
                for (var entry : day.getValue().getAsJsonObject().entrySet()) {
                    UUID owner = UUID.fromString(entry.getKey());
                    long cost = number(entry.getValue().getAsJsonObject(), "nanoYuan");
                    if (cost < 0) throw new IllegalStateException("旧问舟账本金额无效");
                    if (cost != 0) totals.addProperty(owner.toString(), Math.addExact(number(totals, owner.toString()), cost));
                }
            }
            JsonObject snapshot = new JsonObject();
            snapshot.addProperty("source", UUID.randomUUID().toString());
            snapshot.add("players", totals);
            write(legacy, snapshot);
        }
    }

    JsonObject record(UUID player, long admittedAt, JsonObject receipt) {
        if (WzThreadUsage.CHATGPT_PLAN.equals(string(receipt, "billing")) || !receipt.has("nanoYuan")) return null;
        String id = UUID.fromString(receipt.get("id").getAsString()).toString();
        JsonObject charge = new JsonObject();
        charge.addProperty("requestAt", admittedAt);
        charge.add("amount", receipt.get("nanoYuan"));
        return submit(player, id, charge);
    }

    private JsonObject submit(UUID player, String id, JsonObject charge) {
        Path file = pending.resolve(player.toString()).resolve(id + ".json");
        synchronized (this) {
            var playerQueue = unwritten.computeIfAbsent(player, ignored -> new HashMap<>());
            var prior = playerQueue.putIfAbsent(id, charge);
            if (prior != null && !prior.equals(charge))
                throw new IllegalStateException("待入账回执与原记录不一致");
            if (Files.exists(file) && !read(file).equals(charge))
                throw new IllegalStateException("磁盘回执与新回执不一致");
            try {
                write(file, charge);
                acknowledgeMemory(player, id);
            } catch (IllegalStateException unavailable) {
                System.getLogger(WzQuotaLedger.class.getName()).log(System.Logger.Level.WARNING,
                        "WenZhou local quota queue is unavailable; attempting direct database delivery");
            }
        }
        return deliver(player, file, charge);
    }

    private synchronized void acknowledgeMemory(UUID player, String id) {
        var playerQueue = unwritten.get(player);
        if (playerQueue == null) return;
        playerQueue.remove(id);
        if (playerQueue.isEmpty()) unwritten.remove(player);
    }

    void flush(UUID player) {
        Map<String, JsonObject> inMemory;
        synchronized (this) {
            inMemory = Map.copyOf(unwritten.getOrDefault(player, Map.of()));
        }
        inMemory.forEach((id, charge) -> submit(player, id, charge));
        String owner = player.toString();
        long historical;
        String source;
        synchronized (this) {
            JsonObject snapshot = read(legacy);
            historical = number(snapshot.getAsJsonObject("players"), owner);
            source = snapshot.get("source").getAsString();
        }
        try {
            if (historical > 0) {
                directory.importQuota(player, source, historical).get(12, TimeUnit.SECONDS);
                synchronized (this) {
                    var remaining = read(legacy);
                    if (remaining.getAsJsonObject("players").remove(owner) != null) write(legacy, remaining);
                }
            }
            Path folder = pending.resolve(owner);
            if (!Files.isDirectory(folder)) return;
            try (var files = Files.newDirectoryStream(folder, "*.json")) {
                for (Path file : files) {
                    JsonObject charge;
                    synchronized (this) {
                        if (!Files.exists(file)) continue;
                        charge = read(file);
                    }
                    deliver(player, file, charge);
                }
            } catch (java.nio.file.NoSuchFileException delivered) {
                // A concurrent delivery removed the now-empty player folder.
            }
        } catch (Exception e) {
            throw new IllegalStateException("问舟额度尚有回执未入账", e);
        }
    }

    void retry() {
        Set<UUID> players = new HashSet<>();
        synchronized (this) {
            players.addAll(unwritten.keySet());
            for (String id : read(legacy).getAsJsonObject("players").keySet()) players.add(UUID.fromString(id));
        }
        try {
            if (Files.isDirectory(pending)) {
                try (var folders = Files.newDirectoryStream(pending)) {
                    for (Path path : folders)
                        if (Files.isDirectory(path)) players.add(UUID.fromString(path.getFileName().toString()));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("问舟待入账目录读取失败", e);
        }
        RuntimeException failed = null;
        for (UUID player : players) {
            try {
                flush(player);
            } catch (RuntimeException e) {
                failed = e;
            }
        }
        if (failed != null) throw failed;
    }

    private JsonObject deliver(UUID player, Path path, JsonObject charge) {
        String filename = path.getFileName().toString();
        String id = filename.substring(0, filename.length() - ".json".length());
        try {
            JsonObject saved = directory.chargeQuota(player, id, number(charge, "requestAt"), number(charge, "amount"))
                    .get(12, TimeUnit.SECONDS);
            synchronized (this) {
                acknowledgeMemory(player, id);
                try {
                    Files.deleteIfExists(path);
                    Files.deleteIfExists(path.getParent());
                } catch (IOException ignored) {
                    // The database has committed. Remaining files can be replayed idempotently.
                }
            }
            return saved;
        } catch (Exception e) {
            throw new IllegalStateException("问舟额度记账暂不可用，回执已保留", e);
        }
    }
}


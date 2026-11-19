package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;

final class WzHistory {
    private static final long RETENTION = Duration.ofDays(30).toMillis();
    private final Path directory;
    private final boolean readOnly;

    private final Map<UUID, SummaryIndex> indices = new HashMap<>();
    private UsageSnapshot lastUsage;

    record SummaryIndex(JsonArray all, JsonArray research, long since, long expiresAt,
                        Object fileVersion) {}
    private record UsageSnapshot(UUID player, String session, long since, long expiresAt, JsonArray turns) {}

    WzHistory(Path directory) {
        this(directory, false);
    }

    WzHistory(Path directory, boolean readOnly) {
        this.directory = directory;
        this.readOnly = readOnly;
    }

    private Object fileVersion(UUID player) {
        try {
            var attributes = Files.readAttributes(directory.resolve(player + ".json"), BasicFileAttributes.class);
            return List.of(java.util.Objects.toString(attributes.fileKey()), attributes.lastModifiedTime(), attributes.size());
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IOException failure) {
            throw new IllegalStateException("问舟历史不可用", failure);
        }
    }

    synchronized JsonObject load(UUID player) {
        Path path = directory.resolve(player + ".json");
        JsonObject all = read(path);
        boolean changed = false;
        long cutoff = System.currentTimeMillis() - RETENTION;
        for (String id : List.copyOf(all.keySet())) {
            JsonObject thread = all.getAsJsonObject(id);
            JsonArray previous = thread.getAsJsonArray("messages");
            if (previous.isEmpty()
                    && flag(thread, "removed")
                    && number(thread, "removedAt") <= cutoff) {
                all.remove(id);
                changed = true;
                continue;
            }
            if (!hasExpiredTurn(previous, cutoff)) continue;
            JsonArray retained = new JsonArray();
            for (var turn : previous)
                if (number(turn.getAsJsonObject(), "createdAt") > cutoff) retained.add(turn);
            thread.add("messages", retained);
            changed = true;
            if (retained.isEmpty()) {
                if (flag(thread, "removed") && number(thread, "removedAt") > cutoff) {
                    thread.remove("title");
                    thread.remove("titleSince");
                } else all.remove(id);
            } else if (number(thread, "titleSince") <= cutoff) {
                thread.remove("titleSince");
                thread.addProperty(
                        "title", title(string(retained.get(0).getAsJsonObject(), "question")));
            }
        }
        if (changed && !readOnly) save(player, all);
        return all;
    }

    private static boolean hasExpiredTurn(JsonArray turns, long cutoff) {
        for (var turn : turns)
            if (number(turn.getAsJsonObject(), "createdAt") <= cutoff) return true;
        return false;
    }

    synchronized void save(UUID player, JsonObject all) {
        if (readOnly) throw new IllegalStateException("问舟历史为只读");
        indices.remove(player);
        if (lastUsage != null && lastUsage.player.equals(player)) lastUsage = null;
        Path path = directory.resolve(player + ".json");
        if (all.size() > 0) {
            write(path, all);
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("问舟历史清理失败", exception);
        }
    }

    static boolean removeForPlayer(JsonObject all, String id, boolean running, long removedAt) {
        JsonObject thread = object(all, id);
        if (thread.size() == 0 && !running) return false;
        if (flag(thread, "removed")) return true;
        if (thread.size() == 0) {
            thread.addProperty("id", id);
            thread.add("messages", new JsonArray());
            all.add(id, thread);
        }
        thread.addProperty("removed", true);
        thread.addProperty("removedAt", removedAt);
        return true;
    }

    static boolean appendRemoved(JsonObject all, String id, JsonObject turn) {
        JsonObject thread = object(all, id);
        if (!flag(thread, "removed")) return false;
        JsonArray turns = thread.getAsJsonArray("messages");
        if (turns.isEmpty()) thread.addProperty("title", title(string(turn, "question")));
        turns.add(turn);
        thread.addProperty("updatedAt", number(turn, "createdAt"));
        return true;
    }

    List<UUID> players() {
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.matches("[0-9a-f-]{36}\\.json"))
                    .map(name -> UUID.fromString(name.substring(0, 36)))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("问舟历史不可用", exception);
        }
    }

    synchronized SummaryIndex index(UUID player, long researchSince) {
        SummaryIndex cached = indices.get(player);
        long now = System.currentTimeMillis();
        Object version = readOnly ? fileVersion(player) : null;
        if (cached != null && now < cached.expiresAt
                && java.util.Objects.equals(version, cached.fileVersion)
                && (researchSince < 0 || researchSince == cached.since))
            return cached;
        JsonObject all = load(player);
        SummaryIndex index = new SummaryIndex(summaries(player, all, true, 0),
                researchSince > 0 ? summaries(player, all, true, researchSince) : new JsonArray(),
                researchSince, expiresAt(all), version);
        if (all.size() > 0) indices.put(player, index);
        else indices.remove(player);
        return index;
    }

    synchronized JsonArray savedUsage(UUID player, String session, long since) {
        UsageSnapshot cached = lastUsage;
        if (cached != null && cached.player.equals(player) && cached.session.equals(session)
                && cached.since == since && System.currentTimeMillis() < cached.expiresAt) return cached.turns;
        JsonObject all = load(player);
        JsonArray turns = usageTurns(object(all, session), since);
        lastUsage = new UsageSnapshot(player, session, since, expiresAt(all), turns);
        return turns;
    }

    private static long expiresAt(JsonObject all) {
        long earliest = Long.MAX_VALUE;
        for (var value : all.entrySet()) {
            JsonObject thread = value.getValue().getAsJsonObject();
            for (var turn : thread.getAsJsonArray("messages"))
                earliest = Math.min(earliest, number(turn.getAsJsonObject(), "createdAt"));
        }
        return earliest < Long.MAX_VALUE - RETENTION ? earliest + RETENTION : Long.MAX_VALUE;
    }

    static JsonArray summaries(
            UUID player, JsonObject all, boolean includeRemoved, long researchSince) {
        JsonArray result = new JsonArray();
        all.entrySet().stream()
                .map(entry -> entry.getValue().getAsJsonObject())
                .sorted(Comparator.comparingLong(thread -> -number(thread, "updatedAt")))
                .filter(thread -> includeRemoved || !flag(thread, "removed"))
                .forEach(
                        thread -> {
                            JsonObject first = null, latest = null;
                            int count = 0;
                            for (var entry : thread.getAsJsonArray("messages")) {
                                JsonObject turn = entry.getAsJsonObject();
                                if (number(turn, "createdAt") < researchSince) continue;
                                if (first == null) first = turn;
                                latest = turn;
                                count++;
                            }
                            if (first == null) return;
                            JsonObject row = new JsonObject();
                            row.addProperty("id", string(thread, "id"));
                            row.addProperty("playerId", player.toString());
                            row.addProperty("title", visibleTitle(thread, first, researchSince));
                            long updatedAt = number(latest, "createdAt");
                            row.addProperty("updated", updatedAt);
                            row.addProperty("updatedAt", updatedAt);
                            row.addProperty("count", count);
                            row.addProperty(
                                    "pendingTopic",
                                    !string(latest, "topicToken").isEmpty()
                                            && !latest.has("reply"));
                            row.addProperty("research", researchSince > 0);
                            result.add(row);
                        });
        return result;
    }

    static JsonArray turns(JsonObject thread, long since) {
        JsonArray result = new JsonArray();
        if (thread.has("messages"))
            thread.getAsJsonArray("messages")
                    .forEach(
                            turn -> {
                                if (number(turn.getAsJsonObject(), "createdAt") >= since)
                                    result.add(turn.deepCopy());
                            });
        return result;
    }

    static JsonArray usageTurns(JsonObject thread, long since) {
        JsonArray result = new JsonArray();
        JsonArray messages = thread.getAsJsonArray("messages");
        if (messages == null) return result;
        for (var entry : messages) {
            JsonObject turn = entry.getAsJsonObject();
            if (number(turn, "createdAt") < since) continue;
            JsonObject usageOnly = new JsonObject();
            if (turn.has("usageReceipts"))
                usageOnly.add("usageReceipts", turn.get("usageReceipts"));
            usageOnly.addProperty("usageMissing", flag(turn, "usageMissing"));
            result.add(usageOnly);
        }
        return result;
    }

    static boolean hasGeneratedTitle(JsonObject thread) {
        return number(thread, "titleSince") > 0 && !string(thread, "title").isBlank();
    }

    static void setGeneratedTitle(JsonObject thread, String generated, long since) {
        if (generated.isEmpty() || since <= System.currentTimeMillis() - RETENTION || hasGeneratedTitle(thread)) return;
        thread.addProperty("title", title(generated));
        thread.addProperty("titleSince", since);
    }

    static String visibleTitle(JsonObject thread, JsonObject firstVisible, long since) {
        if (hasGeneratedTitle(thread) && number(thread, "titleSince") >= since)
            return string(thread, "title");
        return title(string(firstVisible, "question"));
    }

    static String title(String text) {
        int end = text.offsetByCodePoints(0, Math.min(text.codePointCount(0, text.length()), 44));
        return text.substring(0, end);
    }
}

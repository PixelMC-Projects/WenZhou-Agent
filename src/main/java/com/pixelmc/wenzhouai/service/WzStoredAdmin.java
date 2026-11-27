package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pixelmc.wenzhouai.manager.WzAdminBackend;
import com.pixelmc.wenzhouai.manager.WzDirectory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** The Manager service reads saved data without loading game classes or decrypting credentials. */
public final class WzStoredAdmin implements WzAdminBackend.Data {
    private static final List<String> ADMIN_TURN_FIELDS =
            List.of(
                    "id",
                    "createdAt",
                    "question",
                    "reply",
                    "topicPrompt",
                    "stopped",
                    "failure",
                    "events",
                    "usageReceipts",
                    "usageMissing");

    private final Path directory;
    private final WzHistory history;
    private final Consumer<UUID> notify;
    private final Consumer<UUID> quotaNotify;

    public WzStoredAdmin(Path directory, WzDirectory players, Consumer<UUID> notify) {
        this(directory, players, notify, player -> {});
    }

    public WzStoredAdmin(
            Path directory,
            WzDirectory players,
            Consumer<UUID> notify,
            Consumer<UUID> quotaNotify) {
        this.quotaNotify = quotaNotify;
        this.directory = directory;
        this.notify = notify;
        history = new WzHistory(directory.resolve("conversations"), true);
    }

    @Override
    public JsonObject settings(String operation, JsonObject input) {
        if (!"status".equals(operation)) throw new IllegalStateException("此设置需要游戏服连接");
        return WzSettings.savedAdmin(directory, operation);
    }

    @Override
    public int sessionCount(UUID player) {
        requireStorage();
        return history.index(player).all().size();
    }

    @Override
    public JsonArray sessions(UUID player) {
        requireStorage();
        List<UUID> owners = player == null ? history.players() : List.of(player);
        JsonArray all = new JsonArray();
        for (UUID owner : owners) {
            all.addAll(history.index(owner).all());
        }
        return all;
    }

    @Override
    public JsonObject thread(UUID player, String id) {
        requireStorage();
        return thread(player, id, object(history.load(player), id), null, 0, null, false);
    }

    @Override
    public JsonObject usage(UUID player, String id) {
        requireStorage();
        return usage(history.savedThread(player, id), null, false);
    }

    @Override
    public void accessChanged(UUID player) {
        notify.accept(player);
    }

    @Override
    public void quotaChanged(UUID player) {
        quotaNotify.accept(player);
    }

    private void requireStorage() {
        if (!java.nio.file.Files.isDirectory(directory))
            throw new IllegalStateException("问舟存储目录不可用");
    }

    record SavedThread(JsonArray receipts, int count, String bodyVersion) {}

    static SavedThread snapshot(JsonObject saved) {
        JsonArray turns = visibleTurns(saved);
        return new SavedThread(
                WzHistory.usageTurns(saved, 0), turns.size(), bodyVersion(saved, turns));
    }

    static JsonObject usage(SavedThread saved, JsonArray runningReceipts, boolean runningMissing) {
        if (saved.count() == 0 && runningReceipts == null)
            throw new IllegalArgumentException("问舟会话不可用");
        JsonObject result = WzThreadUsage.merge(saved.receipts(), runningReceipts, runningMissing);
        result.addProperty("bodyVersion", saved.bodyVersion());
        return result;
    }

    private static String bodyVersion(JsonObject saved, JsonArray turns) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (!turns.isEmpty()) {
                String title = WzHistory.visibleTitle(saved, turns.get(0).getAsJsonObject(), 0);
                digest.update(title.getBytes(StandardCharsets.UTF_8));
            }
            digest.update((byte) 0);
            for (var turn : turns) digest.update(turn.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static JsonArray visibleTurns(JsonObject saved) {
        JsonArray turns = new JsonArray();
        JsonArray messages = saved.getAsJsonArray("messages");
        if (messages != null) {
            for (var entry : messages) {
                JsonObject original = entry.getAsJsonObject();
                JsonObject turn = new JsonObject();
                for (String field : ADMIN_TURN_FIELDS)
                    if (original.has(field)) turn.add(field, original.get(field));
                turns.add(turn);
            }
        }
        return turns;
    }

    static JsonObject thread(
            UUID player,
            String id,
            JsonObject saved,
            String runningQuestion,
            long runningAt,
            JsonArray runningReceipts,
            boolean runningMissing) {
        JsonArray turns = visibleTurns(saved);
        if (turns.isEmpty() && runningQuestion == null)
            throw new IllegalArgumentException("问舟会话不可用");
        JsonObject usage = WzThreadUsage.merge(turns, runningReceipts, runningMissing);
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("playerId", player.toString());
        boolean savedTurns = !turns.isEmpty();
        String title =
                savedTurns
                        ? WzHistory.visibleTitle(saved, turns.get(0).getAsJsonObject(), 0)
                        : WzHistory.title(runningQuestion);
        long updated =
                runningQuestion != null
                        ? runningAt
                        : number(turns.get(turns.size() - 1).getAsJsonObject(), "createdAt");
        result.addProperty("title", title);
        result.addProperty("updated", updated);
        result.addProperty("bodyVersion", bodyVersion(saved, turns));
        turns.forEach(turn -> turn.getAsJsonObject().remove("usageMissing"));
        result.add("turns", turns);
        result.addProperty("count", turns.size());
        result.add("running", usage.get("running"));
        result.add("usageReceipts", usage.get("receipts"));
        result.add("usageMissing", usage.get("missing"));
        return result;
    }
}

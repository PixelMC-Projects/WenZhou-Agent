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
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** The Manager service reads saved data without loading game classes or decrypting credentials. */
public final class WzStoredAdmin implements WzAdminBackend.Data {
    private static final List<String> ADMIN_TURN_FIELDS = List.of(
            "id", "createdAt", "question", "reply", "topicPrompt", "stopped", "failure", "events", "usageReceipts", "usageMissing");

    private final Path directory;
    private final WzHistory history;
    private final WzDirectory players;
    private final Consumer<UUID> notify;
    private final Consumer<UUID> quotaNotify;

    public WzStoredAdmin(Path directory, WzDirectory players, Consumer<UUID> notify) {
        this(directory, players, notify, player -> {});
    }

    public WzStoredAdmin(Path directory, WzDirectory players, Consumer<UUID> notify, Consumer<UUID> quotaNotify) {
        this.quotaNotify = quotaNotify;
        this.directory = directory;
        this.players = players;
        this.notify = notify;
        history = new WzHistory(directory.resolve("conversations"), true);
    }

    @Override
    public JsonObject settings(String operation, JsonObject input) {
        if (!Set.of("status", "policy.summary").contains(operation))
            throw new IllegalStateException("此设置需要游戏服连接");
        return WzSettings.savedAdmin(directory, operation);
    }

    @Override
    public int sessionCount(UUID player) {
        requireStorage();
        return history.index(player, -1).all().size();
    }

    @Override
    public WzAdminBackend.SessionLists sessions(UUID player, boolean research) throws Exception {
        requireStorage();
        List<UUID> owners = player == null ? history.players() : List.of(player);
        JsonObject access = research
                ? players.accessRows(owners.stream().map(UUID::toString).toList(), true)
                : new JsonObject();
        long version = research ? policyVersion() : 0;
        JsonArray all = new JsonArray();
        JsonArray eligible = new JsonArray();
        for (UUID owner : owners) {
            long since = research ? researchSinceFields(object(access, owner.toString()), version) : -1;
            var index = history.index(owner, since);
            all.addAll(index.all());
            if (since > 0) eligible.addAll(index.research());
        }
        return new WzAdminBackend.SessionLists(all, eligible);
    }

    @Override
    public JsonObject thread(UUID player, String id, boolean research) {
        requireStorage();
        long since = since(player, research);
        return thread(player, id, object(history.load(player), id), since, null, 0, null, false);
    }

    @Override
    public JsonObject usage(UUID player, String id, boolean research) {
        requireStorage();
        long since = since(player, research);
        return usage(history.savedThread(player, id, since), since, null, false);
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

    private long policyVersion() {
        return number(object(WzSettings.savedAdmin(directory, "policy.summary"), "policy"), "version");
    }

    private long since(UUID player, boolean research) {
        if (!research) return 0;
        JsonObject fields;
        try {
            fields = object(players.accessRows(List.of(player.toString()), true), player.toString());
        } catch (Exception unavailable) {
            throw new IllegalStateException("无法确认问答优化授权", unavailable);
        }
        long since = researchSinceFields(fields, policyVersion());
        if (since < 0) throw new SecurityException("当前会话未授权用于问答优化");
        return since;
    }

    static long researchSinceFields(JsonObject fields, long version) {
        if (!flag(object(fields, WzDirectory.GRANT), "value")) return -1;
        return researchSince(object(object(fields, WzDirectory.RECEIPT), "value"), version);
    }

    static long researchSince(JsonObject receipt, long version) {
        long since = number(receipt, "researchSince");
        return version > 0 && number(receipt, "version") == version
                && flag(receipt, "research") && since > 0 ? since : -1;
    }

    record SavedThread(JsonArray receipts, int count, String bodyVersion) {}

    static SavedThread snapshot(JsonObject saved, long since) {
        JsonArray turns = visibleTurns(saved, since);
        return new SavedThread(WzHistory.usageTurns(saved, since), turns.size(), bodyVersion(saved, turns, since));
    }

    static JsonObject usage(SavedThread saved, long since, JsonArray runningReceipts, boolean runningMissing) {
        if (saved.count() == 0 && runningReceipts == null)
            throw new IllegalArgumentException("问舟会话不可用");
        JsonObject result = WzThreadUsage.merge(saved.receipts(), runningReceipts, runningMissing);
        result.addProperty("bodyVersion", saved.bodyVersion());
        result.addProperty("researchSince", since);
        return result;
    }

    private static String bodyVersion(JsonObject saved, JsonArray turns, long since) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (!turns.isEmpty()) {
                String title = WzHistory.visibleTitle(saved, turns.get(0).getAsJsonObject(), since);
                digest.update(title.getBytes(StandardCharsets.UTF_8));
            }
            digest.update((byte) 0);
            for (var turn : turns) digest.update(turn.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static JsonArray visibleTurns(JsonObject saved, long since) {
        JsonArray turns = new JsonArray();
        JsonArray messages = saved.getAsJsonArray("messages");
        if (messages != null) {
            for (var entry : messages) {
                JsonObject original = entry.getAsJsonObject();
                if (number(original, "createdAt") < since) continue;
                JsonObject turn = new JsonObject();
                for (String field : ADMIN_TURN_FIELDS)
                    if (original.has(field)) turn.add(field, original.get(field));
                turns.add(turn);
            }
        }
        return turns;
    }

    static JsonObject thread(UUID player, String id, JsonObject saved, long since,
                             String runningQuestion, long runningAt, JsonArray runningReceipts,
                             boolean runningMissing) {
        JsonArray turns = visibleTurns(saved, since);
        if (turns.isEmpty() && runningQuestion == null)
            throw new IllegalArgumentException("问舟会话不可用");
        JsonObject usage = WzThreadUsage.merge(turns, runningReceipts, runningMissing);
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("playerId", player.toString());
        boolean savedTurns = !turns.isEmpty();
        String title = savedTurns
                ? WzHistory.visibleTitle(saved, turns.get(0).getAsJsonObject(), since)
                : WzHistory.title(runningQuestion);
        long updated = runningQuestion != null ? runningAt : number(turns.get(turns.size() - 1).getAsJsonObject(), "createdAt");
        result.addProperty("title", title);
        result.addProperty("updated", updated);
        result.addProperty("bodyVersion", bodyVersion(saved, turns, since));
        result.addProperty("researchSince", since);
        turns.forEach(turn -> turn.getAsJsonObject().remove("usageMissing"));
        result.add("turns", turns);
        result.addProperty("count", turns.size());
        result.add("running", usage.get("running"));
        result.add("usageReceipts", usage.get("receipts"));
        result.add("usageMissing", usage.get("missing"));
        return result;
    }
}

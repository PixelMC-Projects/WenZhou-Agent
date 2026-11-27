package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.*;
import com.pixelmc.wenzhouai.manager.WzDirectory;
import com.google.gson.JsonElement;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Published content is immutable. Player-directory fields contain only preferences and inbox receipts. */
final class WzPlayerShares {
    private static final String INBOX = "received_shares", PREFS = "reminders";
    private static final int PAGE = 24;
    private final Path root;
    private final WzDirectory directory;
    private final ConcurrentHashMap<UUID, JsonObject> drafts = new ConcurrentHashMap<>();

    WzPlayerShares(Path root, WzDirectory directory) {
        this.root = root;
        this.directory = directory;
    }

    void discard(UUID owner) { drafts.remove(owner); }

    private JsonObject field(UUID player, String key) throws Exception {
        if (directory != null) return directory.read(player, key).get(5, TimeUnit.SECONDS);
        var row = new JsonObject();
        row.add("value", read(root.resolve(key).resolve(player + ".json")));
        return row;
    }

    private JsonObject update(UUID player, String key, Consumer<JsonObject> change) throws Exception {
        if (directory == null) {
            synchronized (this) {
                var path = root.resolve(key).resolve(player + ".json");
                var old = read(path);
                var value = old.deepCopy();
                change.accept(value);
                if (!old.equals(value)) write(path, value);
                return value;
            }
        }
        var row = field(player, key);
        for (int attempt = 0; ; attempt++) {
            var value = object(row, "value").deepCopy();
            change.accept(value);
            if (value.equals(row.get("value"))) return value;
            long revision = number(row, "revision");
            try {
                directory.savePersonal(player, key, value, revision).get(5, TimeUnit.SECONDS);
                return value;
            } catch (Exception failure) {
                if (attempt >= 2) throw failure;
                row = field(player, key);
                if (number(row, "revision") == revision) throw failure;
            }
        }
    }

    JsonObject preferences(UUID player) throws Exception { return object(field(player, PREFS), "value"); }

    JsonObject reminder(UUID player, JsonObject body) throws Exception {
        String kind = string(body, "kind");
        if ((!kind.equals("completion") && !kind.equals("unread")) || !body.has("enabled")
                || !body.get("enabled").isJsonPrimitive() || !body.getAsJsonPrimitive("enabled").isBoolean())
            return error("提醒设置无效");
        return update(player, PREFS, value -> value.addProperty(kind, flag(body, "enabled")));
    }

    JsonObject preview(UUID player, String sender, JsonObject thread) {
        if (flag(thread, "removed") || !thread.has("messages")) return error("未找到这段对话");
        var turns = new JsonArray();
        var models = new LinkedHashSet<String>();
        for (var element : thread.getAsJsonArray("messages")) {
            var turn = element.getAsJsonObject();
            if (!turn.has("reply") || turn.has("itemChoice") || turn.has("topicPrompt") || turn.has("failure") || flag(turn, "stopped")) continue;
            var copy = new JsonObject();
            copy.addProperty("question", string(turn, "question"));
            copy.add("reply", object(turn, "reply").deepCopy());
            String model = string(turn, "modelSelection").split("/", 2)[0];
            if (model.isBlank() || model.equals("deepseek")) model = "DeepSeek";
            else if (model.equals("terra-api")) model = "GPT-6.1 Terra";
            copy.addProperty("model", model);
            models.add(model);
            turns.add(copy);
        }
        if (turns.isEmpty()) return error("这段对话还没有可分享的回答");
        var snapshot = new JsonObject();
        snapshot.addProperty("id", UUID.randomUUID().toString());
        snapshot.addProperty("title", string(thread, "title"));
        snapshot.addProperty("sender", sender);
        snapshot.addProperty("models", String.join(" / ", models));
        snapshot.add("messages", turns);
        if (snapshot.toString().getBytes(StandardCharsets.UTF_8).length > 890_000)
            return error("这段对话超过快照传输上限");
        drafts.put(player, snapshot);
        return snapshot;
    }

    private Path snapshotPath(String id) {
        var uuid = UUID.fromString(id);
        if (!uuid.toString().equals(id)) throw new IllegalArgumentException("分享编号无效");
        return root.resolve("published").resolve(id + ".json");
    }

    JsonObject publish(UUID owner, String id) {
        var draft = drafts.get(owner);
        if (draft == null || !string(draft, "id").equals(id) || !drafts.remove(owner, draft))
            return error("这份预览已失效，请重新预览");
        var meta = new JsonObject();
        for (String key : new String[]{"id","title","sender","models"}) meta.add(key, draft.get(key));
        write(root.resolve("summaries").resolve(id + ".json"), meta);
        write(snapshotPath(id), draft);
        var result = new JsonObject();
        result.addProperty("id", id);
        result.add("title", draft.get("title"));
        return result;
    }

    JsonObject open(UUID player, String id) throws Exception {
        var snapshot = read(snapshotPath(id));
        if (!id.equals(string(snapshot, "id"))) return error("这条分享无法打开");
        try {
            update(player, INBOX, inbox -> {
                if (inbox.has(id)) return;
                var entry = new JsonObject();
                entry.addProperty("at", System.currentTimeMillis());
                inbox.add(id, entry);
            });
        } catch (Exception unavailable) {
            snapshot.addProperty("warning", "快照已打开，这次未能保存接收记录。");
        }
        return snapshot;
    }

    JsonObject list(UUID player, JsonObject body) throws Exception {
        var inbox = object(field(player, INBOX), "value");
        int last = Math.max(0, (inbox.size() - 1) / PAGE);
        int page = (int)Math.min(last, Math.max(0, number(body, "page")));
        var rows = new JsonArray();
        inbox.entrySet().stream().sorted(Comparator
                .<Map.Entry<String, JsonElement>, Boolean>comparing(e -> flag(e.getValue().getAsJsonObject(), "pinned")).reversed()
                .thenComparing(Comparator.comparingLong((Map.Entry<String, JsonElement> e) -> number(e.getValue().getAsJsonObject(), "at")).reversed())
                .thenComparing(Map.Entry::getKey))
                .skip((long) page * PAGE).limit(PAGE).forEach(e -> {
                    var saved = read(root.resolve("summaries").resolve(UUID.fromString(e.getKey()) + ".json"));
                    var row = e.getValue().getAsJsonObject().deepCopy();
                    for (String key : new String[]{"title","models","sender"}) row.addProperty(key, string(saved,key));
                    row.addProperty("id", e.getKey());
                    rows.add(row);
                });
        var result = new JsonObject();
        result.add("rows", rows);
        result.addProperty("count", inbox.size());
        result.addProperty("page", page);
        result.addProperty("last", last);
        return result;
    }

    JsonObject manage(UUID player, String action, JsonObject body) throws Exception {
        var id = string(body, "id");
        update(player, INBOX, inbox -> {
            if (action.equals("share.delete")) inbox.remove(id);
            else if (inbox.has(id)) object(inbox,id).addProperty("pinned", flag(body,"pinned"));
        });
        return list(player, body);
    }
}

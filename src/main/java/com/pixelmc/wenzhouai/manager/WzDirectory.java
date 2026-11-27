package com.pixelmc.wenzhouai.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

/** The Manager-backed source of truth for cross-world player access and agreement receipts. */
public final class WzDirectory {
    public static final String ID = "wenzhouai";
    public static final String STORAGE_MANIFEST = """
{
  "version":6,
  "name":"WenzhouAI",
  "label":"问舟AI",
  "scope":"global",
  "fields":[
    {"id":"access","type":"boolean","scope":"global","default":false,"maxBytes":32},
    {"id":"agreement","type":"object","scope":"global","default":{},"maxBytes":4096},
    {"id":"reminders","type":"object","scope":"global","default":{},"maxBytes":512},
    {"id":"received_shares","type":"object","scope":"global","default":{},"maxBytes":65536},
    {"id":"intro_seen","type":"boolean","scope":"global","default":false,"maxBytes":32},
    {"id":"model_preferences","type":"object","scope":"global","default":{},"maxBytes":8192}
  ],
  "tables":["wz_quota_config","wz_quota_accounts","wz_quota_receipts","wz_terra_config","wz_terra_grants","wz_terra_balances","wz_terra_receipts"],
  "playerTables":[
    {"table":"wz_quota_accounts","column":"uuid"},
    {"table":"wz_quota_receipts","column":"uuid"},
    {"table":"wz_terra_grants","column":"uuid"}
  ]
}
""";
    public static final String GRANT = "access";
    public static final String RECEIPT = "agreement";
    public static final String INTRO_SEEN = "intro_seen";

    public static final String MODEL_PREFERENCES = "model_preferences";

    public CompletableFuture<JsonObject> savePersonal(UUID player, String field, JsonObject value, long revision) {
        var input = storageRequest("storage.set", player, field);
        input.add("value", value);
        input.addProperty("expectedRevision", revision);
        return request(input);
    }

    public CompletableFuture<JsonObject> saveModelPreferences(UUID owner, JsonObject value, long revision) {
        var input = storageRequest("storage.set", owner, MODEL_PREFERENCES);
        input.add("value", value.deepCopy());
        input.addProperty("expectedRevision", revision);
        return request(input);
    }

    private final java.util.function.Function<JsonObject, CompletableFuture<JsonObject>> transport;

    public WzDirectory(java.util.function.Function<JsonObject, CompletableFuture<JsonObject>> transport) {
        this.transport = transport;
    }


    public CompletableFuture<JsonObject> quota(UUID player) {
        return request(quotaRequest("quota.view", player));
    }

    public CompletableFuture<JsonObject> admitQuota(UUID player, long receivedAt) {
        JsonObject in = quotaRequest("quota.open", player);
        in.addProperty("receivedAt", receivedAt);
        return request(in);
    }

    public CompletableFuture<JsonObject> chargeQuota(UUID player, String receiptId, long requestAt, long amount) {
        JsonObject in = quotaRequest("quota.charge", player);
        in.addProperty("receiptId", receiptId);
        in.addProperty("requestAt", requestAt);
        in.addProperty("amount", amount);
        return request(in);
    }

    public CompletableFuture<JsonObject> importQuota(UUID player, String source, long historicalAmount) {
        JsonObject in = quotaRequest("quota.legacy", player);
        in.addProperty("source", source);
        in.addProperty("amount", historicalAmount);
        return request(in);
    }

    public CompletableFuture<JsonObject> sweepQuota() {
        return request(quotaRequest("quota.sweep", null)).thenCompose(result ->
                result.get("more").getAsBoolean() ? sweepQuota() : CompletableFuture.completedFuture(result));
    }

    CompletableFuture<JsonObject> quotaConfig() {
        return request(quotaRequest("quota.config", null));
    }

    CompletableFuture<JsonObject> quotaAdmin(String op, JsonObject input, UUID player) {
        JsonObject in = input.deepCopy();
        in.addProperty("op", op);
        if (player != null) in.addProperty("player", player.toString());
        return request(in);
    }

    JsonObject quotaRows(Collection<String> players) throws Exception {
        var result = new JsonObject();
        var ids = List.copyOf(players);
        for (int from = 0; from < ids.size(); from += 64) {
            var batch = new JsonArray();
            ids.subList(from, Math.min(ids.size(), from + 64)).forEach(batch::add);
            var in = quotaRequest("quota.rows", null);
            in.add("players", batch);
            var found = request(in).get(12, TimeUnit.SECONDS);
            found.entrySet().forEach(entry -> result.add(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private static JsonObject quotaRequest(String op, UUID player) {
        var in = new JsonObject();
        in.addProperty("op", op);
        if (player != null) in.addProperty("player", player.toString());
        return in;
    }


    public CompletableFuture<Boolean> granted(UUID player) {
        return read(player, GRANT).thenApply(row -> row.get("value").getAsBoolean());
    }

    public CompletableFuture<JsonObject> read(UUID player, String field) {
        return request(storageRequest("storage.get", player, field));
    }

    public CompletableFuture<JsonObject> setGrant(
            UUID player, boolean allowed, long revision) {
        JsonObject request = storageRequest("storage.set", player, GRANT);
        request.addProperty("value", allowed);
        request.addProperty("expectedRevision", revision);
        return request(request);
    }

    /** Monotonic per-player receipt. Concurrent acknowledgements converge on true. */
    public CompletableFuture<JsonObject> markIntroSeen(UUID player) {
        return read(player, INTRO_SEEN).thenCompose(current -> {
            if (current.get("value").getAsBoolean()) return CompletableFuture.completedFuture(current);
            JsonObject write = storageRequest("storage.set", player, INTRO_SEEN);
            write.addProperty("value", true);
            write.add("expectedRevision", current.get("revision"));
            return request(write).exceptionallyCompose(failure ->
                    read(player, INTRO_SEEN).thenCompose(latest ->
                            latest.get("value").getAsBoolean()
                                    ? CompletableFuture.completedFuture(latest)
                                    : CompletableFuture.failedFuture(failure)));
        });
    }

    public CompletableFuture<JsonObject> saveReceipt(
            UUID player, JsonObject receipt, long revision) {
        JsonObject request = storageRequest("storage.set", player, RECEIPT);
        request.add("value", receipt.deepCopy());
        request.addProperty("expectedRevision", revision);
        return request(request);
    }

    public JsonObject accessRows(Collection<String> players, boolean agreements) throws Exception {
        JsonObject rows = new JsonObject();
        List<String> ids = List.copyOf(players);
        JsonArray fields = new JsonArray();
        fields.add(GRANT);
        if (agreements) fields.add(RECEIPT);
        for (int start = 0; start < ids.size(); start += 32) {
            JsonArray batch = new JsonArray();
            ids.subList(start, Math.min(start + 32, ids.size())).forEach(batch::add);
            JsonObject input = new JsonObject();
            input.addProperty("op", "storage.getMany");
            input.add("fields", fields);
            input.add("players", batch);
            JsonObject found = request(input).get(12, TimeUnit.SECONDS).getAsJsonObject("players");
            found.entrySet().forEach(entry -> rows.add(entry.getKey(), entry.getValue()));
        }
        return rows;
    }

    public CompletableFuture<JsonObject> profiles(boolean onlineOnly) {
        JsonObject request = new JsonObject();
        request.addProperty("op", onlineOnly ? "profiles.online" : "profiles.list");
        if (!onlineOnly) request.addProperty("includeOnline", true);
        return request(request);
    }

    public CompletableFuture<JsonObject> profile(UUID player) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "profiles.get");
        request.addProperty("player", player.toString());
        return request(request);
    }

    private static JsonObject storageRequest(String operation, UUID player, String field) {
        if (!GRANT.equals(field) && !RECEIPT.equals(field) && !INTRO_SEEN.equals(field) && !MODEL_PREFERENCES.equals(field)
                && !"reminders".equals(field) && !"received_shares".equals(field))
            throw new IllegalArgumentException("未知的问舟存储字段");
        JsonObject request = new JsonObject();
        request.addProperty("op", operation);
        request.addProperty("player", player.toString());
        request.addProperty("field", field);
        return request;
    }

    private CompletableFuture<JsonObject> request(JsonObject input) {
        return transport.apply(input);
    }
}

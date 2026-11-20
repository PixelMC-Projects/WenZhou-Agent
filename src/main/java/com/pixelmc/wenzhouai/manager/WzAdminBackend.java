package com.pixelmc.wenzhouai.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Authenticated web operations. Player control and receipts remain in the Manager directory. */
public final class WzAdminBackend  {
    private static final int PLAYERS_PER_PAGE = 50;
    private static final int SESSIONS_PER_PAGE = 20;
    private static final Set<String> SETTINGS =
            Set.of(
                    "policy.draft",
                    "policy.publish",
                    "policy.visibility",
                    "credential.set",
                    "credential.remove",
                    "reasoning.set");
    public interface Data {
        JsonObject settings(String operation, JsonObject input);
        int sessionCount(UUID player);
        SessionLists sessions(UUID player, boolean research) throws Exception;
        JsonObject thread(UUID player, String id, boolean research);
        JsonObject usage(UUID player, String id, boolean research);
        void accessChanged(UUID player);
        default void quotaChanged(UUID player) {}
    }

    public record SessionLists(JsonArray all, JsonArray research) {}

    private final Path audit;
    private final WzDirectory directory;
    private final Data data;

    public WzAdminBackend(Path audit, WzDirectory directory, Data data) {
        this.audit = audit;
        this.directory = directory;
        this.data = data;
    }

    public JsonObject request(String operation, JsonObject input, UUID operator) throws Exception {
        if (SETTINGS.contains(operation)) {
            // Audit attempts before mutating; no request values or credential data go to the log.
            audit(operator, operation, null, null);
            return withQuotaSettings(data.settings(operation, input));
        }
        return switch (operation) {
            case "status" -> withQuotaSettings(data.settings(operation, input));
            case "quota.set", "quota.player", "quota.reset", "quota.resetall" -> quota(operation, input, operator);
            case "players" -> players(input);
            case "presence" -> await(directory.profiles(true));
            case "player" -> player(uuid(input, "playerId"));
            case "grant" -> grant(input, operator);
            case "sessions" -> sessions(input);
            case "thread" -> thread(input, operator);
            case "thread.usage" -> threadUsage(input);
            default -> throw new IllegalArgumentException("未知管理操作");
        };
    }

    private JsonObject withQuotaSettings(JsonObject settings) throws Exception {
        await(directory.quotaConfig()).entrySet().forEach(entry -> settings.add(entry.getKey(), entry.getValue()));
        return settings;
    }

    private JsonObject quota(String operation, JsonObject input, UUID operator) throws Exception {
        boolean global = operation.equals("quota.set") || operation.equals("quota.resetall");
        UUID player = global ? null : uuid(input, "playerId");
        audit(operator, operation, player, null);
        String command = switch (operation) {
            case "quota.set" -> "quota.configure";
            case "quota.player" -> "quota.edit";
            case "quota.resetall" -> "quota.resetall";
            default -> "quota.reset";
        };
        JsonObject updated = await(directory.quotaAdmin(command, input, player));
        data.quotaChanged(player);
        if (global) return updated;
        JsonObject output = new JsonObject();
        output.add("quota", updated);
        return output;
    }

    private void loadQuotas(List<JsonObject> rows) throws Exception {
        JsonObject quotas = directory.quotaRows(rows.stream().map(row -> row.get("playerId").getAsString()).toList());
        for (var row : rows) row.add("quota", quotas.get(row.get("playerId").getAsString()));
    }

    private JsonObject players(JsonObject input) throws Exception {
        String tab = text(input, "tab", "all", 16);
        if (!Set.of("all", "online", "offline").contains(tab))
            throw new IllegalArgumentException("无效的玩家筛选项");
        JsonObject roster = await(directory.profiles(false));
        Map<String, String> profiles = profiles(roster);
        Set<String> active = new HashSet<>();
        roster.getAsJsonArray("online").forEach(entry -> active.add(entry.getAsString()));
        active.retainAll(profiles.keySet());
        String query = text(input, "query", "", 128);
        List<JsonObject> candidates = new ArrayList<>();
        for (var profile : profiles.entrySet()) {
            boolean isOnline = active.contains(profile.getKey());
            if ("online".equals(tab) && !isOnline || "offline".equals(tab) && isOnline) continue;
            if (!matches(query, profile.getKey(), profile.getValue())) continue;
            candidates.add(
                    playerIdentity(
                            profile.getKey(),
                            profile.getValue(),
                            isOnline,
                            0));
        }
        Sort sort = sort(input, "grant", Set.of("name", "grant", "agreement", "count", "quotaLimit", "quotaRemaining", "quotaLifetime"));
        JsonObject policy = data.settings("policy.summary", new JsonObject());
        boolean showAgreement = policy.get("showAgreement").getAsBoolean();
        if (sort.key.equals("agreement") && !showAgreement) sort = new Sort("grant", false);
        boolean sortByAccess = "grant".equals(sort.key) || "agreement".equals(sort.key);
        if (sortByAccess) loadAccess(candidates, policy, showAgreement);
        if ("count".equals(sort.key)) fillCounts(candidates);
        boolean sortByQuota = sort.key.startsWith("quota");
        if (sortByQuota) loadQuotas(candidates);
        candidates.sort(comparator(sort));
        JsonObject counts = new JsonObject();
        counts.addProperty("all", profiles.size());
        counts.addProperty("online", active.size());
        counts.addProperty("offline", profiles.size() - active.size());
        List<JsonObject> rows = page(candidates, input, PLAYERS_PER_PAGE);
        if (!sortByAccess) loadAccess(rows, policy, showAgreement);
        if (!"count".equals(sort.key)) fillCounts(rows);
        if (!sortByQuota) loadQuotas(rows);
        for (var row : rows) row.remove("receipt");
        JsonObject output = result(rows, candidates.size(), counts);
        output.add("onlineIds", roster.getAsJsonArray("online"));
        output.add("policy", policy.get("policy"));
        output.addProperty("showAgreement", showAgreement);
        return withQuotaSettings(output);
    }

    private void fillCounts(List<JsonObject> rows) {
        for (var row : rows) row.addProperty("count", data.sessionCount(uuid(row, "playerId")));
    }

    private JsonObject player(UUID player) throws Exception {
        String id = player.toString();
        String name = profileName(player);
        JsonObject row =
                playerIdentity(
                        id,
                        name,
                        online().contains(id),
                        data.sessionCount(player));
        JsonObject policy = data.settings("policy.summary", new JsonObject());
        List<JsonObject> rows = new ArrayList<>(List.of(row));
        loadAccess(rows, policy, policy.get("showAgreement").getAsBoolean());
        if (rows.isEmpty()) throw new IllegalArgumentException("玩家未登记");
        loadQuotas(rows);
        row.add("policy", policy.get("policy"));
        row.add("showAgreement", policy.get("showAgreement"));
        return row;
    }

    private void loadAccess(List<JsonObject> rows, JsonObject policy, boolean showAgreement) throws Exception {
        JsonObject byPlayer = directory.accessRows(
                rows.stream().map(row -> row.get("playerId").getAsString()).toList(), showAgreement);
        rows.removeIf(row -> !byPlayer.has(row.get("playerId").getAsString()));
        for (JsonObject row : rows) {
            JsonObject fields = byPlayer.getAsJsonObject(row.get("playerId").getAsString());
            JsonObject access = fields.getAsJsonObject(WzDirectory.GRANT);
            row.addProperty("granted", access.get("value").getAsBoolean());
            row.addProperty("grantRevision", access.get("revision").getAsLong());
            if (showAgreement) {
                JsonObject receipt = fields.getAsJsonObject(WzDirectory.RECEIPT).getAsJsonObject("value");
                row.add("receipt", receipt);
                row.addProperty("agreement", agreementStatus(receipt, policy.getAsJsonObject("policy")));
            }
        }
    }

    private static String agreementStatus(JsonObject receipt, JsonObject policy) {
        if (!(receipt.get("acceptedAt") instanceof JsonPrimitive date) || !date.isNumber()
                || date.getAsLong() <= 0 || !bool(receipt, "terms", true) || !bool(receipt, "api", true))
            return "未同意";
        return receipt.get("version") instanceof JsonPrimitive version
                && policy.get("version").getAsString().equals(version.getAsString()) ? "已同意" : "需重新同意";
    }

    private JsonObject grant(JsonObject input, UUID operator) throws Exception {
        UUID player = uuid(input, "playerId");
        boolean allowed = requiredBoolean(input, "allowed");
        long revision = integer(input, "revision", 0, Long.MAX_VALUE);
        audit(operator, allowed ? "grant" : "revoke", player, null);
        JsonObject saved = await(directory.setGrant(player, allowed, revision));
        data.accessChanged(player);
        JsonObject output = new JsonObject();
        output.addProperty("granted", saved.get("value").getAsBoolean());
        output.addProperty("grantRevision", saved.get("revision").getAsLong());
        return output;
    }

    private JsonObject sessions(JsonObject input) throws Exception {
        UUID player = input.has("playerId") ? uuid(input, "playerId") : null;
        String tab = text(input, "tab", "all", 16);
        if (!Set.of("all", "research").contains(tab))
            throw new IllegalArgumentException("无效的会话筛选项");
        boolean research = tab.equals("research");
        Map<String, String> profiles = player == null
                ? profiles(await(directory.profiles(false)))
                : Map.of(player.toString(), profileName(player));
        var lists = data.sessions(player, player == null || research);
        List<JsonObject> all = summaries(lists.all(), profiles);
        List<JsonObject> eligible = summaries(lists.research(), profiles);
        int total = all.size(), authorized = eligible.size();
        List<JsonObject> entries = research ? eligible : all;
        String query = text(input, "query", "", 128);
        entries.removeIf(
                row ->
                        !matches(
                                query,
                                row.get("title").getAsString(),
                                row.get("name").getAsString(),
                                row.get("playerId").getAsString()));
        entries.sort(
                comparator(sort(input, "updated", Set.of("title", "name", "count", "updated"))));
        JsonObject counts = new JsonObject();
        counts.addProperty("all", total);
        if (player == null) counts.addProperty("research", authorized);
        return result(page(entries, input, SESSIONS_PER_PAGE), entries.size(), counts);
    }

    private JsonObject thread(JsonObject input, UUID operator) throws Exception {
        UUID player = uuid(input, "playerId");
        String id = sessionId(input);
        String name = profileName(player);
        boolean research = bool(input, "research", false);
        JsonObject thread = data.thread(player, id, research);
        if (thread == null) throw new IllegalArgumentException("会话未找到");
        audit(operator, research ? "research.read" : "thread.read", player, id);
        thread.addProperty("name", name);
        return thread;
    }

    private JsonObject threadUsage(JsonObject input) throws Exception {
        UUID player = uuid(input, "playerId");
        String id = sessionId(input);
        if (await(directory.profile(player)).get("profile").isJsonNull())
            throw new IllegalArgumentException("玩家未登记");
        return data.usage(player, id, bool(input, "research", false));
    }

    private static String sessionId(JsonObject input) {
        String id = text(input, "id", "", 128);
        if (!id.matches("[a-zA-Z0-9_-]{1,128}")) throw new IllegalArgumentException("会话 ID 无效");
        return id;
    }

    private static List<JsonObject> summaries(JsonArray rows, Map<String, String> profiles) {
        List<JsonObject> entries = new ArrayList<>(rows.size());
        for (var element : rows) {
            JsonObject row = element.getAsJsonObject();
            String name = profiles.get(row.get("playerId").getAsString());
            if (name != null) entries.add(sessionFields(row, name));
        }
        return entries;
    }

    private static JsonObject sessionFields(JsonObject row, String name) {
        JsonObject output = new JsonObject();
        for (String key : List.of("id", "playerId", "title", "updated", "count"))
            if (row.has(key)) output.add(key, row.get(key));
        output.addProperty("name", name);
        return output;
    }

    private static List<JsonObject> page(List<JsonObject> source, JsonObject input, int size) {
        int page = input.has("page") ? (int) integer(input, "page", 0, 1_000_000) : 0;
        int from = Math.min(page * size, source.size());
        return source.subList(from, Math.min(from + size, source.size()));
    }

    private static JsonObject result(List<JsonObject> rows, int total, JsonObject counts) {
        JsonArray values = new JsonArray();
        rows.forEach(values::add);
        JsonObject result = new JsonObject();
        result.add("rows", values);
        result.addProperty("total", total);
        result.add("counts", counts);
        return result;
    }

    private static JsonObject playerIdentity(String id, String name, boolean online, long count) {
        JsonObject result = new JsonObject();
        result.addProperty("playerId", id);
        result.addProperty("name", name);
        result.addProperty("isOnline", online);
        result.addProperty("count", count);
        return result;
    }

    private String profileName(UUID player) throws Exception {
        var profile = await(directory.profile(player)).get("profile");
        if (profile.isJsonNull()) throw new IllegalArgumentException("玩家未登记");
        return profile.getAsJsonObject().get("name").getAsString();
    }

    private static Map<String, String> profiles(JsonObject roster) {
        JsonArray rows = roster.getAsJsonArray("players");
        Map<String, String> profiles = new HashMap<>();
        for (var element : rows) {
            JsonObject player = element.getAsJsonObject();
            profiles.put(player.get("id").getAsString(), player.get("name").getAsString());
        }
        return profiles;
    }

    private Set<String> online() throws Exception {
        Set<String> online = new HashSet<>();
        for (var value : await(directory.profiles(true)).getAsJsonArray("players"))
            online.add(value.getAsString());
        return online;
    }

    private static <T> T await(CompletableFuture<T> result) throws Exception {
        return result.get(12, TimeUnit.SECONDS);
    }

    private static UUID uuid(JsonObject input, String key) {
        String value = text(input, key, "", 36);
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) throw new IllegalArgumentException("UUID 无效");
        return uuid;
    }

    private record Sort(String key, boolean reverse) {}

    private static Sort sort(JsonObject input, String fallback, Set<String> keys) {
        JsonObject sort = input.has("sort") ? input.getAsJsonObject("sort") : new JsonObject();
        String key = text(sort, "key", fallback, 16);
        String direction = text(sort, "direction", "asc", 4);
        if (!keys.contains(key) || !Set.of("asc", "desc").contains(direction))
            throw new IllegalArgumentException("无效的排序方式");
        return new Sort(key, direction.equals("desc"));
    }

    private static Comparator<JsonObject> comparator(Sort sort) {
        Collator language = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
        Comparator<JsonObject> byName =
                Comparator.comparing(row -> row.get("name").getAsString(), language);
        Comparator<JsonObject> primary =
                switch (sort.key) {
                    case "grant" -> Comparator.comparing(row -> !row.get("granted").getAsBoolean());
                    case "quotaLimit", "quotaRemaining", "quotaLifetime" ->
                            Comparator.comparingLong(row -> {
                                JsonObject quota = row.getAsJsonObject("quota");
                                if (sort.key.equals("quotaLifetime")) return quota.get("lifetimeNanoYuan").getAsLong();
                                if (quota.get("unlimited").getAsBoolean()) return Long.MAX_VALUE;
                                return quota.get(sort.key.equals("quotaLimit") ? "limitNanoYuan" : "remainingNanoYuan").getAsLong();
                            });
                    case "updated", "count" ->
                            Comparator.comparingLong(row -> row.get(sort.key).getAsLong());
                    default ->
                            Comparator.comparing(row -> row.get(sort.key).getAsString(), language);
                };
        if (sort.reverse) primary = primary.reversed();
        return primary.thenComparing(byName)
                .thenComparing(row -> row.get("playerId").getAsString())
                .thenComparing(row -> row.has("id") ? row.get("id").getAsString() : "");
    }

    private static boolean matches(String query, String... values) {
        String needle = query.toLowerCase(Locale.ROOT).strip();
        for (String value : values)
            if (value.toLowerCase(Locale.ROOT).contains(needle)) return true;
        return false;
    }

    private static long integer(JsonObject input, String key, long min, long max) {
        if (!(input.get(key) instanceof JsonPrimitive value) || !value.isNumber())
            throw new IllegalArgumentException("缺少数字字段");
        long number;
        try {
            number = value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("数字超出范围", invalid);
        }
        if (number < min || number > max) throw new IllegalArgumentException("数字超出范围");
        return number;
    }

    private static String text(JsonObject input, String key, String fallback, int max) {
        if (!input.has(key)) return fallback;
        if (!(input.get(key) instanceof JsonPrimitive value)
                || !value.isString()
                || value.getAsString().length() > max) throw new IllegalArgumentException("文字字段无效");
        return value.getAsString();
    }

    private static boolean requiredBoolean(JsonObject input, String key) {
        if (!(input.get(key) instanceof JsonPrimitive value) || !value.isBoolean())
            throw new IllegalArgumentException("布尔字段无效");
        return value.getAsBoolean();
    }

    private static boolean bool(JsonObject input, String key, boolean fallback) {
        return input.has(key) ? requiredBoolean(input, key) : fallback;
    }

    private synchronized void audit(UUID operator, String action, UUID player, String id)
            throws IOException {
        JsonObject entry = new JsonObject();
        entry.addProperty("at", System.currentTimeMillis());
        entry.addProperty("operator", operator.toString());
        entry.addProperty("action", action);
        if (player != null) entry.addProperty("playerId", player.toString());
        if (id != null) entry.addProperty("sessionId", id);
        Files.createDirectories(audit.toAbsolutePath().getParent());
        Files.writeString(
                audit, entry + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}

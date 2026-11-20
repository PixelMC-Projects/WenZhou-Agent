package com.pixelmc.wenzhouai.service;

import com.google.gson.*;
import com.pixelmc.pixelmcmanager.api.PlayerHubApi;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.pixelmc.wenzhouai.service.WzData.string;

/** Per-answer evidence handles cannot be reused by another player or turn. */
final class WzTurnNotes {
    private final WzGameplayMemory memory;
    private final UUID player;
    private final Set<String> subjects = new LinkedHashSet<>();
    private final List<String> sources = new ArrayList<>();
    private final java.util.concurrent.CompletableFuture<WzGameplayMemory.Scope> initialScope;
    private long lastScopeCheck = System.nanoTime();

    WzTurnNotes(WzGameplayMemory memory, UUID player) {
        this.memory = memory;
        this.player = player;
        initialScope = snapshot();
    }

    private java.util.concurrent.CompletableFuture<WzGameplayMemory.Scope> snapshot() {
        return PlayerHubApi.request(player, "snapshot", "{}").thenApply(raw ->
                WzGameplayMemory.Scope.current(JsonParser.parseString(raw).getAsJsonObject().getAsJsonObject("scope")));
    }

    private void source(JsonObject row, String ref) {
        if (sources.size() >= 256 || ref.length() > 640) return;
        int index = sources.indexOf(ref);
        if (index < 0) { index = sources.size(); sources.add(ref); }
        row.addProperty("source", index + 1);
    }
    private void subject(String type, String id) {
        if (WenzhouReadTools.resourceId(id) != null) subjects.add(type + "/" + id);
    }
    void observe(String kind, String target, JsonObject result) {
        String status = string(result, "status");
        if (kind.equals("locate_gameplay") && status.equals("installed_code")) {
            for (var entry : result.getAsJsonArray("methods")) {
                var row = entry.getAsJsonObject();
                source(row, "method " + string(row, "class") + "." + string(row, "method")
                        + string(row, "descriptor") + " " + row.get("references"));
            }
            return;
        }
        if (!status.equals("live")) return;
        switch (kind) {
            case "item_overview" -> {
                for (var entry : result.getAsJsonArray("items")) {
                    var row = entry.getAsJsonObject();
                    subject("item", string(row, "item"));
                    if (row.has("detail")) source(row, "recipe " + row.getAsJsonArray("recipe_ids").get(0).getAsString());
                }
            }
            case "find_item" -> subject("item", string(result, "unique_item"));
            case "tag", "food_effects", "recipe", "loot_table" -> {
                subject(kind.equals("food_effects") ? "item" : kind, target);
                source(result, kind + " " + target);
                if (kind.equals("recipe")) {
                    var recipe = result.getAsJsonObject("recipe");
                    if (recipe != null && recipe.has("result")) subject("item", string(recipe.getAsJsonObject("result"), "item"));
                }
            }
            case "search_tags", "item_tags" -> {
                if (kind.equals("item_tags")) subject("item", target);
                for (var entry : result.getAsJsonArray("tags")) {
                    var row = entry.getAsJsonObject();
                    String tag = string(row, "tag");
                    subject("tag", tag);
                    source(row, "tag " + tag);
                }
            }
            case "recipes_for_item", "loot_sources" -> {
                subject("item", target);
                if (result.get("total").getAsInt() > 0) source(result, kind + " " + target);
            }
            default -> { }
        }
    }

    JsonObject execute(String action, JsonObject args) throws InterruptedException {
        try {
            // Manager provides the active archive, pack name and version using the authenticated player.
            var scope = initialScope.get(6, TimeUnit.SECONDS);
            if (scope == null)
                return WzGameplayMemory.error("Manager 尚未确认当前整合包档案。");
            // Manager rate-limits player snapshots to one per half second. Consecutive model
            // tool calls share that observation, and later lookups recheck the scope.
            if (System.nanoTime() - lastScopeCheck >= TimeUnit.MILLISECONDS.toNanos(550)) {
                if (!scope.equals(snapshot().get(6, TimeUnit.SECONDS)))
                    return WzGameplayMemory.error("本次提问期间整合包档案发生了变化。");
                lastScopeCheck = System.nanoTime();
            }
            return switch (action) {
                case "search_memory" -> memory.search(scope, strings(args, "keywords"),
                        args.has("page") ? args.get("page").getAsBigDecimal().intValueExact() : 1);
                case "read_memory" -> memory.read(scope, string(args, "id"));
                case "save_memory" -> save(scope, args);
                default -> WzGameplayMemory.error("未知记忆操作。");
            };
        } catch (ExecutionException | TimeoutException unavailable) {
            return WzGameplayMemory.error("Manager 暂未提供当前档案，可继续查询游戏。");
        } catch (java.io.IOException | RuntimeException invalid) {
            return WzGameplayMemory.error("笔记参数或存储不可用，可继续查询游戏。");
        }
    }

    private JsonObject save(WzGameplayMemory.Scope scope, JsonObject args) throws java.io.IOException {
        String topic = string(args, "subject");
        if (!subjects.contains(topic)) topic = "item/" + topic;
        if (!subjects.contains(topic)) return WzGameplayMemory.error("主题必须是本轮已核对的物品或资源 ID。");
        var refs = new LinkedHashSet<String>();
        for (var value : args.getAsJsonArray("sources")) {
            int index = value.getAsBigDecimal().intValueExact() - 1;
            if (index < 0 || index >= sources.size()) return WzGameplayMemory.error("请引用本轮回执上的 source 编号。");
            refs.add(sources.get(index));
        }
        return memory.save(scope, topic, string(args, "facet"), string(args, "summary"), string(args, "detail"),
                strings(args, "keywords"), List.copyOf(refs));
    }
    private static List<String> strings(JsonObject args, String key) {
        var values = new ArrayList<String>();
        for (var value : args.getAsJsonArray(key)) {
            String word = value.getAsString().strip();
            if (values.size() >= 6 || !WzGameplayMemory.plain(word, 48)) throw new IllegalArgumentException();
            values.add(word);
        }
        return values;
    }
}

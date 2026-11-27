package com.pixelmc.wenzhouai.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.Util;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.storage.loot.Deserializers;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

final class WzLoot {
    private static final Map<ResourceManager, CompletableFuture<Index>> CACHE = new WeakHashMap<>();
    private static final Gson TABLE_JSON = Deserializers.createLootTableSerializer().create();

    static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static final int PAGE = 16;

    static final class Index {
        final Map<String, Set<String>> items = new HashMap<>();
        final Map<String, Set<String>> tags = new HashMap<>();
        final Map<String, Set<String>> parents = new HashMap<>();
        int unreadable;

        void add(String table, JsonObject definition) {
            for (JsonElement pool : array(definition, "pools")) {
                if (pool.isJsonObject()) entries(table, array(pool.getAsJsonObject(), "entries"));
            }
        }

        private void entries(String table, JsonArray entries) {
            for (JsonElement value : entries) {
                if (!value.isJsonObject()) continue;
                JsonObject entry = value.getAsJsonObject();
                ResourceLocation type = ResourceLocation.tryParse(WzData.string(entry, "type"));
                if (type == null) continue;
                Map<String, Set<String>> index =
                        switch (type.toString()) {
                            case "minecraft:item" -> items;
                            case "minecraft:tag" -> tags;
                            case "minecraft:loot_table" -> parents;
                            default -> null;
                        };
                if (index != null && entry.has("name")) {
                    ResourceLocation target =
                            ResourceLocation.tryParse(WzData.string(entry, "name"));
                    if (target != null)
                        index.computeIfAbsent(target.toString(), k -> new HashSet<>()).add(table);
                }
                entries(table, array(entry, "children"));
            }
        }

        JsonObject sources(String item, Collection<String> itemTags, int page) {
            Set<String> direct = new TreeSet<>(items.getOrDefault(item, Set.of()));
            for (String tag : itemTags) direct.addAll(tags.getOrDefault(tag, Set.of()));
            Set<String> found = new TreeSet<>(direct);
            var queue = new ArrayDeque<>(direct);
            while (!queue.isEmpty()) {
                for (String parent : parents.getOrDefault(queue.remove(), Set.of())) {
                    if (found.add(parent)) queue.add(parent);
                }
            }
            long start = (long) (page - 1) * PAGE;
            if (page < 1 || page > 1 && start >= found.size()) return WzData.error("页码超出战利品来源范围。");
            JsonArray matches = new JsonArray();
            List<String> ordered = new ArrayList<>(found);
            for (int i = (int) start; i < Math.min(start + PAGE, ordered.size()); i++) {
                String table = ordered.get(i);
                JsonObject row = new JsonObject();
                row.addProperty("table", table);
                row.addProperty("via", direct.contains(table) ? "item_or_tag" : "nested_table");
                matches.add(row);
            }
            JsonObject result =
                    WenzhouReadTools.message(
                            "live", "本服当前已加载的战利品池。引用是候选来源，请读表核实条件和权重；事件、全局战利品修饰器及动态物品变换可能另行增删掉落。");
            result.addProperty("total", found.size());
            result.addProperty("unreadable", unreadable);
            result.addProperty("page", page);
            result.add("tables", matches);
            return result;
        }
    }

    static CompletableFuture<JsonObject> query(
            MinecraftServer server, String kind, ResourceLocation id, int page) {
        ResourceManager resources = server.getResourceManager();
        var tables = server.getLootData();
        if ("loot_table".equals(kind)) {
            if (!tables.getKeys(LootDataType.TABLE).contains(id))
                return CompletableFuture.completedFuture(
                        WenzhouReadTools.message("not_active", "本服未加载该战利品表。"));
            LootTable active = tables.getLootTable(id);
            return CompletableFuture.supplyAsync(() -> detail(active, id, page), Util.ioPool());
        }
        var holder =
                BuiltInRegistries.ITEM.getHolder(
                        net.minecraft.resources.ResourceKey.create(
                                net.minecraft.core.registries.Registries.ITEM, id));
        if (holder.isEmpty())
            return CompletableFuture.completedFuture(
                    WenzhouReadTools.message("not_registered", "物品未注册。"));
        List<String> itemTags = holder.get().tags().map(t -> t.location().toString()).toList();
        CompletableFuture<Index> future;
        synchronized (CACHE) {
            future = CACHE.get(resources);
            if (future == null || future.isCompletedExceptionally()) {
                var active = new HashMap<ResourceLocation, LootTable>();
                for (var key : tables.getKeys(LootDataType.TABLE))
                    active.put(key, tables.getLootTable(key));
                future = CompletableFuture.supplyAsync(() -> build(active), Util.ioPool());
                CACHE.put(resources, future);
            }
        }
        return future.thenApplyAsync(
                index -> index.sources(id.toString(), itemTags, page), Util.ioPool());
    }

    static CompletableFuture<JsonObject> withPackChanges(
            MinecraftServer server, String kind, ResourceLocation id, int page) {
        var base = query(server, kind, id, page);
        // Rule results paginate independently of the raw table and source pages.
        return base.thenCombine(WzPackChanges.query(server, id, 1, true), WzLoot::attachChanges);
    }

    private static JsonObject attachChanges(JsonObject result, JsonObject changes) {
        if (!"live".equals(WzData.string(result, "status"))) return result;
        if (!"pack_changes".equals(WzData.string(changes, "status"))) {
            result.add("pack_changes", changes);
        } else {
            // The running pool already reflects data-pack replacements. Forward only conditional
            // rules.
            var checks = new JsonObject();
            var rules = new JsonArray();
            for (var value : array(changes, "matches")) {
                var hit = value.getAsJsonObject();
                if (!hit.has("line") && !hit.has("conditions") && !hit.has("type")) continue;
                var rule = new JsonObject();
                for (String field :
                        List.of(
                                "path",
                                "line",
                                "conditions",
                                "script_rules",
                                "type",
                                "item",
                                "loot_table_id",
                                "excerpt")) if (hit.has(field)) rule.add(field, hit.get(field));
                rules.add(rule);
            }
            checks.add("rules", rules);
            checks.addProperty("total", rules.size());
            if (WzData.flag(changes, "more")) checks.addProperty("more", true);
            if (WzData.number(changes, "unreadable") > 0)
                checks.addProperty("unreadable", WzData.number(changes, "unreadable"));
            result.add("pack_changes", checks);
        }
        result.addProperty("probability_scope", "loaded_loot_pools");
        result.addProperty(
                "message",
                "这些是本服运行中的战利品池；实际掉落还受池条件、全局修饰器和事件影响。pack_changes.more 表示还有规则，可按目标查"
                    + " pack_changes(view=loot)。");
        if (!result.has("tables")) return result;
        var operations = new HashMap<String, Set<String>>();
        for (var hit : array(changes, "matches")) {
            for (var rule : array(hit.getAsJsonObject(), "script_rules")) {
                var evidence = rule.getAsJsonObject();
                operations
                        .computeIfAbsent(
                                WzData.string(evidence, "table"), unused -> new TreeSet<>())
                        .add(WzData.string(evidence, "operation"));
            }
        }
        for (var element : array(result, "tables")) {
            var row = element.getAsJsonObject();
            var names = operations.get(WzData.string(row, "table"));
            if (names == null) continue;
            var list = new JsonArray();
            names.forEach(list::add);
            row.add("script_operations", list);
        }
        return result;
    }

    private static Index build(Map<ResourceLocation, LootTable> tables) {
        Index index = new Index();
        for (var entry : tables.entrySet()) {
            try {
                index.add(
                        entry.getKey().toString(),
                        TABLE_JSON.toJsonTree(entry.getValue(), LootTable.class).getAsJsonObject());
            } catch (RuntimeException failure) {
                index.unreadable++;
            }
        }
        return index;
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key)
                : new JsonArray();
    }

    private static JsonObject detail(LootTable active, ResourceLocation table, int page) {
        try {
            JsonElement tree = TABLE_JSON.toJsonTree(active, LootTable.class);
            String json = TABLE_JSON.toJson(tree);
            int length = 4000;
            long start = (long) (page - 1) * length;
            if (start < 0 || start >= json.length()) return WzData.error("页码超出战利品表范围。");
            JsonObject result = WenzhouReadTools.message("live", "当前运行中的掉落池、条件、数量及权重。");
            result.addProperty("table", table.toString());
            result.addProperty("page", page);
            result.addProperty("more", start + length < json.length());
            if (page == 1 && tree.isJsonObject()) {
                JsonArray summaries = summarizePools(tree.getAsJsonObject());
                if (!summaries.isEmpty()) result.add("pool_summaries", summaries);
            }
            if (page == 1 && table.getPath().startsWith("gameplay/"))
                result.add("trigger_references", WzLootTriggers.references(table));
            result.addProperty(
                    "definition",
                    json.substring((int) start, (int) Math.min(json.length(), start + length)));
            return result;
        } catch (RuntimeException failure) {
            return WenzhouReadTools.message("unavailable", "无法读取当前运行中的掉落池。");
        }
    }

    private static JsonArray summarizePools(JsonObject tableObj) {
        JsonArray summaries = new JsonArray();
        try {
            JsonArray pools = array(tableObj, "pools");
            int poolLimit = Math.min(pools.size(), 12);
            for (int p = 0; p < poolLimit; p++) {
                JsonElement poolEl = pools.get(p);
                if (!poolEl.isJsonObject()) continue;
                JsonObject pool = poolEl.getAsJsonObject();
                JsonArray entries = array(pool, "entries");
                if (entries.isEmpty()) continue;
                JsonObject summary = new JsonObject();
                summary.addProperty("pool_index", p);
                if (pool.has("rolls"))
                    summary.addProperty("rolls", formatNumberProvider(pool.get("rolls")));
                if (pool.has("bonus_rolls")) {
                    String bonus = formatNumberProvider(pool.get("bonus_rolls"));
                    if (!"0".equals(bonus) && !"0.0".equals(bonus))
                        summary.addProperty("bonus_rolls", bonus);
                }
                boolean hasConditions = !array(pool, "conditions").isEmpty();
                if (hasConditions) summary.addProperty("has_conditions", true);
                boolean hasComposite = false;
                long totalWeight = 0;
                for (JsonElement entryEl : entries) {
                    if (!entryEl.isJsonObject()) continue;
                    JsonObject entry = entryEl.getAsJsonObject();
                    String type = WzData.string(entry, "type");
                    if (entry.has("children")
                            || type.endsWith(":alternatives")
                            || type.endsWith(":group")
                            || type.endsWith(":sequence")) {
                        hasComposite = true;
                        continue;
                    }
                    totalWeight += entryWeight(entry);
                }
                if (!hasComposite) summary.addProperty("total_weight", totalWeight);
                else summary.addProperty("has_composite_entries", true);
                JsonArray itemRows = new JsonArray();
                int entryLimit = Math.min(entries.size(), 24);
                for (int i = 0; i < entryLimit; i++) {
                    JsonElement entryEl = entries.get(i);
                    if (!entryEl.isJsonObject()) continue;
                    JsonObject entry = entryEl.getAsJsonObject();
                    JsonObject row = new JsonObject();
                    String type = WzData.string(entry, "type");
                    if (!type.isEmpty()) row.addProperty("type", type);
                    String name = WzData.string(entry, "name");
                    if (!name.isEmpty()) row.addProperty("name", name);
                    if (!entry.has("children")
                            && !type.endsWith(":alternatives")
                            && !type.endsWith(":group")
                            && !type.endsWith(":sequence")) {
                        long weight = entryWeight(entry);
                        row.addProperty("weight", weight);
                        if (!hasComposite && totalWeight > 0) {
                            double pct = Math.round(weight * 10000.0 / totalWeight) / 100.0;
                            row.addProperty("pick_probability_percent", pct);
                        }
                    }
                    if (!array(entry, "conditions").isEmpty())
                        row.addProperty("has_conditions", true);
                    inspectCountFunctions(entry, row);
                    itemRows.add(row);
                }
                summary.add("entries", itemRows);
                if (entries.size() > entryLimit)
                    summary.addProperty("entries_omitted", entries.size() - entryLimit);
                summaries.add(summary);
            }
        } catch (RuntimeException ignored) {
            return new JsonArray();
        }
        return summaries;
    }

    private static long entryWeight(JsonObject entry) {
        if (!entry.has("weight")
                || !entry.get("weight").isJsonPrimitive()
                || !entry.get("weight").getAsJsonPrimitive().isNumber()) return 1L;
        long w = entry.get("weight").getAsLong();
        return w > 0 ? w : 0L;
    }

    private static String formatNumberProvider(JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) {
            String raw = el.getAsString();
            return raw.endsWith(".0") ? raw.substring(0, raw.length() - 2) : raw;
        }
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("min") && obj.has("max")) {
                return formatNumberProvider(obj.get("min"))
                        + "~"
                        + formatNumberProvider(obj.get("max"));
            }
            if (obj.has("value")) return formatNumberProvider(obj.get("value"));
        }
        return el.toString();
    }

    private static void inspectCountFunctions(JsonObject entry, JsonObject row) {
        for (JsonElement fnEl : array(entry, "functions")) {
            if (!fnEl.isJsonObject()) continue;
            JsonObject fn = fnEl.getAsJsonObject();
            String fnType = WzData.string(fn, "function");
            if (!"minecraft:set_count".equals(fnType) || !fn.has("count")) continue;
            JsonElement countEl = fn.get("count");
            row.addProperty("count_range", formatNumberProvider(countEl));
            if (countEl.isJsonObject()) {
                JsonObject c = countEl.getAsJsonObject();
                if (c.has("min")
                        && c.get("min").isJsonPrimitive()
                        && c.get("min").getAsJsonPrimitive().isNumber()
                        && c.get("min").getAsDouble() <= 0.0) {
                    row.addProperty("zero_count_possible", true);
                }
            } else if (countEl.isJsonPrimitive()
                    && countEl.getAsJsonPrimitive().isNumber()
                    && countEl.getAsDouble() <= 0.0) {
                row.addProperty("zero_count_possible", true);
            }
        }
    }

    private WzLoot() {}
}

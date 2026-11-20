package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
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
import net.minecraft.Util;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.storage.loot.LootDataType;

final class WzLoot {
    private static final Map<ResourceManager, CompletableFuture<Index>> CACHE = new WeakHashMap<>();
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
                Map<String, Set<String>> index = switch (type.toString()) {
                    case "minecraft:item" -> items;
                    case "minecraft:tag" -> tags;
                    case "minecraft:loot_table" -> parents;
                    default -> null;
                };
                if (index != null && entry.has("name")) {
                    ResourceLocation target = ResourceLocation.tryParse(WzData.string(entry, "name"));
                    if (target != null) index.computeIfAbsent(target.toString(), k -> new HashSet<>()).add(table);
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
            long start = (long)(page - 1) * PAGE;
            if (page < 1 || page > 1 && start >= found.size()) return WzData.error("页码超出战利品来源范围。");
            JsonArray matches = new JsonArray();
            List<String> ordered = new ArrayList<>(found);
            for (int i = (int)start; i < Math.min(start + PAGE, ordered.size()); i++) {
                String table = ordered.get(i);
                JsonObject row = new JsonObject();
                row.addProperty("table", table);
                row.addProperty("via", direct.contains(table) ? "item_or_tag" : "nested_table");
                matches.add(row);
            }
            JsonObject result = WenzhouReadTools.message("live",
                    "本服已加载战利品表的生效数据包定义。引用是候选来源，请读表核实条件和权重；事件、全局战利品修饰器及动态物品变换可能另行增删掉落。");
            result.addProperty("total", found.size());
            result.addProperty("unreadable", unreadable);
            result.addProperty("page", page);
            result.add("tables", matches);
            return result;
        }
    }

    static CompletableFuture<JsonObject> query(MinecraftServer server, String kind, ResourceLocation id, int page) {
        ResourceManager resources = server.getResourceManager();
        var tables = server.getLootData();
        if ("loot_table".equals(kind)) {
            if (!tables.getKeys(LootDataType.TABLE).contains(id))
                return CompletableFuture.completedFuture(WenzhouReadTools.message("not_active", "本服未加载该战利品表。"));
            return CompletableFuture.supplyAsync(() -> detail(resources, id, page), Util.ioPool());
        }
        var holder = BuiltInRegistries.ITEM.getHolder(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.ITEM, id));
        if (holder.isEmpty()) return CompletableFuture.completedFuture(WenzhouReadTools.message("not_registered", "物品未注册。"));
        List<String> itemTags = holder.get().tags().map(t -> t.location().toString()).toList();
        CompletableFuture<Index> future;
        synchronized (CACHE) {
            future = CACHE.get(resources);
            if (future == null || future.isCompletedExceptionally()) {
                var ids = List.copyOf(tables.getKeys(LootDataType.TABLE));
                future = CompletableFuture.supplyAsync(() -> build(resources, ids), Util.ioPool());
                CACHE.put(resources, future);
            }
        }
        return future.thenApplyAsync(index -> index.sources(id.toString(), itemTags, page), Util.ioPool());
    }

    static CompletableFuture<JsonObject> withPackChanges(MinecraftServer server, String kind, ResourceLocation id, int page) {
        var base = query(server, kind, id, page);
        // Rule results paginate independently of the raw table and source pages.
        return base.thenCombine(WzPackChanges.query(server, id, 1, true), WzLoot::attachChanges);
    }

    private static JsonObject attachChanges(JsonObject result, JsonObject changes) {
        result.add("pack_changes", changes);
        if (!"live".equals(WzData.string(result,"status"))) return result;
        result.addProperty("message","这里列出数据包原表。结合 pack_changes 检查移除、追加、替换和触发条件，再判断最终掉落。");
        if (!result.has("tables")) return result;
        var operations = new HashMap<String,Set<String>>();
        for (var hit : array(changes,"matches")) {
            for (var rule : array(hit.getAsJsonObject(),"script_rules")) {
                var evidence = rule.getAsJsonObject();
                operations.computeIfAbsent(WzData.string(evidence,"table"), unused -> new TreeSet<>())
                        .add(WzData.string(evidence,"operation"));
            }
        }
        for (var element : array(result,"tables")) {
            var row = element.getAsJsonObject();
            var names = operations.get(WzData.string(row,"table"));
            if (names == null) continue;
            var list = new JsonArray();
            names.forEach(list::add);
            row.add("script_operations",list);
        }
        return result;
    }

    private static Resource definition(ResourceManager resources, ResourceLocation table) throws IOException {
        return resources.getResourceOrThrow(table.withPath("loot_tables/" + table.getPath() + ".json"));
    }

    private static Index build(ResourceManager resources, List<ResourceLocation> tables) {
        Index index = new Index();
        for (var table : tables) {
            try (var reader = definition(resources, table).openAsReader()) {
                index.add(table.toString(), JsonParser.parseReader(reader).getAsJsonObject());
            } catch (RuntimeException | IOException failure) {
                index.unreadable++;
            }
        }
        return index;
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonObject detail(ResourceManager resources, ResourceLocation table, int page) {
        try {
            Resource resource = definition(resources, table);
            String json;
            try (var reader = resource.openAsReader()) {
                json = JsonParser.parseReader(reader).toString();
            }
            int length = 4000;
            long start = (long)(page - 1) * length;
            if (start < 0 || start >= json.length()) return WzData.error("页码超出战利品表范围。");
            JsonObject result = WenzhouReadTools.message("live", "最高优先级数据包定义，含掉落池、条件、数量及权重。");
            result.addProperty("table", table.toString());
            result.addProperty("pack", resource.sourcePackId());
            result.addProperty("page", page);
            result.addProperty("more", start + length < json.length());
            result.addProperty("definition", json.substring((int)start, (int)Math.min(json.length(), start + length)));
            return result;
        } catch (IOException | RuntimeException failure) {
            return WenzhouReadTools.message("unavailable", "未能读取这张已加载战利品表的数据包定义。");
        }
    }

    private WzLoot() {}
}

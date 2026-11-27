package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Exact entity aliases share the item catalog's language snapshot, but not its candidate list. */
final class WzEntityNames {
    record Entry(String id, String name, String lootTable) {}
    private final Map<String, List<Entry>> names = new HashMap<>();

    void add(Entry entity, Collection<String> chinese, Collection<String> english) {
        var aliases = new LinkedHashSet<String>();
        aliases.add(key(entity.id()));
        aliases.add(key(entity.name()));
        chinese.forEach(name -> aliases.add(key(name)));
        english.forEach(name -> aliases.add(key(name)));
        aliases.remove("");
        for (String alias : aliases)
            names.computeIfAbsent(alias, unused -> new ArrayList<>()).add(entity);
    }

    JsonObject find(String query) {
        var matches = names.getOrDefault(key(query), List.of());
        var result = new JsonObject();
        result.addProperty("status", "live");
        result.addProperty("query", query);
        result.addProperty("total", matches.size());
        var rows = new JsonArray();
        for (var match : matches.subList(0, Math.min(8, matches.size()))) {
            var row = new JsonObject();
            row.addProperty("id", match.id());
            row.addProperty("name", match.name());
            rows.add(row);
        }
        result.add("entity_matches", rows);
        if (matches.size() == 1) {
            var entity = matches.get(0);
            result.addProperty("unique_entity", entity.id());
            result.addProperty("loot_table", entity.lootTable());
        } else if (!matches.isEmpty()) {
            result.addProperty("message", "有同名实体，请玩家确认模组或注册名后再查掉落。");
        } else {
            result.addProperty("message", "当前实体注册表中未定位到这个名称。");
        }
        return result;
    }

    private static String key(String name) {
        return Normalizer.normalize(name.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}

package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Lookup over registry-backed item IDs. A translation file cannot introduce an item. */
final class WzItemIndex {
    private static final int LIMIT = 8;
    private static final java.util.regex.Pattern SPACE = java.util.regex.Pattern.compile("\\s+");
    record Entry(String id, String modName, String displayName, List<String> chinese, List<String> english) {}

    private record Candidate(Entry item, Set<String> names) {}
    private final Map<String, Candidate> byId = new HashMap<>();
    private final Map<String, List<Candidate>> exact = new HashMap<>();
    private final List<Candidate> entries = new ArrayList<>();

    WzItemIndex(List<Entry> items) {
        for (Entry item : items.stream().sorted(Comparator.comparing(Entry::id)).toList()) {
            Set<String> names = new LinkedHashSet<>();
            names.add(normal(item.id()));
            names.add(normal(item.id().substring(item.id().indexOf(':') + 1)));
            names.add(normal(item.displayName()));
            for (String name : item.chinese()) names.add(normal(name));
            for (String name : item.english()) names.add(normal(name));
            names.remove("");
            Candidate candidate = new Candidate(item, names);
            entries.add(candidate);
            byId.put(item.id(), candidate);
            for (String name : names) exact.computeIfAbsent(name, ignored -> new ArrayList<>()).add(candidate);
        }
    }

    static boolean valid(String query) {
        return query != null && !query.isBlank() && query.length() <= 240
                && query.codePoints().noneMatch(Character::isISOControl);
    }

    boolean hasDisplayName(String id, String label) {
        Candidate row = byId.get(id);
        if (row == null) return false;
        Entry item = row.item();
        String clean = net.minecraft.ChatFormatting.stripFormatting(label);
        return clean.equals(item.displayName()) || item.chinese().contains(clean) || item.english().contains(clean)
                || label.equals(item.displayName()) || item.chinese().contains(label) || item.english().contains(label);
    }

    JsonObject find(String query) {
        if (!valid(query)) return WzData.error("请输入有效的物品名称或注册 ID。");
        String input = query.strip();
        if (input.indexOf('*') >= 0) return matchIds(input);
        Candidate id = byId.get(input.toLowerCase(Locale.ROOT));
        if (id != null) return result(query, "id", List.of(id));

        String needle = normal(input);
        if (needle.isEmpty()) return result(query, "none", List.of());
        List<Candidate> names = exact.get(needle);
        if (names != null) return result(query, "exact", names);
        List<Candidate> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (Candidate candidate : entries) {
            boolean partial = false, prefix = false;
            for (String name : candidate.names()) {
                if (name.startsWith(needle)) { prefix = true; break; }
                if (name.contains(needle)) partial = true;
            }
            if (prefix) starts.add(candidate);
            else if (partial) contains.add(candidate);
        }
        starts.addAll(contains);
        return result(query, starts.isEmpty() ? "none" : "partial", starts);
    }

    private JsonObject matchIds(String pattern) {
        if (!pattern.matches("[a-z0-9_.-]+:(?:\\*[a-z0-9_./-]+|[a-z0-9_./-]+\\*)"))
            return WzData.error("注册名统计请使用 modid:*后缀 或 modid:前缀*。");
        int star = pattern.indexOf('*');
        String prefix = pattern.substring(0, star);
        String suffix = pattern.substring(star + 1);
        List<Candidate> matches = entries.stream().filter(candidate -> {
            String id = candidate.item().id();
            return id.startsWith(prefix) && id.endsWith(suffix);
        }).toList();
        return result(pattern, "id_pattern", matches);
    }

    private static JsonObject result(String query, String match, List<Candidate> selected) {
        JsonArray rows = new JsonArray();
        for (int i = 0; i < Math.min(LIMIT, selected.size()); i++) {
            Entry item = selected.get(i).item();
            JsonObject row = new JsonObject();
            row.addProperty("id", item.id());
            row.addProperty("mod_id", item.id().substring(0, item.id().indexOf(':')));
            row.addProperty("mod_name", item.modName());
            row.addProperty("name", item.displayName());
            if (!item.chinese().isEmpty()) row.addProperty("zh_cn", item.chinese().get(0));
            if (!item.english().isEmpty()) row.addProperty("en_us", item.english().get(0));
            rows.add(row);
        }
        JsonObject result = new JsonObject();
        result.addProperty("status", "live");
        result.addProperty("query", query);
        result.addProperty("match", match);
        result.addProperty("total", selected.size());
        result.add("candidates", rows);
        if (selected.size() == 1 && (match.equals("id") || match.equals("exact")))
            result.addProperty("unique_item", selected.get(0).item().id());
        return result;
    }

    private static String normal(String value) {
        String clean = Normalizer.normalize(net.minecraft.ChatFormatting.stripFormatting(value), Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
        return SPACE.matcher(clean.replace('_', ' ')).replaceAll(" ").strip();
    }
}

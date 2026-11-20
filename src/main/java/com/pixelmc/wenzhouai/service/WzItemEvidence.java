package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

final class WzItemEvidence {
    static final int MAX_REFERENCES = 4;

    static JsonObject overview(RecipeManager recipes, RegistryAccess registries, String ids) {
        var items = new LinkedHashMap<Item, ResourceLocation>();
        for (String text : ids.split(",")) {
            var id = WenzhouReadTools.resourceId(text);
            if (id != null && items.size() < MAX_REFERENCES)
                BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> items.put(item, id));
        }
        if (items.isEmpty()) return WenzhouReadTools.message("not_registered", "引用的物品未注册。");
        Map<Item, TreeSet<ResourceLocation>> matches = new LinkedHashMap<>();
        items.keySet().forEach(item -> matches.put(item, new TreeSet<>()));
        int unreadable = 0;
        for (var recipe : recipes.getRecipes()) {
            try {
                var output = recipe.getResultItem(registries);
                var matching = matches.get(output.getItem());
                if (!output.isEmpty() && matching != null) matching.add(recipe.getId());
            } catch (RuntimeException unavailable) {
                unreadable++;
            }
        }
        JsonArray rows = new JsonArray();
        for (var entry : items.entrySet()) {
            var idsForItem = matches.get(entry.getKey());
            JsonObject row = new JsonObject();
            row.addProperty("item", entry.getValue().toString());
            row.addProperty("count", idsForItem.size());
            JsonArray candidates = new JsonArray();
            idsForItem.stream().limit(6).forEach(id -> candidates.add(id.toString()));
            row.add("recipe_ids", candidates);
            if (idsForItem.size() == 1) {
                var id = idsForItem.first();
                try {
                    var evidence = WenzhouRecipes.live(recipes, registries, id);
                    if (evidence.toString().length() <= 3100) row.add("detail", evidence);
                } catch (RuntimeException unsupported) {
                    // A nonstandard ingredient can still be inspected with a separate recipe lookup.
                }
            }
            rows.add(row);
        }
        var result = WenzhouReadTools.message("live", "已注册物品与本服固定产物配方。零项或回拼配方仍需调查其他来源。");
        result.add("items", rows);
        result.addProperty("unreadable", unreadable);
        return result;
    }

    static JsonObject tags(String kind, String query, int page, String hints) {
        Stream<TagKey<Item>> stream;
        if (kind.equals("item_tags")) {
            var id = WenzhouReadTools.resourceId(query);
            var item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item == null) return WenzhouReadTools.message("not_registered", "该物品未注册。");
            stream = item.builtInRegistryHolder().tags();
        } else {
            String term = query.strip().replaceFirst("^get_?(?=[A-Z])", "")
                    .replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
            if (term.startsWith("#")) term = term.substring(1);
            if (!term.matches("[a-z0-9_./:-]{1,120}"))
                return WenzhouReadTools.message("invalid_request", "请输入标签 ID 的完整名称或一段英文关键词。");
            return search(term, hints, page);
        }
        return ranked(stream, query.replace("#", "").toLowerCase(java.util.Locale.ROOT), hints, page);
    }

    static JsonObject relatedTags(ResourceLocation missing, int page, String hints) {
        return search(missing.toString(), hints, page);
    }

    private static JsonObject search(String query, String hints, int page) {
        int colon = query.indexOf(':');
        String path = query.substring(colon + 1);
        if (path.isEmpty()) return WenzhouReadTools.message("invalid_request", "请输入标签路径。");
        List<TagKey<Item>> names = BuiltInRegistries.ITEM.getTagNames().toList();
        var candidates = names.stream().filter(tag -> tag.location().toString().contains(query)
                || (colon >= 0 && tag.location().getPath().contains(path))).toList();
        if (candidates.isEmpty()) {
            var fragments = java.util.Arrays.stream(path.split("[_./-]+")).filter(word -> word.length() >= 3)
                    .filter(word -> !word.equals("tag") && !word.equals("item")).toList();
            candidates = names.stream().filter(tag -> fragments.stream().anyMatch(tag.location().getPath()::contains)).toList();
        }
        return ranked(candidates.stream(), query, hints, page);
    }

    private static JsonObject ranked(Stream<TagKey<Item>> candidates, String query, String hints, int page) {
        record Scored(TagKey<Item> tag, int score) {}
        String[] words = hints.isBlank() ? new String[0] : hints.split(" +");
        String path = query.substring(query.indexOf(':') + 1);
        var sorted = candidates.map(tag -> {
            var id = tag.location();
            int score = id.toString().equals(query) ? 1000 : 0;
            if (query.startsWith(id.getNamespace() + ":")) score += 2;
            if (id.getPath().equals(path)) score += 8;
            if (id.getPath().contains(path)) score += 4;
            if (java.util.Arrays.asList(id.getPath().split("[_./-]+")).contains(path)) score += 5;
            int pending = 0;
            for (int i = 0; i < words.length; i++) {
                if (id.toString().contains(words[i])) score += 12;
                else pending |= 1 << i;
            }
            if (pending != 0) {
                var members = BuiltInRegistries.ITEM.getTag(tag).orElseThrow();
                for (var member : members) {
                    String item = BuiltInRegistries.ITEM.getKey(member.value()).toString();
                    for (int i = 0; i < words.length; i++) {
                        int bit = 1 << i;
                        if ((pending & bit) != 0 && item.contains(words[i])) { score += 5; pending &= ~bit; }
                    }
                    if (pending == 0) break;
                }
            }
            return new Scored(tag, score);
        }).sorted(java.util.Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(row -> row.tag.location().toString())).map(Scored::tag).toList();
        return tagPage(sorted, page);
    }

    private static JsonObject tagPage(List<TagKey<Item>> sorted, int page) {
        long start = (page - 1L) * 8;
        if (page < 1 || (page > 1 && start >= sorted.size()))
            return WenzhouReadTools.message("invalid_page", "标签查询页码超出范围。");
        JsonArray tags = new JsonArray();
        for (int i = (int) start; i < Math.min(start + 8, sorted.size()); i++) {
            var tag = sorted.get(i);
            var members = BuiltInRegistries.ITEM.getTag(tag).orElseThrow();
            JsonObject row = new JsonObject();
            row.addProperty("tag", tag.location().toString());
            row.addProperty("total", members.size());
            JsonArray examples = new JsonArray();
            members.stream().limit(6).forEach(holder -> examples.add(BuiltInRegistries.ITEM.getKey(holder.value()).toString()));
            row.add("members", examples);
            tags.add(row);
        }
        var result = WenzhouReadTools.message("live", "本服生效的物品标签及成员示例。用 tag 按完整 ID 翻阅全部成员。");
        result.add("tags", tags);
        result.addProperty("total", sorted.size());
        result.addProperty("page", page);
        return result;
    }
    private WzItemEvidence() {}
}

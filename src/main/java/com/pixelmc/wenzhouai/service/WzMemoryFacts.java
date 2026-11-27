package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Projects exact, positive observations from allowlisted tool DTOs. */
final class WzMemoryFacts {
    record Fact(String subject, String facet, String summary, String detail, String provenance) {}

    private static final int TOOL_LIMIT = 8;
    private static final Set<String> RECIPE_INPUT_ROLES =
            Set.of("ingredient", "tool", "chamber", "pedestal", "template", "base", "addition");

    static List<Fact> from(String kind, String target, JsonObject result) {
        if (!text(result, "status").equals("live")
                || WzData.flag(result, "truncated")
                || WzData.flag(result, "time_limit_reached")
                || result.has("personal")) return List.of();
        var facts = new LinkedHashSet<Fact>();
        switch (kind) {
            case "recipe" -> recipe(facts, target, result);
            case "find_item" -> {
                var detail = object(result, "recipe_detail");
                String id = id(object(detail, "recipe"), "id");
                if (text(detail, "status").equals("live")
                        && id(result, "unique_item")
                                .equals(id(object(object(detail, "recipe"), "result"), "item")))
                    recipe(facts, id, detail);
            }
            case "item_overview" -> {
                for (var row : rows(result, "items")) {
                    String item = id(row, "item");
                    var detail = object(row, "detail");
                    var definition = object(detail, "recipe");
                    if (text(detail, "status").equals("live")
                            && item.equals(id(object(definition, "result"), "item")))
                        recipe(facts, id(definition, "id"), detail);
                    else if (!item.isEmpty())
                        for (var value : array(row, "recipe_ids")) {
                            String recipe = id(value);
                            if (!recipe.isEmpty())
                                add(
                                        facts,
                                        "item",
                                        item,
                                        "obtain",
                                        "配方产物",
                                        "已加载的配方 " + recipe + " 声明产出 " + item + "。",
                                        "recipe " + recipe);
                        }
                }
            }
            case "item_sources" -> itemSources(facts, target, result);
            case "recipes_for_item", "recipes_using_item" ->
                    recipeIndex(facts, kind, target, result);
            case "machine_conversions" -> machine(facts, target, result);
            case "loot_sources" -> lootSources(facts, target, result);
            case "loot_table" -> lootTable(facts, target, result);
            case "chest_sources", "structure_chests" ->
                    structureChests(facts, kind, target, result);
            case "structure_generation" -> structure(facts, target, result);
            case "trade_offers" -> trades(facts, target, result);
            case "event_outputs" -> event(facts, target, result);
            case "food_effects" -> food(facts, target, result);
            case "food_book_stats" -> {
                var total = natural(result, "book_total");
                if (target.equals("solcarrot:food_book") && total != null)
                    add(
                            facts,
                            "item",
                            target,
                            "conditions",
                            "食物书收录条件",
                            "当前食物书统计 " + total + " 种符合本服收录条件的食物。",
                            "food_book_stats " + target);
            }
            case "worldgen_sources" -> worldgen(facts, target, result);
            case "entity_spawns" -> spawn(facts, target, result);
            case "tag", "biome_tag", "item_tags", "search_tags" ->
                    tags(facts, kind, target, result);
            default -> {}
        }
        return facts.stream().limit(TOOL_LIMIT).toList();
    }

    private static void itemSources(Set<Fact> facts, String target, JsonObject result) {
        var recipes = object(result, "recipes");
        if (text(recipes, "status").equals("live")) {
            recipeIndex(facts, "recipes_for_item", target, recipes);
            var detail = object(recipes, "recipe_detail");
            var def = object(detail, "recipe");
            if (text(detail, "status").equals("live")
                    && target.equals(id(object(def, "result"), "item")))
                recipe(facts, id(def, "id"), detail);
        }
        var loot = object(result, "loot_sources");
        if (text(loot, "status").equals("live")) lootSources(facts, target, loot);
        var chests = object(result, "chest_sources");
        if (text(chests, "status").equals("live"))
            structureChests(facts, "chest_sources", target, chests);
        var worldgen = object(result, "worldgen");
        if (text(worldgen, "status").equals("live")) worldgen(facts, target, worldgen);
        var trades = object(result, "trades");
        if (text(trades, "status").equals("live")) trades(facts, target, trades);
    }

    private static void recipe(Set<Fact> facts, String target, JsonObject result) {
        var recipe = object(result, "recipe");
        String recipeId = id(recipe, "id");
        if (recipeId.isEmpty() || !target.equals(recipeId)) return;
        var output = object(recipe, "result");
        String item = id(output, "item");
        String serializer = id(recipe, "serializer");
        Integer count = natural(output, "count");
        if (item.isEmpty() || serializer.isEmpty() || count == null || count == 0) return;
        String machine = id(recipe, "machine");
        String route =
                machine.isEmpty()
                        ? "已加载配方 " + recipeId + "（" + serializer + "）"
                        : "已加载的 " + machine + " 配方 " + recipeId;
        String detail = route + " 声明产出 " + count + " 个 " + item + "。";
        String materials = simpleIngredients(array(recipe, "ingredients"));
        if (materials != null && !materials.isEmpty()) {
            detail += "材料槽为 " + materials + "。";
        } else if (recipe.has("chamber_input")) {
            String chamber = ingredient(recipe.get("chamber_input"));
            String pedestals = simpleIngredients(array(recipe, "pedestal_ingredients"));
            Integer cost = natural(recipe, "source_cost");
            if (!chamber.isEmpty() && pedestals != null && cost != null)
                detail +=
                        "灌注材料为 "
                                + chamber
                                + (pedestals.isEmpty() ? "" : "，基座材料为 " + pedestals)
                                + "，源质消耗 "
                                + cost
                                + "。";
        }
        if (detail.length() > 240) detail = route + " 声明产出 " + count + " 个 " + item + "。";
        add(facts, "item", item, "obtain", "配方产物", detail, "recipe " + recipeId);
        add(facts, "recipe", recipeId, "operation", "配方工序", detail, "recipe " + recipeId);
    }

    private static String simpleIngredients(JsonArray values) {
        var entries = new ArrayList<String>();
        for (var value : values) {
            if (value.isJsonNull()) continue;
            String ingredient = ingredient(value);
            if (ingredient.isEmpty()) return null;
            entries.add(ingredient);
        }
        return String.join("、", entries);
    }

    private static String ingredient(JsonElement value) {
        if (value != null && value.isJsonObject() && value.getAsJsonObject().size() == 1) {
            var row = value.getAsJsonObject();
            String item = id(row, "item");
            if (!item.isEmpty()) return item;
            String tag = id(row, "tag");
            if (!tag.isEmpty()) return "#" + tag;
        }
        return "";
    }

    private static void recipeIndex(
            Set<Fact> facts, String kind, String target, JsonObject result) {
        if (!target.equals(id(result, "item"))) return;
        boolean producing = kind.equals("recipes_for_item");
        for (var row : rows(result, "matches")) {
            String recipe = id(row, "recipe");
            if (recipe.isEmpty()) continue;
            for (var role : rows(row, "roles")) {
                String roleName = text(role, "role");
                if (producing && Set.of("primary", "additional").contains(roleName)) {
                    Integer count = natural(role, "count");
                    if (count == null || count == 0) continue;
                    String chance = decimal(role, "base_chance");
                    if (role.has("base_chance")
                            && (chance.isEmpty()
                                    || Double.parseDouble(chance) <= 0
                                    || Double.parseDouble(chance) > 1)) continue;
                    add(
                            facts,
                            "item",
                            target,
                            "obtain",
                            "配方产物",
                            "已加载配方 "
                                    + recipe
                                    + " 声明"
                                    + (roleName.equals("additional") ? "额外" : "")
                                    + "产出 "
                                    + count
                                    + " 个 "
                                    + target
                                    + (chance.isEmpty() ? "。" : "，该产物的基础概率为 " + chance + "。"),
                            "recipe " + recipe);
                } else if (!producing && RECIPE_INPUT_ROLES.contains(roleName)) {
                    String roleLabel =
                            switch (roleName) {
                                case "tool" -> "工具";
                                case "chamber" -> "灌注材料";
                                case "pedestal" -> "基座材料";
                                case "template" -> "锻造模板";
                                case "base" -> "锻造底材";
                                case "addition" -> "锻造附加材料";
                                default -> "材料槽候选";
                            };
                    add(
                            facts,
                            "item",
                            target,
                            "uses",
                            "配方用途",
                            "已加载配方 " + recipe + " 将 " + target + " 列为" + roleLabel + "。",
                            "recipe " + recipe);
                }
            }
        }
    }

    private static void machine(Set<Fact> facts, String target, JsonObject result) {
        String machine = id(result, "machine");
        String vessel = id(result, "serving_container");
        if (machine.isEmpty() || vessel.isEmpty()) return;
        for (var row : rows(result, "entries")) {
            String recipe = id(row, "recipe");
            String regular = id(row, "regular_output");
            if (!target.equals(id(row, "cup_output")) || recipe.isEmpty() || regular.isEmpty())
                continue;
            add(
                    facts,
                    "item",
                    target,
                    "obtain",
                    "容器换算",
                    "使用 " + machine + " 和 " + vessel + " 时，配方 " + recipe + " 的产物 " + regular
                            + " 映射为 " + target + "。",
                    "machine_conversions " + recipe);
        }
    }

    private static void lootSources(Set<Fact> facts, String target, JsonObject result) {
        for (var row : rows(result, "tables")) {
            String table = id(row, "table");
            String via = text(row, "via");
            if (table.isEmpty() || !Set.of("item_or_tag", "nested_table").contains(via)) continue;
            String relation = via.equals("nested_table") ? "通过子表引用" : "通过物品或标签条目引用";
            add(
                    facts,
                    "item",
                    target,
                    "drops",
                    "战利品池来源",
                    "当前战利品表 " + table + " " + relation + " " + target + "。触发和掉落条件须另查。",
                    "loot_table " + table);
        }
    }

    private static void lootTable(Set<Fact> facts, String target, JsonObject result) {
        if (!target.equals(id(result, "table"))
                || !Objects.equals(natural(result, "page"), 1)
                || WzData.flag(result, "more")) return;
        String definition = text(result, "definition");
        if (definition.length() > 4000) return;
        try {
            var table = JsonParser.parseString(definition).getAsJsonObject();
            for (var pool : rows(table, "pools")) lootEntries(facts, target, pool, "entries", 0);
        } catch (RuntimeException ignored) {
            // Only a complete, parsed live table can supply facts.
        }
    }

    private static void lootEntries(
            Set<Fact> facts, String table, JsonObject pool, String field, int depth) {
        if (depth > 8 || facts.size() >= TOOL_LIMIT) return;
        for (var row : rows(pool, field)) {
            String item = id(row, "name");
            if (text(row, "type").equals("minecraft:item") && !item.isEmpty()) {
                String detail = "当前战利品表 " + table + " 含 " + item + " 的掉落条目。触发和池条件须另查。";
                add(facts, "item", item, "drops", "战利品池来源", detail, "loot_table " + table);
                add(facts, "loot_table", table, "drops", "战利品条目", detail, "loot_table " + table);
            }
            lootEntries(facts, table, row, "children", depth + 1);
            if (facts.size() >= TOOL_LIMIT) break;
        }
    }

    private static void structureChests(
            Set<Fact> facts, String kind, String target, JsonObject result) {
        for (var row : rows(result, "entries")) {
            String structure = id(row, "structure");
            String table = id(row, "loot_table");
            String container = id(row, "container");
            if (structure.isEmpty() || table.isEmpty() || container.isEmpty()) continue;
            String prefix = "可生成结构 " + structure + " 的 " + container + " 模板引用战利品表 " + table;
            if (kind.equals("chest_sources")) {
                add(
                        facts,
                        "item",
                        target,
                        "obtain",
                        "结构战利品来源",
                        prefix + "，表中可查到 " + target + " 的条目或引用。实际产出仍受池条件限制。",
                        "chest_sources " + structure + " " + table);
            } else if (target.equals(structure)) {
                add(
                        facts,
                        "structure",
                        structure,
                        "drops",
                        "结构容器来源",
                        prefix + "。房间生成与表条件决定实际战利品。",
                        "structure_chests " + structure + " " + table);
            }
        }
    }

    private static void structure(Set<Fact> facts, String target, JsonObject result) {
        for (var row : rows(result, "entries")) {
            String structure = id(row, "structure");
            String dimension = id(row, "dimension");
            String set = id(row, "structure_set");
            if (!target.equals(structure)
                    || dimension.isEmpty()
                    || set.isEmpty()
                    || !WzData.flag(row, "world_generation_enabled")) continue;
            add(
                    facts,
                    "structure",
                    target,
                    "conditions",
                    "结构生成配置",
                    "维度 " + dimension + " 的生效结构集合 " + set + " 列有 " + target + "。位置与地形条件另查。",
                    "structure_generation " + set);
        }
    }

    private static void trades(Set<Fact> facts, String target, JsonObject result) {
        for (var row : rows(result, "entries")) {
            String merchant = id(row, "merchant");
            var gives = object(row, "gives");
            var cost = object(row, "cost");
            var second = object(row, "second_cost");
            String item = id(gives, "item");
            String want = tradeStack(cost);
            String offer = tradeStack(gives);
            String extra = tradeStack(second);
            boolean wandering = merchant.equals("minecraft:wandering_trader");
            Integer level = natural(row, wandering ? "pool" : "level");
            if (merchant.isEmpty()
                    || item.isEmpty()
                    || offer.isEmpty()
                    || want.isEmpty()
                    || level == null
                    || row.has("second_cost") && extra.isEmpty()) continue;
            String detail =
                    "当前报价池中，"
                            + merchant
                            + (wandering ? " 第 " + level + " 组" : " 等级 " + level)
                            + " 有收取 "
                            + want
                            + (extra.isEmpty() ? "" : " 和 " + extra)
                            + "、给出 "
                            + offer
                            + " 的报价。实际商人需抽到该报价。";
            if (item.equals(target) || merchant.equals(target))
                add(facts, "item", item, "obtain", "商人报价池", detail, "trade_offers " + merchant);
            if (target.equals(id(cost, "item")) || target.equals(id(second, "item")))
                add(facts, "item", target, "uses", "商人收取材料", detail, "trade_offers " + merchant);
        }
    }

    private static String tradeStack(JsonObject row) {
        String item = id(row, "item");
        Integer count = natural(row, "count");
        Integer minimum = natural(row, "min");
        Integer maximum = natural(row, "max");
        if (item.isEmpty()) return "";
        if (count != null && count > 0) return count + " 个 " + item;
        if (minimum != null && maximum != null && minimum > 0 && maximum >= minimum)
            return minimum + "–" + maximum + " 个 " + item;
        return "";
    }

    private static void event(Set<Fact> facts, String target, JsonObject result) {
        if (!target.equals(id(result, "target"))) return;
        for (var row : rows(result, "entries")) {
            if (!text(row, "confidence").equals("verified")) continue;
            if (rows(row, "outputs").stream()
                    .noneMatch(
                            output ->
                                    target.equals(id(output, "id"))
                                            && natural(output, "quantity") != null
                                            && natural(output, "quantity") > 0)) continue;
            String mod = text(row, "sourceMod");
            if (!mod.matches("[a-z][a-z0-9_]{0,63}")) continue;
            add(
                    facts,
                    "item",
                    target,
                    "obtain",
                    "事件产物",
                    text(row, "fact"),
                    "event_outputs " + mod + " " + target);
        }
    }

    private static void food(Set<Fact> facts, String target, JsonObject result) {
        if (!target.equals(id(result, "item")) || !WzData.flag(result, "has_food_properties"))
            return;
        Integer nutrition = natural(result, "nutrition");
        String saturation = decimal(result, "saturation_modifier");
        if (nutrition != null && !saturation.isEmpty())
            add(
                    facts,
                    "item",
                    target,
                    "effects",
                    "默认食物属性",
                    "默认物品栈的营养值为 " + nutrition + "，饱和度修正值为 " + saturation + "。",
                    "food_effects " + target);
        for (var row : rows(result, "effects")) {
            String effect = id(row, "effect");
            Integer level = natural(row, "level");
            Integer ticks = natural(row, "duration_ticks");
            String chance = decimal(row, "chance");
            boolean infinite = WzData.flag(row, "infinite");
            if (effect.isEmpty()
                    || level == null
                    || level == 0
                    || !infinite && ticks == null
                    || chance.isEmpty()
                    || Double.parseDouble(chance) <= 0) continue;
            add(
                    facts,
                    "item",
                    target,
                    "effects",
                    "默认食用效果",
                    "默认食物属性声明效果 "
                            + effect
                            + " "
                            + level
                            + " 级，"
                            + (infinite ? "无限时长" : "持续 " + ticks + " tick")
                            + "，基础概率 "
                            + chance
                            + "。自定义食用事件另查。",
                    "food_effects " + target);
        }
    }

    private static void worldgen(Set<Fact> facts, String target, JsonObject result) {
        String block = id(result, "places_block");
        if (block.isEmpty()) return;
        add(
                facts,
                "item",
                target,
                "uses",
                "对应放置方块",
                target + " 注册为方块 " + block + " 的放置物，具体放置条件另查。",
                "worldgen_sources " + target);
        for (var row : rows(result, "entries")) {
            String biome = id(row, "biome");
            String feature = id(row, "placed_feature");
            if (!block.equals(id(row, "block"))
                    || biome.isEmpty()
                    || feature.isEmpty()
                    || row.has("placement_status")) continue;
            add(
                    facts,
                    "item",
                    target,
                    "conditions",
                    "方块生成配置",
                    "对应方块 "
                            + block
                            + " 出现在群系 "
                            + biome
                            + " 挂载的地物 "
                            + feature
                            + " 的产物定义中。实际生成受地形与概率限制。",
                    "worldgen_sources " + feature);
        }
    }

    private static void spawn(Set<Fact> facts, String target, JsonObject result) {
        for (var row : rows(result, "entries")) {
            String biome = id(row, "biome");
            Integer weight = natural(row, "weight");
            Integer min = natural(row, "min_group");
            Integer max = natural(row, "max_group");
            if (biome.isEmpty()
                    || weight == null
                    || weight == 0
                    || min == null
                    || max == null
                    || max < min) continue;
            add(
                    facts,
                    "entity",
                    target,
                    "conditions",
                    "自然生成表",
                    "群系 "
                            + biome
                            + " 将 "
                            + target
                            + " 列为自然生成候选，权重 "
                            + weight
                            + "，群体大小 "
                            + min
                            + "–"
                            + max
                            + "。其他生成条件另查。",
                    "entity_spawns " + biome);
        }
    }

    private static void tags(Set<Fact> facts, String kind, String target, JsonObject result) {
        if (kind.equals("tag") && target.equals(id(result, "tag"))) {
            for (var item : array(result, "items")) tagMember(facts, target, id(item));
        } else if (kind.equals("biome_tag")) {
            for (var value : array(result, "entries")) {
                String biome = id(value);
                if (!biome.isEmpty())
                    add(
                            facts,
                            "biome_tag",
                            target,
                            "conditions",
                            "生效群系标签",
                            "生效群系标签 #" + target + " 包含群系 " + biome + "。",
                            "biome_tag " + target);
            }
        } else {
            for (var row : rows(result, "tags")) {
                String tag = id(row, "tag");
                if (tag.isEmpty()) continue;
                if (kind.equals("item_tags")) tagMember(facts, tag, target);
                else for (var item : array(row, "members")) tagMember(facts, tag, id(item));
            }
        }
    }

    private static void tagMember(Set<Fact> facts, String tag, String item) {
        if (tag.isEmpty() || item.isEmpty()) return;
        String detail = "生效物品标签 #" + tag + " 包含 " + item + "。";
        add(facts, "tag", tag, "conditions", "生效物品标签", detail, "tag " + tag);
        add(facts, "item", item, "conditions", "物品标签归属", detail, "tag " + tag);
    }

    private static void add(
            Set<Fact> facts,
            String type,
            String id,
            String facet,
            String channel,
            String detail,
            String provenance) {
        if (facts.size() >= TOOL_LIMIT
                || resource(id).isEmpty()
                || !WzGameplayMemory.plain(detail, 240)
                || !WzGameplayMemory.plain(provenance, 640)) return;
        String path = id.substring(id.indexOf(':') + 1);
        String summary = channel + " · " + path;
        if (summary.length() > 48) summary = summary.substring(0, 47) + "…";
        facts.add(new Fact(type + "/" + id, facet, summary, detail, provenance));
    }

    static String subject(String value) {
        int split = value.indexOf('/');
        if (split < 1
                || !Set.of(
                                "item",
                                "tag",
                                "biome_tag",
                                "entity",
                                "recipe",
                                "loot_table",
                                "structure")
                        .contains(value.substring(0, split))
                || resource(value.substring(split + 1)).isEmpty()) return "";
        return value;
    }

    private static String resource(String value) {
        return WenzhouReadTools.resourceId(value) == null ? "" : value;
    }

    static String text(JsonObject row, String key) {
        return row.get(key) instanceof com.google.gson.JsonPrimitive p && p.isString()
                ? p.getAsString()
                : "";
    }

    static JsonObject object(JsonObject row, String key) {
        return row.get(key) instanceof JsonObject value ? value : new JsonObject();
    }

    static JsonArray array(JsonObject row, String key) {
        return row.get(key) instanceof JsonArray value ? value : new JsonArray();
    }

    static List<JsonObject> rows(JsonObject row, String key) {
        var values = new ArrayList<JsonObject>();
        for (var value : array(row, key))
            if (value instanceof JsonObject object) values.add(object);
        return values;
    }

    private static String id(JsonObject row, String key) {
        return resource(text(row, key));
    }

    private static String id(JsonElement value) {
        return value instanceof com.google.gson.JsonPrimitive p && p.isString()
                ? resource(p.getAsString())
                : "";
    }

    private static Integer natural(JsonObject row, String key) {
        if (!(row.get(key) instanceof com.google.gson.JsonPrimitive p) || !p.isNumber())
            return null;
        try {
            int value = p.getAsBigDecimal().intValueExact();
            return value >= 0 ? value : null;
        } catch (NumberFormatException | ArithmeticException invalid) {
            return null;
        }
    }

    private static String decimal(JsonObject row, String key) {
        if (!(row.get(key) instanceof com.google.gson.JsonPrimitive p) || !p.isNumber()) return "";
        String value = p.getAsString();
        return value.length() <= 24 && Double.isFinite(p.getAsDouble()) ? value : "";
    }

    private WzMemoryFacts() {}
}

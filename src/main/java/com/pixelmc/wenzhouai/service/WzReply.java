package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

final class WzReply {
    private static final String RESOURCE_ID = "[a-z0-9_.-]+:[a-z0-9_./-]+";
    private static final Pattern REFERENCE =
            Pattern.compile("\\[\\[(recipe|item|stack):(" + RESOURCE_ID + "|[a-z0-9-]{36})]]");
    private static final Pattern VALID_RESOURCE = Pattern.compile(RESOURCE_ID);
    private static final Pattern CLAUSES = Pattern.compile("[，,。！？!?；;\n]|以及|另外|同时|顺便");
    private static final Pattern CRAFT = Pattern.compile("合成|配方|制作|打造|(?:怎么|如何|怎样)(?:做|摆)|工作台");
    private static final Pattern OBTAIN = Pattern.compile("获得|获取|得到|弄到|拿到|从哪|哪里|在哪|怎么来|怎么挖");
    private static final Pattern OTHER_QUESTION =
            Pattern.compile("用途|用法|怎么用|如何使用|比较|对比|伤害|属性|掉率|商店|价格|限购|怎么修|有什么用");
    private static final Pattern GRID_HEADER =
            Pattern.compile(
                    "左(?:列|格)?[|]中(?:列|格)?[|]右(?:列|格)?|第?1(?:列|格)[|]第?2(?:列|格)[|]第?3(?:列|格)");
    private static final Pattern GRID_INTRO = Pattern.compile(".*(?:合成|摆放|工作台|制作|配方|格子).*");
    private static final Pattern SOURCE_CHECK = Pattern.compile("未核实|未能核实|没有核实|未确认|未能确认|还不确定");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");
    private static final int INLINE_ITEM_BYTES = 6500;
    // Only a delimited list label can be redundant. Ordinary prose before an item stays intact.
    private static final Pattern LIST_LABEL =
            Pattern.compile(
                    "(?:[、，,][ \\t]*|(?:^|\\n)[ \\t]*[-•][ \\t]+)([^、，,\\n\\r]{1,160}?)[ \\t]*\\z");

    private static final Pattern PAREN_ALIAS =
            Pattern.compile("^[ \\t]*(?:（([^（）\\r\\n]{1,160})）|\\(([^()\\r\\n]{1,160})\\))");

    record ListLabel(
            JsonArray parts,
            int position,
            String fragment,
            String label,
            String id,
            boolean leading) {
        void localize(WzItemIndex index) {
            if (!index.hasDisplayName(id, label)) return;
            String text = parts.get(position).getAsString();
            if (leading && text.startsWith(fragment))
                parts.set(
                        position,
                        new com.google.gson.JsonPrimitive(text.substring(fragment.length())));
            if (!leading && text.endsWith(fragment))
                parts.set(
                        position,
                        new com.google.gson.JsonPrimitive(
                                text.substring(0, text.length() - fragment.length())));
        }
    }

    static List<ListLabel> listLabels(JsonObject message) {
        List<ListLabel> labels = new ArrayList<>();
        if (message.has("messages")) {
            for (var turn : message.getAsJsonArray("messages"))
                collectLabels(turn.getAsJsonObject(), labels);
        } else collectLabels(message, labels);
        return labels;
    }

    private static void collectLabels(JsonObject turn, List<ListLabel> labels) {
        JsonObject reply = object(turn, "reply");
        if (!reply.has("text") || !reply.get("text").isJsonArray()) return;
        JsonArray parts = reply.getAsJsonArray("text");
        JsonObject items = object(reply, "items");
        for (int i = 0; i < parts.size(); i++) {
            if (!parts.get(i).isJsonObject()) continue;
            String key = string(parts.get(i).getAsJsonObject(), "item");
            String id = string(object(items, key), "id");
            if (id.isEmpty()) continue;
            if (i + 1 < parts.size() && parts.get(i + 1).isJsonPrimitive()) {
                var after = PAREN_ALIAS.matcher(parts.get(i + 1).getAsString());
                if (after.find())
                    labels.add(
                            new ListLabel(
                                    parts,
                                    i + 1,
                                    after.group(),
                                    after.group(1) != null ? after.group(1) : after.group(2),
                                    id,
                                    true));
            }
            if (i == 0 || !parts.get(i - 1).isJsonPrimitive()) continue;
            String text = parts.get(i - 1).getAsString();
            var match = LIST_LABEL.matcher(text);
            if (match.find())
                labels.add(
                        new ListLabel(
                                parts,
                                i - 1,
                                text.substring(match.start(1)),
                                match.group(1),
                                id,
                                false));
        }
    }

    private final Map<String, JsonObject> recipes = new HashMap<>();
    private final Map<String, JsonObject> stacks = new HashMap<>();
    private final Function<String, String> itemNames;
    private final WorkbenchRequest workbench;
    private String preflightRecipe = "";
    private String acquisition = "";
    private String acquisitionTool = "";
    private boolean alternateSource;
    private boolean deterministicCard;
    private boolean requestedMaterialIsIngredient;

    WzReply(Function<String, String> itemNames) {
        this(itemNames, null);
    }

    WzReply(Function<String, String> itemNames, WorkbenchRequest workbench) {
        this.itemNames = itemNames;
        this.workbench = workbench;
    }

    record WorkbenchRequest(String output, String material, boolean specific) {
        static WorkbenchRequest parse(String question) {
            var all = new LinkedHashSet<String>();
            var outputs = new LinkedHashSet<String>();
            var materials = new LinkedHashSet<String>();
            for (String clause : CLAUSES.split(question)) {
                var items = new LinkedHashSet<String>();
                var references = REFERENCE.matcher(clause);
                while (references.find()) {
                    if (references.group(1).equals("item")) items.add(references.group(2));
                }
                all.addAll(items);
                if (items.size() != 1) continue;
                boolean craft = CRAFT.matcher(clause).find();
                boolean obtain = OBTAIN.matcher(clause).find();
                if (craft && !obtain) outputs.addAll(items);
                if (obtain && !craft) materials.addAll(items);
            }
            if (outputs.size() != 1) return null;
            boolean specific =
                    materials.size() <= 1
                            && all.size() == outputs.size() + materials.size()
                            && !OTHER_QUESTION.matcher(question).find();
            return new WorkbenchRequest(
                    outputs.iterator().next(),
                    materials.size() == 1 ? materials.iterator().next() : "",
                    specific);
        }

        JsonObject matchingRecipe(JsonObject overview) {
            for (var element : elements(overview, "items")) {
                if (!element.isJsonObject()) continue;
                var item = element.getAsJsonObject();
                if (!output.equals(string(item, "item")) || number(item, "count") != 1) continue;
                var detail = object(item, "detail");
                var recipe = object(detail, "recipe");
                String serializer = string(recipe, "serializer");
                if (!string(detail, "status").equals("live")
                        || !output.equals(string(object(recipe, "result"), "item"))
                        || !(serializer.equals("minecraft:crafting_shaped")
                                || serializer.equals("minecraft:crafting_shapeless"))) continue;
                return recipe;
            }
            return new JsonObject();
        }

        boolean includesMaterial(JsonObject recipe) {
            if (material.isEmpty()) return false;
            for (var ingredient : elements(recipe, "ingredients")) {
                if (ingredient.isJsonObject()
                        && material.equals(string(ingredient.getAsJsonObject(), "item")))
                    return true;
            }
            return false;
        }
    }

    private static JsonArray elements(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonArray()
                ? value.getAsJsonArray(key)
                : new JsonArray();
    }

    String completeAnswer(String modelText) {
        if (!deterministicCard) return modelText;
        String marker = "[[recipe:" + preflightRecipe + "]]";
        String answer = removeIncorrectGrid(modelText.replace(marker, "")).strip();
        if (answer.isBlank()) {
            answer = "[[item:" + workbench.output() + "]]用工作台按下方配方合成。";
            if (!workbench.specific()) answer += "其他部分暂未核实。";
        }
        if (requestedMaterialIsIngredient) {
            String toolName = acquisitionTool.isEmpty() ? null : itemNames.apply(acquisitionTool);
            if (!acquisition.isEmpty()
                    && !answer.contains(acquisition)
                    && (acquisitionTool.isEmpty() || !answer.contains(acquisitionTool))
                    && (toolName == null || !answer.contains(toolName))) {
                answer += "\n\n" + acquisition;
            } else if (acquisition.isEmpty()
                    && !alternateSource
                    && !SOURCE_CHECK.matcher(answer).find()) {
                answer += "\n\n[[item:" + workbench.material() + "]]的获得方法目前还未核实。";
            }
        }
        return answer + "\n\n" + marker;
    }

    boolean hasDeterministicCard() {
        return deterministicCard;
    }

    private String removeIncorrectGrid(String input) {
        var data = recipes.get(preflightRecipe);
        if (data == null || number(data, "width") != 3 || number(data, "height") != 3) return input;
        var lines = input.split("\\R", -1);
        var selectedSlots = elements(data, "display_slots");
        for (int start = 0; start + 4 < lines.length; start++) {
            String[] header = cells(lines[start]);
            String[] separator = cells(lines[start + 1]);
            if (header.length != 3
                    || separator.length != 3
                    || !GRID_HEADER.matcher(String.join("|", header)).matches()
                    || !java.util.Arrays.stream(separator)
                            .allMatch(cell -> cell.matches(":?-{2,}:?"))) continue;
            int intro = start - 1;
            while (intro >= 0 && lines[intro].isBlank()) intro--;
            if (intro < 0 || !GRID_INTRO.matcher(lines[intro]).matches()) continue;
            String[][] rows = {
                cells(lines[start + 2]), cells(lines[start + 3]), cells(lines[start + 4])
            };
            if (!java.util.Arrays.stream(rows).allMatch(cells -> cells.length == 3)
                    || start + 5 < lines.length && cells(lines[start + 5]).length == 3) continue;
            boolean correct = true;
            for (int row = 0; row < 3; row++)
                for (int column = 0; column < 3; column++)
                    correct &=
                            matchesIngredient(
                                    rows[row][column], selectedSlots.get(row * 3 + column));
            if (!correct) {
                java.util.Arrays.fill(lines, start, start + 5, "");
                if (lines[intro].matches(
                        "(?:请)?按(?:照)?(?:下表|下面(?:的|这个)表|下方表格)(?:合成|制作|摆放)?[。:：]?$"))
                    lines[intro] = "工作台配方见下方配方卡。";
            }
        }
        return String.join("\n", lines).replaceAll("\n{3,}", "\n\n");
    }

    private static String[] cells(String line) {
        String trimmed = line.strip();
        if (trimmed.length() < 2 || !trimmed.startsWith("|") || !trimmed.endsWith("|"))
            return new String[0];
        return java.util.Arrays.stream(trimmed.substring(1, trimmed.length() - 1).split("\\|", -1))
                .map(String::strip)
                .toArray(String[]::new);
    }

    private boolean matchesIngredient(String modelCell, JsonElement slot) {
        String cell = modelCell.replace("**", "").replace("`", "").strip();
        if (slot.isJsonNull()) return cell.matches("|空(?:格|白)?|无|—+|-+");
        for (var key : elements(slot.getAsJsonObject(), "keys")) {
            var stack = stacks.get(key.getAsString());
            if (stack == null) continue;
            String id = string(stack, "id");
            if (cell.equals(id)
                    || cell.equals("[[item:" + id + "]]")
                    || cell.equals("[[stack:" + key.getAsString() + "]]")
                    || cell.equals(string(stack, "name"))
                    || cell.equals(itemNames.apply(id))) return true;
        }
        return false;
    }

    void collect(String mode, String kind, String id, JsonObject evidence) {
        if ("item_sources".equals(kind) && "live".equals(string(evidence, "status"))) {
            JsonObject recipesObj = object(evidence, "recipes");
            if (recipesObj.has("recipe_detail") && recipesObj.get("recipe_detail").isJsonObject()) {
                var ids = recipesObj.getAsJsonArray("recipe_ids");
                if (ids != null && !ids.isEmpty()) {
                    collect(
                            "live",
                            "recipe",
                            ids.get(0).getAsString(),
                            recipesObj.getAsJsonObject("recipe_detail"));
                }
            }
            if (workbench != null && workbench.material().equals(id) && "live".equals(mode)) {
                for (String branch :
                        new String[] {
                            "recipes",
                            "loot_sources",
                            "chest_sources",
                            "worldgen",
                            "shop",
                            "quests",
                            "trades"
                        }) {
                    JsonObject sub = object(evidence, branch);
                    if ("live".equals(string(sub, "status")) && number(sub, "total") > 0) {
                        alternateSource = true;
                        break;
                    }
                }
            }
            return;
        }
        if ("find_item".equals(kind) && evidence.has("recipe_detail")) {
            collect(
                    "live",
                    "recipe",
                    evidence.getAsJsonObject("fixed_output_recipes")
                            .getAsJsonArray("recipe_ids")
                            .get(0)
                            .getAsString(),
                    evidence.getAsJsonObject("recipe_detail"));
            return;
        }
        if ("item_overview".equals(kind) && evidence.has("items")) {
            if (workbench != null) {
                var recipe = workbench.matchingRecipe(evidence);
                preflightRecipe = string(recipe, "id");
                requestedMaterialIsIngredient = workbench.includesMaterial(recipe);
            }
            for (var element : evidence.getAsJsonArray("items")) {
                var row = element.getAsJsonObject();
                if (row.has("detail"))
                    collect(
                            "live",
                            "recipe",
                            row.getAsJsonArray("recipe_ids").get(0).getAsString(),
                            row.getAsJsonObject("detail"));
            }
            deterministicCard =
                    !preflightRecipe.isEmpty()
                            && buildRecipeCard(new JsonObject(), preflightRecipe) != null;
            return;
        }
        if (workbench != null
                && workbench.material().equals(id)
                && "live".equals(mode)
                && "event_outputs".equals(kind)
                && id.equals(string(evidence, "target"))) {
            for (var candidate : elements(evidence, "entries")) {
                if (!candidate.isJsonObject()) continue;
                var row = candidate.getAsJsonObject();
                if (!"verified".equals(string(row, "confidence"))) continue;
                for (var result : elements(row, "outputs")) {
                    if (result.isJsonObject()
                            && id.equals(string(result.getAsJsonObject(), "id"))
                            && !string(row, "fact").isBlank()) {
                        acquisition = string(row, "fact").strip();
                        acquisitionTool = string(row, "tool");
                        return;
                    }
                }
            }
            return;
        }
        if (workbench != null
                && workbench.material().equals(id)
                && "live".equals(mode)
                && "live".equals(string(evidence, "status"))
                && java.util.Set.of(
                                "recipes_for_item",
                                "loot_sources",
                                "chest_sources",
                                "trade_offers",
                                "merchant_offers",
                                "quest_rewards",
                                "shop_item",
                                "worldgen_sources",
                                "machine_conversions")
                        .contains(kind)
                && number(evidence, "total") > 0) alternateSource = true;
        JsonObject displayed = new JsonObject();
        String status = string(evidence, "status");
        if ("live".equals(mode) && "recipe".equals(kind) && "live".equals(status)) {
            JsonObject recipe = object(evidence, "recipe").deepCopy();
            JsonObject result = object(recipe, "result");
            recipe.addProperty("display_output", observe(result, displayed));
            var ingredients = evidence.get("display_ingredients");
            if (ingredients != null && ingredients.isJsonArray()) {
                JsonArray slots = new JsonArray();
                for (JsonElement group : ingredients.getAsJsonArray()) {
                    slots.add(
                            group.isJsonNull()
                                    ? JsonNull.INSTANCE
                                    : observeGroup(group.getAsJsonObject(), displayed));
                }
                recipe.add("display_slots", slots);
            }
            recipes.put(id, recipe);
        } else if ("read".equals(mode)
                && "command".equals(kind)
                && "recipe_evidence".equals(status)) {
            JsonObject fields = object(evidence, "display_fields");
            if (fields.size() > 0) {
                JsonObject fieldRefs = new JsonObject();
                fields.entrySet()
                        .forEach(
                                field ->
                                        fieldRefs.add(
                                                field.getKey(),
                                                observeGroup(
                                                                field.getValue().getAsJsonObject(),
                                                                displayed)
                                                        .get("keys")));
                evidence.add("display_field_references", fieldRefs);
            }
        }
        if (displayed.size() > 0) evidence.add("display_item_references", displayed);
        evidence.remove("display_fields");
        evidence.remove("display_ingredients");
    }

    private JsonObject observeGroup(JsonObject group, JsonObject displayed) {
        JsonObject result = new JsonObject();
        result.addProperty("count", number(group, "count"));
        JsonArray keys = new JsonArray();
        var choices = group.get("choices");
        if (choices != null && choices.isJsonArray()) {
            for (JsonElement choice : choices.getAsJsonArray()) {
                if (!choice.isJsonObject()) continue;
                String key = observe(choice.getAsJsonObject(), displayed);
                if (!key.isEmpty()) keys.add(key);
            }
        }
        result.add("keys", keys);
        return result;
    }

    private String observe(JsonObject source, JsonObject displayed) {
        String id = string(source, "item"), snbt = string(source, "snbt");
        JsonObject observed = item(id, snbt);
        if (observed == null) return "";
        String key =
                UUID.nameUUIDFromBytes((id + "\0" + snbt).getBytes(StandardCharsets.UTF_8))
                        .toString();
        stacks.putIfAbsent(key, observed);
        if (displayed.size() < 48 && displayed.toString().length() < 12_000) {
            JsonObject model = observed.deepCopy();
            model.remove("name");
            displayed.add(key, model);
        }
        return key;
    }

    String unverifiedReferences(String answer) {
        var missing = new java.util.LinkedHashSet<String>();
        var match = REFERENCE.matcher(answer);
        while (match.find() && missing.size() < 8) {
            String key = match.group(2);
            boolean verified =
                    switch (match.group(1)) {
                        case "item" -> itemNames.apply(key) != null;
                        case "stack" -> stacks.containsKey(key);
                        default -> recipes.containsKey(key);
                    };
            if (!verified) missing.add(match.group());
        }
        return String.join("、", missing);
    }

    JsonObject streamItem(String marker) {
        var match = REFERENCE.matcher(marker);
        if (!match.matches()) return null;
        return switch (match.group(1)) {
            case "item" -> item(match.group(2), "");
            case "stack" -> stacks.get(match.group(2));
            default -> null;
        };
    }

    String answerText(String answer) {
        return project(answer).plain.toString().strip();
    }

    private static final int MAX_RECIPE_CARDS = 4;

    JsonObject render(String answer) {
        Projection projection = project(answer);
        JsonObject reply = new JsonObject();
        if (projection.inline) reply.add("text", projection.parts);
        else reply.addProperty("text", projection.plain.toString().strip());
        JsonArray cards = new JsonArray();
        var addedIds = new LinkedHashSet<String>();
        boolean hasAlternatives = false;
        if (deterministicCard && !preflightRecipe.isEmpty()) {
            JsonObject card = buildRecipeCard(projection.items, preflightRecipe);
            if (card != null) {
                addedIds.add(preflightRecipe);
                if (card.has("slotAlternatives")) hasAlternatives = true;
                cards.add(card);
            }
        }
        var ref = REFERENCE.matcher(answer);
        while (ref.find() && cards.size() < MAX_RECIPE_CARDS) {
            if (!ref.group(1).equals("recipe")) continue;
            String recipeId = ref.group(2);
            if (!addedIds.add(recipeId)) continue;
            JsonObject card = buildRecipeCard(projection.items, recipeId);
            if (card != null) {
                if (card.has("slotAlternatives")) hasAlternatives = true;
                cards.add(card);
            }
        }
        if (cards.size() > 0) {
            reply.add("recipe", cards.get(0));
            if (cards.size() > 1) reply.add("recipes", cards);
            if (hasAlternatives) reply.addProperty("note", "图中每格展示一种已核实的材料。部分槽位也接受其他候选材料。");
        }
        if (projection.items.size() > 0) reply.add("items", projection.items);
        return reply;
    }

    private Projection project(String answer) {
        String source = safeLength(CONTROL.matcher(answer).replaceAll("").strip());
        var matcher = REFERENCE.matcher(source);
        Projection result = new Projection();
        int previous = 0;
        int itemBytes = 0;
        while (matcher.find()) {
            String segment = source.substring(previous, matcher.start());
            previous = matcher.end();
            String kind = matcher.group(1), key = matcher.group(2);
            if (kind.equals("recipe")) {
                // Trim dangling horizontal spaces right before a stripped recipe marker so empty
                // indented lines don't remain.
                segment = segment.replaceAll("[ \\t]+$", "");
                result.text(segment);
                continue;
            }
            result.text(segment);
            JsonObject item = kind.equals("stack") ? localized(stacks.get(key)) : item(key, "");
            if (item == null) {
                if (kind.equals("item")) result.text(key);
                continue;
            }
            String name = string(item, "name");
            String localKey = kind.equals("stack") ? "stack-" + key : "item-" + key;
            int size =
                    result.items.has(localKey)
                            ? 0
                            : localKey.length()
                                    + 4
                                    + item.toString().getBytes(StandardCharsets.UTF_8).length;
            if (itemBytes + size > INLINE_ITEM_BYTES) {
                result.text(name);
                continue;
            }
            itemBytes += size;
            JsonObject token = new JsonObject();
            token.addProperty("item", localKey);
            result.parts.add(token);
            result.items.add(localKey, item);
            result.plain.append(name);
            result.inline = true;
        }
        result.text(source.substring(previous));
        return result;
    }

    private JsonObject buildRecipeCard(JsonObject items, String id) {
        JsonObject data = recipes.get(id);
        if (data == null || !data.has("display_slots")) return null;
        String serializer = string(data, "serializer");
        boolean shaped = serializer.equals("minecraft:crafting_shaped");
        if (!shaped && !serializer.equals("minecraft:crafting_shapeless")) return null;
        String outputKey = string(data, "display_output");
        JsonObject output = stacks.get(outputKey);
        long count = number(object(data, "result"), "count");
        if (output == null || count < 1 || count > Integer.MAX_VALUE) return null;
        JsonArray ingredients = data.getAsJsonArray("display_slots");
        int width = shaped ? (int) number(data, "width") : Math.min(3, ingredients.size());
        int height = shaped ? (int) number(data, "height") : (ingredients.size() + 2) / 3;
        if (width < 1
                || width > 3
                || height < 1
                || height > 3
                || ingredients.size() > 9
                || (shaped && ingredients.size() != width * height)) return null;
        JsonArray slots = new JsonArray();
        JsonObject cardItems = new JsonObject();
        JsonObject alternatives = new JsonObject();
        for (JsonElement ingredient : ingredients) {
            if (ingredient.isJsonNull()) {
                if (!shaped) return null;
                slots.add(JsonNull.INSTANCE);
                continue;
            }
            JsonObject group = ingredient.getAsJsonObject();
            JsonArray keys = group.getAsJsonArray("keys");
            if (keys.size() == 0) return null;
            String key = keys.get(0).getAsString();
            JsonObject item = stacks.get(key);
            if (item == null) return null;
            if (number(group, "count") > 1) {
                JsonArray linked = new JsonArray();
                for (JsonElement choice : keys) {
                    String choiceKey = choice.getAsString();
                    linked.add("stack-" + choiceKey);
                    cardItems.add("stack-" + choiceKey, localized(stacks.get(choiceKey)));
                }
                alternatives.add(Integer.toString(slots.size()), linked);
            }
            slots.add("stack-" + key);
            cardItems.add("stack-" + key, localized(item));
        }
        cardItems.add("stack-" + outputKey, localized(output));
        cardItems.entrySet().forEach(entry -> items.add(entry.getKey(), entry.getValue()));
        JsonObject recipe = new JsonObject();
        recipe.addProperty("columns", width);
        recipe.add("slots", slots);
        recipe.addProperty("output", "stack-" + outputKey);
        recipe.addProperty("count", count);
        recipe.addProperty("shapeless", !shaped);
        if (alternatives.size() > 0) recipe.add("slotAlternatives", alternatives);
        return recipe;
    }

    private JsonObject localized(JsonObject stack) {
        if (stack == null) return null;
        String name = itemNames.apply(string(stack, "id"));
        if (name == null || name.equals(string(stack, "name"))) return stack;
        JsonObject result = stack.deepCopy();
        result.addProperty("name", name);
        return result;
    }

    private JsonObject item(String id, String snbt) {
        if (id.length() > 240
                || !VALID_RESOURCE.matcher(id).matches()
                || id.contains("..")
                || snbt.getBytes(StandardCharsets.UTF_8).length > 2048) return null;
        String name = itemNames.apply(id);
        if (name == null) return null;
        JsonObject item = new JsonObject();
        item.addProperty("id", id);
        item.addProperty("name", name);
        if (!snbt.isEmpty()) item.addProperty("snbt", snbt);
        return item;
    }

    private static String safeLength(String text) {
        int end = 0, bytes = 0;
        while (end < text.length()) {
            int code = text.codePointAt(end);
            bytes += code <= 0x7f ? 1 : code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4;
            if (bytes > 8000) break;
            end += Character.charCount(code);
        }
        return text.substring(0, end);
    }

    private static final class Projection {
        final StringBuilder plain = new StringBuilder();
        final JsonArray parts = new JsonArray();
        final JsonObject items = new JsonObject();
        boolean inline;

        void text(String text) {
            if (text.isEmpty()) return;
            parts.add(text);
            plain.append(text);
        }
    }
}

package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

final class WzReply {
    private static final String RESOURCE_ID = "[a-z0-9_.-]+:[a-z0-9_./-]+";
    private static final Pattern REFERENCE =
            Pattern.compile("\\[\\[(recipe|item|stack):(" + RESOURCE_ID + "|[a-z0-9-]{36})]]");
    private static final Pattern VALID_RESOURCE = Pattern.compile(RESOURCE_ID);
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");
    private static final int MAX_INLINE = 32;
    private final Map<String, JsonObject> recipes = new HashMap<>();
    private final Map<String, JsonObject> stacks = new HashMap<>();
    private final Function<String, String> itemNames;

    WzReply(Function<String, String> itemNames) {
        this.itemNames = itemNames;
    }

    void collect(String mode, String kind, String id, JsonObject evidence) {
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

    String answerText(String answer) {
        return project(answer).plain.toString().strip();
    }

    JsonObject render(String answer) {
        Projection projection = project(answer);
        JsonObject reply = new JsonObject();
        if (projection.inline) reply.add("text", projection.parts);
        else reply.addProperty("text", projection.plain.toString().strip());
        var ref = REFERENCE.matcher(answer);
        while (ref.find()) {
            if (!ref.group(1).equals("recipe")) continue;
            if (addRecipe(reply, projection.items, ref.group(2))) break;
        }
        if (projection.items.size() > 0) reply.add("items", projection.items);
        return reply;
    }

    private Projection project(String answer) {
        String source = safeLength(CONTROL.matcher(answer).replaceAll("").strip());
        var matcher = REFERENCE.matcher(source);
        Projection result = new Projection();
        int previous = 0;
        int inline = 0;
        int itemBytes = 0;
        while (matcher.find()) {
            result.text(source.substring(previous, matcher.start()));
            previous = matcher.end();
            String kind = matcher.group(1), key = matcher.group(2);
            if (kind.equals("recipe")) continue;
            JsonObject item = kind.equals("stack") ? stacks.get(key) : item(key, "");
            if (item == null) {
                if (kind.equals("item")) result.text(key);
                continue;
            }
            String name = string(item, "name");
            String localKey = kind.equals("stack") ? "stack-" + key : "item-" + key;
            int size =
                    result.items.has(localKey)
                            ? 0
                            : item.toString().getBytes(StandardCharsets.UTF_8).length;
            if (inline++ >= MAX_INLINE || itemBytes + size > 6500) {
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

    private boolean addRecipe(JsonObject reply, JsonObject items, String id) {
        JsonObject data = recipes.get(id);
        if (data == null || !data.has("display_slots")) return false;
        String serializer = string(data, "serializer");
        boolean shaped = serializer.equals("minecraft:crafting_shaped");
        if (!shaped && !serializer.equals("minecraft:crafting_shapeless")) return false;
        String outputKey = string(data, "display_output");
        JsonObject output = stacks.get(outputKey);
        long count = number(object(data, "result"), "count");
        if (output == null || count < 1 || count > Integer.MAX_VALUE) return false;
        JsonArray ingredients = data.getAsJsonArray("display_slots");
        int width = shaped ? (int) number(data, "width") : Math.min(3, ingredients.size());
        int height = shaped ? (int) number(data, "height") : (ingredients.size() + 2) / 3;
        if (width < 1
                || width > 3
                || height < 1
                || height > 3
                || ingredients.size() > 9
                || (shaped && ingredients.size() != width * height)) return false;
        JsonArray slots = new JsonArray();
        JsonObject cardItems = new JsonObject();
        JsonObject alternatives = new JsonObject();
        for (JsonElement ingredient : ingredients) {
            if (ingredient.isJsonNull()) {
                if (!shaped) return false;
                slots.add(JsonNull.INSTANCE);
                continue;
            }
            JsonObject group = ingredient.getAsJsonObject();
            JsonArray keys = group.getAsJsonArray("keys");
            if (keys.size() == 0) return false;
            String key = keys.get(0).getAsString();
            JsonObject item = stacks.get(key);
            if (item == null) return false;
            if (number(group, "count") > 1) {
                JsonArray linked = new JsonArray();
                for (JsonElement choice : keys) {
                    String choiceKey = choice.getAsString();
                    linked.add("stack-" + choiceKey);
                    cardItems.add("stack-" + choiceKey, stacks.get(choiceKey));
                }
                alternatives.add(Integer.toString(slots.size()), linked);
            }
            slots.add("stack-" + key);
            cardItems.add("stack-" + key, item);
        }
        cardItems.add("stack-" + outputKey, output);
        cardItems.entrySet().forEach(entry -> items.add(entry.getKey(), entry.getValue()));
        JsonObject recipe = new JsonObject();
        recipe.addProperty("columns", width);
        recipe.add("slots", slots);
        recipe.addProperty("output", "stack-" + outputKey);
        recipe.addProperty("count", count);
        recipe.addProperty("shapeless", !shaped);
        if (alternatives.size() > 0) {
            recipe.add("slotAlternatives", alternatives);
            reply.addProperty("note", "图中每格展示一种已核实的材料。部分槽位也接受其他候选材料。");
        }
        reply.add("recipe", recipe);
        return true;
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

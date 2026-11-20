package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Pattern;

/** Structured, read-only game-data lookup used by the server model host. */
public final class WenzhouReadTools {
    private static final int PAGE_SIZE = 32;
    private static final Pattern ID =
            Pattern.compile("[a-z0-9_-][a-z0-9_.-]*:[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*");

    private WenzhouReadTools() {}

    /** The model host supplies structured arguments, never a shell command. */
    public static CompletableFuture<JsonObject> query(
            MinecraftServer server, String mode, String kind, String name, int page) {
        return query(server, mode, kind, name, page, "");
    }

    static CompletableFuture<JsonObject> query(MinecraftServer server, String mode, String kind,
                                                String name, int page, String hints) {
        if ("read".equals(mode) && "command".equals(kind))
            return WenzhouCommands.run(server, name)
                    .exceptionally(ex -> message("unavailable", "这次只读命令未完成。"));
        if ("live".equals(mode) && "find_item".equals(kind)) {
            if (page != 1 || !WzItemIndex.valid(name))
                return CompletableFuture.completedFuture(message("invalid_request", "请输入有效的物品名称或注册 ID，页码使用 1。"));
            return WzRegisteredItems.index(server).thenComposeAsync(catalog -> {
                JsonObject result = catalog.index().find(name);
                result.addProperty("unreadable_language_files", catalog.unreadableLanguageFiles());
                if (!result.has("unique_item")) return CompletableFuture.completedFuture(result);
                ResourceLocation item = ResourceLocation.tryParse(result.get("unique_item").getAsString());
                return server.submit(() -> {
                    JsonObject recipes = recipesForItem(server.getRecipeManager(), server.registryAccess(), item, 1);
                    result.add("fixed_output_recipes", recipes);
                    return result;
                });
            }, net.minecraft.Util.backgroundExecutor()).exceptionally(ex -> message("unavailable", "本次物品索引未能完成；已有确切 ID 时可直接查配方。"));
        }
        if ("live".equals(mode) && "locate_gameplay".equals(kind))
            return WenzhouCommands.locate(name, page, hints.equals("@interactions"));
        if ("live".equals(mode) && "read_method".equals(kind))
            return WenzhouCommands.method(name, page, hints);
        if ("live".equals(mode) && ("search_tags".equals(kind) || "item_tags".equals(kind)))
            return server.submit(() -> WzItemEvidence.tags(kind, name, page, hints));
        if ("live".equals(mode) && "item_overview".equals(kind))
            return server.submit(() -> WzItemEvidence.overview(server.getRecipeManager(), server.registryAccess(), name))
                    .exceptionally(ex -> message("unavailable", "本次预读未完成，可按物品 ID 单独查阅。"));
        ResourceLocation id = resourceId(name);
        if (id == null || page < 1)
            return CompletableFuture.completedFuture(message("invalid_request", "资源标识或页码无效。"));
        if ("live".equals(mode) && "pack_changes".equals(kind))
            return server.submit(() -> WzPackChanges.query(server, id, page)).thenCompose(future -> future);
        return server.submit(
                        () -> {
                            if ("recipe".equals(kind) && page == 1) {
                                if ("mod".equals(mode)) {
                                    return WenzhouRecipes.inspect(server, id);
                                }
                                if ("live".equals(mode)) {
                                    return CompletableFuture.completedFuture(
                                            WenzhouRecipes.live(
                                                    server.getRecipeManager(),
                                                    server.registryAccess(),
                                                    id));
                                }
                            }
                            if ("live".equals(mode) && ("loot_sources".equals(kind) || "loot_table".equals(kind))) {
                                return WzLoot.query(server, kind, id, page);
                            }
                            if ("live".equals(mode) && "food_effects".equals(kind) && page == 1) {
                                return CompletableFuture.completedFuture(foodEffects(id));
                            }
                            if ("live".equals(mode) && "tag".equals(kind)) {
                                return CompletableFuture.completedFuture(tag(id, page, hints));
                            }
                            if ("live".equals(mode) && "recipes_for_item".equals(kind)) {
                                return CompletableFuture.completedFuture(
                                        recipesForItem(
                                                server.getRecipeManager(),
                                                server.registryAccess(),
                                                id,
                                                page));
                            }
                            return CompletableFuture.completedFuture(
                                    message("unsupported", "没有这个只读查询。"));
                        })
                .thenCompose(result -> result)
                .exceptionally(ex -> message("unavailable", "此次读取未完成，无法核实该条目。"));
    }

    static ResourceLocation resourceId(String name) {
        if (name == null
                || name.length() > 240
                || !ID.matcher(name).matches()
                || name.contains("..")
                || name.endsWith("/.")
                || name.contains("/./")
                || name.contains(":.")) return null;
        return ResourceLocation.tryParse(name);
    }

    static String itemDisplayName(String name) {
        ResourceLocation id = resourceId(name);
        if (id == null) return null;
        return BuiltInRegistries.ITEM
                .getOptional(id)
                .map(item -> item.getDescription().getString())
                .orElse(null);
    }

    static JsonObject recipesForItem(
            RecipeManager manager, RegistryAccess registries, ResourceLocation id, int page) {
        var item = BuiltInRegistries.ITEM.getOptional(id);
        if (item.isEmpty()) return message("not_registered", "这个物品 ID 未注册。");
        var matches = new ArrayList<String>();
        int unreadable = 0;
        for (var recipe : manager.getRecipes()) {
            try {
                var output = recipe.getResultItem(registries);
                if (!output.isEmpty() && output.is(item.get()))
                    matches.add(recipe.getId().toString());
            } catch (RuntimeException e) {
                // Custom recipes can lack a static output; the remaining recipes still count.
                unreadable++;
            }
        }
        matches.sort(Comparator.naturalOrder());
        long start = (long) (page - 1) * PAGE_SIZE;
        if (page < 1 || (page > 1 && start >= matches.size()))
            return message("invalid_page", "页码超出配方范围。");
        JsonArray ids = new JsonArray();
        for (int i = (int) start; i < Math.min(start + PAGE_SIZE, matches.size()); i++)
            ids.add(matches.get(i));
        JsonObject result = message("live", "仅包含声明此固定产物的已加载配方；其他获取方式要另行核实。");
        result.addProperty("item", id.toString());
        result.addProperty("page", page);
        result.addProperty("total", matches.size());
        result.addProperty("unreadable", unreadable);
        result.add("recipe_ids", ids);
        return result;
    }

    static JsonObject foodEffects(ResourceLocation id) {
        var item = BuiltInRegistries.ITEM.getOptional(id);
        if (item.isEmpty()) return message("not_registered", "该物品未注册。");
        var stack = item.get().getDefaultInstance();
        var food = stack.getFoodProperties(null);
        JsonObject result = message("live", "本服默认物品栈、无特定食用者的食物属性；自定义食用代码和事件可能另有作用。");
        result.addProperty("item", id.toString());
        result.addProperty("has_food_properties", food != null);
        if (food != null) {
            result.addProperty("nutrition", food.getNutrition());
            result.addProperty("saturation_modifier", food.getSaturationModifier());
            result.addProperty("always_edible", food.canAlwaysEat());
            JsonArray effects = new JsonArray();
            for (var pair : food.getEffects()) {
                var effect = pair.getFirst();
                if (effect == null) continue;
                JsonObject row = new JsonObject();
                row.addProperty("effect", BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect()).toString());
                row.addProperty("level", effect.getAmplifier() + 1);
                row.addProperty("duration_ticks", effect.getDuration());
                row.addProperty("infinite", effect.isInfiniteDuration());
                row.addProperty("chance", pair.getSecond());
                effects.add(row);
            }
            result.add("effects", effects);
        }
        return result;
    }

    private static JsonObject tag(ResourceLocation id, int page, String hints) {
        var members = BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, id));
        if (members.isEmpty()) {
            JsonObject result = message("not_active", "精确标签未注册；候选只代表名称相近。请按代码里的实际条件选用。");
            result.add("suggestions", WzItemEvidence.relatedTags(id, page, hints));
            return result;
        }
        var entries = members.get();
        long start = (long) (page - 1) * PAGE_SIZE;
        if (page > 1 && start >= entries.size()) {
            return message("invalid_page", "页码超出当前标签范围。");
        }
        JsonArray items = new JsonArray();
        for (int i = (int) start; i < Math.min(start + PAGE_SIZE, entries.size()); i++) {
            items.add(BuiltInRegistries.ITEM.getKey(entries.get(i).value()).toString());
        }
        JsonObject result = new JsonObject();
        result.addProperty("status", "live");
        result.addProperty("tag", id.toString());
        result.addProperty("page", page);
        result.addProperty("total", entries.size());
        result.add("items", items);
        return result;
    }

    static JsonObject message(String status, String text) {
        JsonObject result = new JsonObject();
        result.addProperty("status", status);
        result.addProperty("message", text);
        return result;
    }
}

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
                    var ids = recipes.getAsJsonArray("recipe_ids");
                    if (ids != null && ids.size() == 1 && recipes.get("total").getAsInt() == 1) {
                        var recipeId = ResourceLocation.tryParse(ids.get(0).getAsString());
                        try {
                            var detail = WenzhouRecipes.live(server.getRecipeManager(), server.registryAccess(), recipeId);
                            if ("live".equals(WzData.string(detail, "status")) && detail.toString().length() <= 3100)
                                result.add("recipe_detail", detail);
                        } catch (RuntimeException unsupported) {
                            // A mod's dynamic ingredient must not prevent returning the successful item lookup.
                        }
                    }
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
            return server.submit(() -> WzPackChanges.query(server, id, page, hints.equals("@loot"))).thenCompose(future -> future);
        return server.submit(
                        () -> {
                            if ("live".equals(mode)) {
                                if ("entity_spawns".equals(kind)) return WzSpawnEvidence.query(server, id, page);
                                if ("biome_tag".equals(kind)) return CompletableFuture.completedFuture(
                                        WzSpawnEvidence.biomeTag(server.registryAccess().registryOrThrow(Registries.BIOME), id, page));
                                if ("machine_conversions".equals(kind)) return CompletableFuture.completedFuture(
                                        WzMachineRecipes.conversions(server.getRecipeManager(), server.registryAccess(), id, page));
                            }
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
                                return WzLoot.withPackChanges(server, kind, id, page);
                            }
                            if ("live".equals(mode) && "food_effects".equals(kind) && page == 1) {
                                return CompletableFuture.completedFuture(foodEffects(id));
                            }
                            if ("live".equals(mode) && "tag".equals(kind)) {
                                return CompletableFuture.completedFuture(tag(id, page, hints));
                            }
                            if ("live".equals(mode) && ("recipes_for_item".equals(kind) || "recipes_using_item".equals(kind))) {
                                return CompletableFuture.completedFuture(
                                        WzRecipeIndex.get(server.getRecipeManager(), server.registryAccess())
                                                .page(id, page, kind.equals("recipes_using_item")));
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

    public static void refreshRecipeIndex(MinecraftServer server) {
        WzPackChanges.clear();
        WzRecipeIndex.clear();
        WzRecipeIndex.get(server.getRecipeManager(), server.registryAccess());
    }

    public static void clearRecipeIndex() {
        WzPackChanges.clear();
        WzRecipeIndex.clear();
    }

    static String itemDisplayName(String name) {
        ResourceLocation id = resourceId(name);
        if (id == null) return null;
        return BuiltInRegistries.ITEM
                .getOptional(id)
                .map(item -> {
                    try {
                        return net.minecraft.ChatFormatting.stripFormatting(item.getDefaultInstance().getHoverName().getString());
                    } catch (RuntimeException unusualStack) {
                        return net.minecraft.ChatFormatting.stripFormatting(item.getDescription().getString());
                    }
                })
                .orElse(null);
    }

    static JsonObject recipesForItem(
            RecipeManager manager, RegistryAccess registries, ResourceLocation id, int page) {
        return WzRecipeIndex.get(manager, registries).page(id, page, false);
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

    static JsonObject entriesPage(JsonArray rows, int page, String description) {
        final int pageSize = 12;
        long start = (page - 1L) * pageSize;
        if (page < 1 || page > 1 && start >= rows.size()) return WenzhouReadTools.message("invalid_page", "页码超出范围。");
        var result = WenzhouReadTools.message("live", description);
        JsonArray selected = new JsonArray();
        for (int i = (int) start; i < Math.min(rows.size(), start + pageSize); i++) selected.add(rows.get(i));
        result.add("entries", selected);
        result.addProperty("total", rows.size());
        result.addProperty("more", start + pageSize < rows.size());
        return result;
    }

    static JsonObject message(String status, String text) {
        JsonObject result = new JsonObject();
        result.addProperty("status", status);
        result.addProperty("message", text);
        return result;
    }
}

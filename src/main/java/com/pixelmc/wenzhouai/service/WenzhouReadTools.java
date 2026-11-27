package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
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

    static CompletableFuture<JsonObject> query(
            MinecraftServer server, String mode, String kind, String name, int page, String hints) {
        return query(server, mode, kind, name, page, hints, null);
    }

    static CompletableFuture<JsonObject> query(
            MinecraftServer server,
            String mode,
            String kind,
            String name,
            int page,
            String hints,
            java.util.UUID requester) {
        return query(server, mode, kind, name, page, hints, requester, new WzPackContext(""));
    }

    static CompletableFuture<JsonObject> query(
            MinecraftServer server,
            String mode,
            String kind,
            String name,
            int page,
            String hints,
            java.util.UUID requester,
            WzPackContext pack) {
        return lookup(server, mode, kind, name, page, hints, requester)
                .thenCompose(result -> pack.check(server, kind, name, hints, result));
    }

    private static CompletableFuture<JsonObject> lookup(
            MinecraftServer server,
            String mode,
            String kind,
            String name,
            int page,
            String hints,
            java.util.UUID requester) {
        if ("live".equals(mode) && WzManagerGameplay.TOOLS.contains(kind))
            return WzManagerGameplay.read(requester, kind, name, page, hints);
        if ("read".equals(mode) && "command".equals(kind))
            return WenzhouCommands.run(server, name)
                    .exceptionally(ex -> message("unavailable", "这次只读命令未完成。"));
        if ("live".equals(mode) && ("find_item".equals(kind) || "find_entity".equals(kind))) {
            if (page != 1 || !WzItemIndex.valid(name))
                return CompletableFuture.completedFuture(
                        message("invalid_request", "请输入有效的名称或注册 ID，页码使用 1。"));
            return WzRegisteredItems.index(server)
                    .thenComposeAsync(
                            catalog -> {
                                JsonObject result = catalog.resolveName(kind, name);
                                if (result.has("entity_matches"))
                                    return CompletableFuture.completedFuture(result);
                                result.addProperty(
                                        "unreadable_language_files",
                                        catalog.unreadableLanguageFiles());
                                if (WzData.number(result, "total") == 0
                                        && WzFoodBook.namesBook(name))
                                    return WzManagerGameplay.read(
                                                    requester, "food_support", "*", 1, "")
                                            .thenApply(
                                                    support -> {
                                                        if ("mod_not_installed"
                                                                .equals(
                                                                        WzData.string(
                                                                                support, "status")))
                                                            result.addProperty(
                                                                    "missing_mod", "solcarrot");
                                                        return result;
                                                    });
                                if (!result.has("unique_item"))
                                    return CompletableFuture.completedFuture(result);
                                ResourceLocation item =
                                        ResourceLocation.tryParse(
                                                result.get("unique_item").getAsString());
                                return server.submit(
                                        () -> {
                                            JsonObject recipes =
                                                    recipesForItem(
                                                            server.getRecipeManager(),
                                                            server.registryAccess(),
                                                            item,
                                                            1);
                                            result.add("fixed_output_recipes", recipes);
                                            var ids = recipes.getAsJsonArray("recipe_ids");
                                            if (ids != null
                                                    && ids.size() == 1
                                                    && recipes.get("total").getAsInt() == 1) {
                                                var recipeId =
                                                        ResourceLocation.tryParse(
                                                                ids.get(0).getAsString());
                                                try {
                                                    var detail =
                                                            WenzhouRecipes.live(
                                                                    server.getRecipeManager(),
                                                                    server.registryAccess(),
                                                                    recipeId);
                                                    if ("live"
                                                                    .equals(
                                                                            WzData.string(
                                                                                    detail,
                                                                                    "status"))
                                                            && detail.toString().length() <= 3100)
                                                        result.add("recipe_detail", detail);
                                                } catch (RuntimeException unsupported) {
                                                    // A mod's dynamic ingredient must not prevent
                                                    // returning the successful item lookup.
                                                }
                                            }
                                            return result;
                                        });
                            },
                            net.minecraft.Util.backgroundExecutor())
                    .exceptionally(ex -> message("unavailable", "本次名称索引未能完成，可用已核实的注册 ID 继续查阅。"));
        }
        if ("live".equals(mode) && "locate_gameplay".equals(kind))
            return WenzhouCommands.locate(name, page, hints.equals("@interactions"));
        if ("live".equals(mode) && "read_method".equals(kind))
            return WenzhouCommands.method(name, page, hints);
        if ("live".equals(mode) && ("search_tags".equals(kind) || "item_tags".equals(kind)))
            return server.submit(() -> WzItemEvidence.tags(kind, name, page, hints));
        if ("live".equals(mode) && "item_overview".equals(kind))
            return server.submit(
                            () ->
                                    WzItemEvidence.overview(
                                            server.getRecipeManager(),
                                            server.registryAccess(),
                                            name))
                    .exceptionally(ex -> message("unavailable", "本次预读未完成，可按物品 ID 单独查阅。"));
        ResourceLocation id = resourceId(name);
        if (id == null || page < 1)
            return CompletableFuture.completedFuture(message("invalid_request", "资源标识或页码无效。"));
        if ("live".equals(mode) && "item_sources".equals(kind)) {
            if (page != 1)
                return CompletableFuture.completedFuture(
                        message("invalid_page", "item_sources 仅支持第 1 页；如需翻看某项来源的后续页，请单独调用对应工具。"));
            return itemSources(server, id, requester);
        }
        if ("live".equals(mode) && "pack_changes".equals(kind))
            return server.submit(() -> WzPackChanges.query(server, id, page, hints.equals("@loot")))
                    .thenCompose(future -> future);
        return server.submit(
                        () -> {
                            if ("live".equals(mode)) {
                                if ("worldgen_sources".equals(kind))
                                    return WzWorldgenSources.query(server, id, page);
                                if ("entity_spawns".equals(kind))
                                    return WzSpawnEvidence.query(server, id, page);
                                if ("biome_tag".equals(kind))
                                    return CompletableFuture.completedFuture(
                                            WzSpawnEvidence.biomeTag(
                                                    server.registryAccess()
                                                            .registryOrThrow(Registries.BIOME),
                                                    id,
                                                    page));
                                if ("machine_conversions".equals(kind))
                                    return CompletableFuture.completedFuture(
                                            WzMachineRecipes.conversions(
                                                    server.getRecipeManager(),
                                                    server.registryAccess(),
                                                    id,
                                                    page));
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
                            if ("live".equals(mode)
                                    && ("loot_sources".equals(kind) || "loot_table".equals(kind))) {
                                return WzLoot.withPackChanges(server, kind, id, page);
                            }
                            if ("live".equals(mode) && "food_effects".equals(kind) && page == 1) {
                                return CompletableFuture.completedFuture(foodEffects(id));
                            }
                            if ("live".equals(mode) && "tag".equals(kind)) {
                                return CompletableFuture.completedFuture(tag(id, page, hints));
                            }
                            if ("live".equals(mode)
                                    && ("recipes_for_item".equals(kind)
                                            || "recipes_using_item".equals(kind))) {
                                return CompletableFuture.completedFuture(
                                        WzRecipeIndex.get(
                                                        server.getRecipeManager(),
                                                        server.registryAccess())
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
        WzLoot.clear();
        WzWorldgenSources.clear();
        WzRecipeIndex.clear();
        WzRecipeIndex.get(server.getRecipeManager(), server.registryAccess());
    }

    public static void clearRecipeIndex() {
        WzLoot.clear();
        WzWorldgenSources.clear();
        WzRecipeIndex.clear();
    }

    static String itemDisplayName(String name) {
        ResourceLocation id = resourceId(name);
        if (id == null) return null;
        return BuiltInRegistries.ITEM
                .getOptional(id)
                .map(
                        item -> {
                            try {
                                return net.minecraft.ChatFormatting.stripFormatting(
                                        item.getDefaultInstance().getHoverName().getString());
                            } catch (RuntimeException unusualStack) {
                                return net.minecraft.ChatFormatting.stripFormatting(
                                        item.getDescription().getString());
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
                row.addProperty(
                        "effect",
                        BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect()).toString());
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
        if (page < 1 || page > 1 && start >= rows.size())
            return WenzhouReadTools.message("invalid_page", "页码超出范围。");
        var result = WenzhouReadTools.message("live", description);
        JsonArray selected = new JsonArray();
        for (int i = (int) start; i < Math.min(rows.size(), start + pageSize); i++)
            selected.add(rows.get(i));
        result.add("entries", selected);
        result.addProperty("total", rows.size());
        result.addProperty("more", start + pageSize < rows.size());
        return result;
    }

    private static CompletableFuture<JsonObject> itemSources(
            MinecraftServer server, ResourceLocation id, java.util.UUID requester) {
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            return CompletableFuture.completedFuture(
                    message(
                            "not_registered",
                            "物品未注册。item_sources 仅接受已注册的物品 ID，可先用 find_item 定位物品 ID。"));
        }
        String itemId = id.toString();
        CompletableFuture<JsonObject> recipes =
                safeBranch(
                        server.submit(
                                () -> {
                                    JsonObject found =
                                            recipesForItem(
                                                    server.getRecipeManager(),
                                                    server.registryAccess(),
                                                    id,
                                                    1);
                                    var ids = found.getAsJsonArray("recipe_ids");
                                    if (ids != null
                                            && ids.size() == 1
                                            && WzData.number(found, "total") == 1) {
                                        var recipeId =
                                                ResourceLocation.tryParse(ids.get(0).getAsString());
                                        if (recipeId != null) {
                                            try {
                                                var detail =
                                                        WenzhouRecipes.live(
                                                                server.getRecipeManager(),
                                                                server.registryAccess(),
                                                                recipeId);
                                                if ("live".equals(WzData.string(detail, "status"))
                                                        && detail.toString().length() <= 3100)
                                                    found.add("recipe_detail", detail);
                                            } catch (RuntimeException unsupported) {
                                                // Keep the recipe index even if detail
                                                // serialization fails.
                                            }
                                        }
                                    }
                                    return found;
                                }),
                        "配方查询");
        CompletableFuture<JsonObject> loot =
                safeBranch(
                        server.submit(() -> WzLoot.withPackChanges(server, "loot_sources", id, 1))
                                .thenCompose(f -> f),
                        "战利品表反查");
        CompletableFuture<JsonObject> chests =
                safeBranch(
                        WzManagerGameplay.read(requester, "chest_sources", itemId, 1, ""),
                        "结构箱子反查");
        boolean hasBlock =
                BuiltInRegistries.BLOCK.containsKey(id)
                        || BuiltInRegistries.ITEM.getOptional(id).orElse(null)
                                instanceof net.minecraft.world.item.BlockItem;
        CompletableFuture<JsonObject> worldgen =
                hasBlock
                        ? safeBranch(
                                server.submit(() -> WzWorldgenSources.query(server, id, 1))
                                        .thenCompose(f -> f),
                                "自然生成查询")
                        : CompletableFuture.completedFuture(
                                message("not_block", "该物品不对应可放置方块，跳过自然地物检索。"));
        CompletableFuture<JsonObject> shop =
                safeBranch(WzManagerGameplay.read(requester, "shop_item", itemId, 1, ""), "商店商品反查");
        CompletableFuture<JsonObject> quests =
                safeBranch(
                        WzManagerGameplay.read(requester, "quest_rewards", itemId, 1, ""),
                        "任务奖励反查");
        CompletableFuture<JsonObject> trades =
                safeBranch(
                        WzManagerGameplay.read(requester, "trade_offers", itemId, 1, ""), "商人交易反查");
        return CompletableFuture.allOf(recipes, loot, chests, worldgen, shop, quests, trades)
                .thenApply(
                        ignored -> {
                            JsonObject recipesRes = recipes.join();
                            JsonObject lootRes = loot.join();
                            JsonObject chestsRes = chests.join();
                            JsonObject worldgenRes = worldgen.join();
                            JsonObject shopRes = shop.join();
                            JsonObject questsRes = quests.join();
                            JsonObject tradesRes = trades.join();
                            JsonObject result =
                                    message(
                                            "live",
                                            "已一次性汇总配方、战利品表、结构箱子、自然生成、商店、任务奖励与商人交易；需核对具体概率请对命中的战利品表调用"
                                                + " loot_table，若上述均无结果再查 event_outputs。");
                            result.addProperty("item", itemId);
                            JsonObject counts = new JsonObject();
                            addBranchCount(counts, "recipes", recipesRes);
                            addBranchCount(counts, "loot_tables", lootRes);
                            addBranchCount(counts, "chest_sources", chestsRes);
                            addBranchCount(counts, "worldgen", worldgenRes);
                            addBranchCount(counts, "shop", shopRes);
                            addBranchCount(counts, "quests", questsRes);
                            addBranchCount(counts, "trades", tradesRes);
                            result.add("summary_counts", counts);
                            result.add("recipes", compactBranch(recipesRes));
                            result.add("loot_sources", compactBranch(lootRes));
                            result.add("chest_sources", compactBranch(chestsRes));
                            result.add("worldgen", compactBranch(worldgenRes));
                            result.add("shop", compactBranch(shopRes));
                            result.add("quests", compactBranch(questsRes));
                            result.add("trades", compactBranch(tradesRes));
                            return result;
                        });
    }

    private static CompletableFuture<JsonObject> safeBranch(
            CompletableFuture<JsonObject> future, String label) {
        return future.orTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(
                        ex -> {
                            Throwable cause =
                                    ex instanceof java.util.concurrent.CompletionException
                                                    && ex.getCause() != null
                                            ? ex.getCause()
                                            : ex;
                            if (cause instanceof java.util.concurrent.TimeoutException) {
                                JsonObject timeout = message("timeout", label + "超时，来源尚未核实。");
                                timeout.addProperty("time_limit_reached", true);
                                return timeout;
                            }
                            return message("unavailable", label + "未完成。");
                        });
    }

    private static void addBranchCount(JsonObject counts, String key, JsonObject branch) {
        String status = WzData.string(branch, "status");
        if ("live".equals(status))
            counts.addProperty(key, Math.max(0, WzData.number(branch, "total")));
        else counts.addProperty(key, status.isEmpty() ? "unavailable" : status);
    }

    private static JsonObject compactBranch(JsonObject branch) {
        if (!"live".equals(WzData.string(branch, "status"))) return branch;
        if (WzData.number(branch, "total") > 0
                || WzData.number(branch, "unreadable") > 0
                || WzData.flag(branch, "truncated")
                || WzData.flag(branch, "time_limit_reached")
                || branch.has("recipe_detail")) return branch;
        JsonObject packChanges = WzData.object(branch, "pack_changes");
        if (WzData.number(packChanges, "total") > 0
                || WzData.flag(packChanges, "more")
                || WzData.number(packChanges, "unreadable") > 0) return branch;
        JsonObject stub = new JsonObject();
        stub.addProperty("status", "live");
        stub.addProperty("total", 0);
        return stub;
    }

    static JsonObject message(String status, String text) {
        JsonObject result = new JsonObject();
        result.addProperty("status", status);
        result.addProperty("message", text);
        return result;
    }
}

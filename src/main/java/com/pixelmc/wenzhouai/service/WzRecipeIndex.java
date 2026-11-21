package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import java.util.*;

/** Built on the server thread from the live recipe instances after tags are bound. */
final class WzRecipeIndex {
    private static final int PAGE_SIZE = 32;
    private static RecipeManager owner;
    private static WzRecipeIndex current;
    private final Map<Item,List<Row>> outputs = new HashMap<>();
    private final Map<Item,List<Row>> uses = new HashMap<>();
    private final List<SmithingRecipe> smithing = new ArrayList<>();
    private final Set<Item> checkedSmithing = new HashSet<>();
    private int unreadable;

    private record Row(ResourceLocation recipe, JsonArray roles) {}

    static synchronized WzRecipeIndex get(RecipeManager recipes, RegistryAccess registries) {
        if (owner != recipes) {
            current = new WzRecipeIndex(recipes, registries);
            owner = recipes;
        }
        return current;
    }

    static synchronized void clear() {
        owner = null;
        current = null;
    }

    private WzRecipeIndex(RecipeManager recipes, RegistryAccess registries) {
        var ordered = new ArrayList<>(recipes.getRecipes());
        ordered.sort(Comparator.comparing(Recipe::getId));
        for (var recipe : ordered) {
            var products = new HashMap<Item,JsonArray>();
            var inputs = new HashMap<Item,JsonArray>();
            boolean incomplete = false;
            try {
                if (recipe.getClass() != SmithingTrimRecipe.class)
                    output(products, recipe.getResultItem(registries), "primary", null);
                if (recipe.getClass() == SmithingTransformRecipe.class || recipe.getClass() == SmithingTrimRecipe.class)
                    smithing.add((SmithingRecipe)recipe);
            } catch (RuntimeException unsupported) {
                incomplete = true;
            }
            try {
                int slot = 0;
                for (Ingredient ingredient : recipe.getIngredients()) {
                    ingredient(inputs, ingredient, "ingredient", slot++);
                }
                var extras = WzMachineRecipes.extras(recipe);
                if (extras != null) {
                    var additional = new HashMap<Item,JsonArray>();
                    for (var product : extras.products())
                        output(additional, product.item(), product.count(), product.role(), product.baseChance());
                    for (var use : extras.uses()) {
                        var data = new JsonObject();
                        data.addProperty("role", use.role());
                        data.addProperty("slot", use.slot());
                        for (var item : use.items())
                            inputs.computeIfAbsent(item, unused -> new JsonArray()).add(data);
                    }
                    products = additional;
                }
            } catch (ReflectiveOperationException | RuntimeException unsupported) {
                incomplete = true;
            }
            if (incomplete) unreadable++;
            addRows(outputs, products, recipe.getId());
            addRows(uses, inputs, recipe.getId());
        }
    }

    private static void addRows(Map<Item,List<Row>> index, Map<Item,JsonArray> entries, ResourceLocation recipe) {
        entries.forEach((item, roles) -> index.computeIfAbsent(item, unused -> new ArrayList<>()).add(new Row(recipe,roles)));
    }

    private static void output(Map<Item,JsonArray> entries, ItemStack stack, String role, Number chance) {
        if (stack.isEmpty()) return;
        output(entries, stack.getItem(), stack.getCount(), role, chance);
    }

    private static void output(Map<Item,JsonArray> entries, Item item, int count, String role, Number chance) {
        var data = new JsonObject();
        data.addProperty("role",role);
        data.addProperty("count",count);
        if (chance != null) data.addProperty("base_chance",chance);
        entries.computeIfAbsent(item,unused -> new JsonArray()).add(data);
    }

    private static void ingredient(Map<Item,JsonArray> entries, Ingredient ingredient, String role, int slot) {
        if (ingredient == Ingredient.EMPTY) return;
        var seen = new HashSet<Item>();
        var data = new JsonObject();
        data.addProperty("role",role);
        data.addProperty("slot",slot);
        for (var stack : ingredient.getItems()) {
            if (!stack.isEmpty() && seen.add(stack.getItem()))
                entries.computeIfAbsent(stack.getItem(),unused -> new JsonArray()).add(data);
        }
    }

    JsonObject page(ResourceLocation id, int page, boolean byUse) {
        var item = BuiltInRegistries.ITEM.getOptional(id);
        if (item.isEmpty()) return WenzhouReadTools.message("not_registered","这个物品 ID 未注册。");
        var matches = byUse ? uses.computeIfAbsent(item.get(),unused -> new ArrayList<>()) : outputs.getOrDefault(item.get(),List.of());
        if (byUse && checkedSmithing.add(item.get())) {
            // Vanilla smithing exposes slot predicates instead of an ingredient list.
            var stack = item.get().getDefaultInstance();
            for (var recipe : smithing) {
                var roles = new JsonArray();
                if (recipe.isTemplateIngredient(stack)) smithingRole(roles,"template",0);
                if (recipe.isBaseIngredient(stack)) smithingRole(roles,"base",1);
                if (recipe.isAdditionIngredient(stack)) smithingRole(roles,"addition",2);
                if (!roles.isEmpty()) matches.add(new Row(recipe.getId(),roles));
            }
            matches.sort(Comparator.comparing(Row::recipe));
        }
        long start = (page - 1L) * PAGE_SIZE;
        if (page < 1 || page > 1 && start >= matches.size())
            return WenzhouReadTools.message("invalid_page","页码超出配方范围。");
        var result = WenzhouReadTools.message("live", byUse
                ? "已加载配方的材料或工具候选，slot 从零起；标签替代、NBT 与工序条件请读配方详情。"
                : "已加载配方的声明产物，农夫乐事切配另列每次产出与基础概率；其他动态产出可能未列。");
        result.addProperty("item",id.toString());
        result.addProperty("page",page);
        result.addProperty("total",matches.size());
        result.addProperty("unreadable",unreadable);
        var ids = new JsonArray();
        var rows = new JsonArray();
        for (int i = (int)start; i < Math.min(start + PAGE_SIZE, matches.size()); i++) {
            Row match = matches.get(i);
            ids.add(match.recipe.toString());
            var row = new JsonObject();
            row.addProperty("recipe",match.recipe.toString());
            row.add("roles",match.roles.deepCopy());
            rows.add(row);
        }
        result.add("recipe_ids",ids);
        result.add("matches",rows);
        return result;
    }
    private static void smithingRole(JsonArray roles, String role, int slot) {
        var entry = new JsonObject();
        entry.addProperty("role",role);
        entry.addProperty("slot",slot);
        roles.add(entry);
    }
}

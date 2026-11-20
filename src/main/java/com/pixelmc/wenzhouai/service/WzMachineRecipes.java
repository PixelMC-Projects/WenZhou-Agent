package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.fml.ModList;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class WzMachineRecipes {
    private static final String IMBUEMENT = "com.hollingsworth.arsnouveau.common.crafting.recipes.ImbuementRecipe";

    record Imbuement(Ingredient input, ItemStack output, int source, List<Ingredient> pedestals) {}

    static Imbuement imbuement(Recipe<?> recipe) throws ReflectiveOperationException {
        var type = recipe.getClass();
        if (!type.getName().equals(IMBUEMENT)) return null;
        var raw = (List<?>) type.getField("pedestalItems").get(recipe);
        return new Imbuement((Ingredient) type.getField("input").get(recipe),
                (ItemStack) type.getField("output").get(recipe), type.getField("source").getInt(recipe),
                raw.stream().map(Ingredient.class::cast).toList());
    }

    static JsonObject conversions(RecipeManager manager, RegistryAccess access, ResourceLocation id, int page) {
        var target = BuiltInRegistries.ITEM.getOptional(id);
        if (target.isEmpty()) return WenzhouReadTools.message("not_registered", "此物品未注册。");
        if (!ModList.get().isLoaded("miners_delight"))
            return WenzhouReadTools.message("unsupported", "当前适配的铜锅模组未安装。");
        try {
            var type = Class.forName("com.sammy.minersdelight.logic.CupConversionReloadListener");
            var conversions = (Map<?, ?>) type.getField("BOWL_TO_CUP").get(null);
            var rows = new JsonArray();
            // Only the cooking recipe type feeds this machine. Other recipes with the same result are unrelated.
            var cooking = BuiltInRegistries.RECIPE_TYPE.getOptional(ResourceLocation.tryParse("farmersdelight:cooking"));
            if (cooking.isEmpty()) return WenzhouReadTools.message("unavailable", "当前没有注册烹饪锅配方类型。");
            for (var recipe : manager.getRecipes().stream().filter(entry -> entry.getType() == cooking.get())
                    .sorted(Comparator.comparing(Recipe::getId)).toList()) {
                var original = recipe.getResultItem(access);
                var cup = conversions.get(original.getItem());
                if (cup != target.get() || original.isEmpty()) continue;
                var row = new JsonObject();
                row.addProperty("recipe", recipe.getId().toString());
                row.addProperty("regular_output", BuiltInRegistries.ITEM.getKey(original.getItem()).toString());
                row.addProperty("regular_count", original.getCount());
                row.addProperty("cup_output", id.toString());
                var ingredients = new JsonArray();
                for (var ingredient : recipe.getIngredients()) ingredients.add(ingredient.toJson());
                if (ingredients.toString().length() <= 2500) row.add("ingredients", ingredients);
                rows.add(row);
            }
            var result = WenzhouReadTools.entriesPage(rows, page, "仅覆盖矿工乐事铜锅。匹配当前烹饪配方与已加载的杯装映射；碗装产物是原配方输出，不是投入材料。");
            result.addProperty("machine", "miners_delight:copper_pot");
            result.addProperty("serving_container", "miners_delight:copper_cup");
            return result;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException incompatible) {
            return WenzhouReadTools.message("unavailable", "当前版本的铜锅转换表未能完整读取。");
        }
    }

    private WzMachineRecipes() {}
}

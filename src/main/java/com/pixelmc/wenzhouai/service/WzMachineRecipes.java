package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonObject;
import com.pixelmc.pixelmcmanager.api.RecipeGameplayApi;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;

/** Optional Manager adapter. Generic recipe evidence is still available with older Manager builds. */
final class WzMachineRecipes {
    static RecipeGameplayApi.Extras extras(Recipe<?> recipe) throws ReflectiveOperationException {
        try {
            return RecipeGameplayApi.extras(recipe);
        } catch (LinkageError oldManager) {
            return null;
        }
    }

    static JsonObject detail(Recipe<?> recipe, RegistryAccess registries) {
        try {
            try {
                return RecipeGameplayApi.detail(recipe, registries);
            } catch (NoSuchMethodError oldManager) {
                return RecipeGameplayApi.detail(recipe);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            return null;
        }
    }

    static JsonObject conversions(RecipeManager manager, RegistryAccess access, ResourceLocation id, int page) {
        try {
            return RecipeGameplayApi.cupConversions(manager, access, id, page);
        } catch (LinkageError oldManager) {
            return WenzhouReadTools.message("unavailable", "当前 Manager 未提供设备产物转换。");
        }
    }

    private WzMachineRecipes() {}
}

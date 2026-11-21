package com.pixelmc.wenzhouai.service;

import com.google.gson.*;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.*;
import java.util.concurrent.CompletableFuture;

final class WzSpawnEvidence {

    private WzSpawnEvidence() {}

    static CompletableFuture<JsonObject> query(MinecraftServer server, ResourceLocation id, int page) {
        try {
            return com.pixelmc.pixelmcmanager.api.PackGameplayApi.entitySpawns(server, id, page);
        } catch (LinkageError oldManager) {
            return CompletableFuture.completedFuture(WenzhouReadTools.message("unavailable", "当前 Manager 未提供实体生成规则。"));
        }
    }

    static JsonObject biomeTag(Registry<Biome> registry, ResourceLocation id, int page) {
        var holders = registry.getTag(TagKey.create(Registries.BIOME, id));
        if (holders.isEmpty()) return WenzhouReadTools.message("not_active", "本服没有这个群系标签。");
        JsonArray rows = new JsonArray();
        holders.get().stream().flatMap(holder -> holder.unwrapKey().stream())
                .map(key -> key.location().toString()).sorted().forEach(rows::add);
        return WenzhouReadTools.entriesPage(rows, page, "本服群系注册表中该标签的成员。");
    }


}

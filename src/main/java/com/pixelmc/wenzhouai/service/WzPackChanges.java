package com.pixelmc.wenzhouai.service;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.concurrent.*;

final class WzPackChanges {
    private static final List<String> GROUPS = List.of("KubeJS","LootJS","CraftTweaker","数据包","掉落修饰器");

    static CompletableFuture<JsonObject> query(MinecraftServer server, ResourceLocation id, int page, boolean lootOnly) {
        try {
            return com.pixelmc.pixelmcmanager.api.PackGameplayApi.packChanges(server, id, page, lootOnly);
        } catch (LinkageError oldManager) {
            return CompletableFuture.completedFuture(WenzhouReadTools.message("unavailable", "当前 Manager 未提供整合包资料。"));
        }
    }

    static String caption(JsonObject result) {
        var parts = new StringJoiner(" · ");
        var counts = result.getAsJsonObject("counts");
        var occurrences = result.getAsJsonObject("occurrences");
        for (String group : GROUPS) if (counts.has(group)) {
            parts.add(group + " " + counts.get(group).getAsInt() + " 个文件 · " + occurrences.get(group).getAsInt() + " 处匹配");
        }
        if (parts.length() == 0) parts.add("未找到对应项");
        if (WzData.number(result,"unreadable") > 0) parts.add("部分资料未读");
        return parts.toString();
    }
    private WzPackChanges() {}
}

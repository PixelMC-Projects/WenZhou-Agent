package com.pixelmc.wenzhouai.service;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

final class WzManagerGameplay {
    static final Set<String> TOOLS =
            Set.of(
                    "food_book_stats",
                    "event_outputs",
                    "structure_generation",
                    "structure_chests",
                    "chest_sources",
                    "trade_offers",
                    "merchant_offers",
                    "quest_search",
                    "quest_detail",
                    "quest_rewards",
                    "shop_balance",
                    "shop_categories",
                    "shop_entries",
                    "shop_detail",
                    "shop_quote",
                    "shop_item");

    static CompletableFuture<JsonObject> read(
            UUID sender, String kind, String target, int page, String options) {
        var args = new JsonObject();
        args.addProperty("target", target);
        args.addProperty("page", page);
        if (options.equals("@global")) args.addProperty("view", "global");
        else if (options.startsWith("{")) {
            var opts = JsonParser.parseString(options).getAsJsonObject();
            for (String k : List.of("view", "quantity")) if (opts.has(k)) args.add(k, opts.get(k));
        }
        try {
            return com.pixelmc.pixelmcmanager.api.PlayerGameplayApi.request(
                            sender, kind, args.toString())
                    .thenApply(s -> JsonParser.parseString(s).getAsJsonObject());
        } catch (LinkageError old) {
            return CompletableFuture.completedFuture(
                    WenzhouReadTools.message("unavailable", "当前服务端 Manager 尚未提供 gameplay.read。"));
        }
    }

    private WzManagerGameplay() {}
}

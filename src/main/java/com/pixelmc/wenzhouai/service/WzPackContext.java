package com.pixelmc.wenzhouai.service;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;

/** Correlate code candidates with pack settings and server scripts before they reach the model. */
final class WzPackContext {
    private static final Pattern ID = Pattern.compile("(?<![a-z0-9_.-])#?([a-z0-9_.-]+:[a-z0-9_./-]+)");
    private static final Pattern INLINE = Pattern.compile("\\[\\[item:([a-z0-9_.-]+:[a-z0-9_./-]+)]]");
    private static final Set<String> PARTIAL = Set.of("worldgen_sources", "entity_spawns", "food_effects",
            "locate_gameplay", "read_method", "loot_sources", "loot_table", "trade_offers", "structure_chests", "chest_sources");
    private static final Map<String,Pattern> EVENTS = Map.of(
            "trade", Pattern.compile("trade|merchant|villager|wandering|交易|商人|村民",Pattern.CASE_INSENSITIVE),
            "loot", Pattern.compile("loot|drop|掉落|战利品",Pattern.CASE_INSENSITIVE),
            "interact", Pattern.compile("interact|clicked|rightclick|useon|交互|右键",Pattern.CASE_INSENSITIVE),
            "worldgen", Pattern.compile("worldgen|biomemodifier|placedfeature|装饰器|地物|群系"),
            "food", Pattern.compile("finishusing|foodeaten|foodproperties|食用|吃下",Pattern.CASE_INSENSITIVE),
            "spawn", Pattern.compile("spawn|刷怪|刷新生物",Pattern.CASE_INSENSITIVE));
    private final Set<String> subjects = new LinkedHashSet<>();
    private final String question;

    WzPackContext(String question) {
        this.question = question;
        INLINE.matcher(question).results().forEach(m -> subjects.add(m.group(1)));
    }

    CompletableFuture<JsonObject> check(MinecraftServer server, String kind, String target, String hint, JsonObject result) {
        String unique = WzData.string(result, "unique_item");
        if (!unique.isEmpty()) subjects.add(unique);
        if (kind.equals("item_overview") && result.has("items"))
            for (var item : result.getAsJsonArray("items")) subjects.add(WzData.string(item.getAsJsonObject(),"item"));
        boolean command = kind.equals("command") && (target.startsWith("classes ") || target.startsWith("javap ") || target.startsWith("shell "));
        if (!PARTIAL.contains(kind) && !command) return CompletableFuture.completedFuture(result);
        String status = WzData.string(result,"status");
        if (!Set.of("live","installed_code","sandbox_output","not_found").contains(status)) return CompletableFuture.completedFuture(result);
        var ids = new LinkedHashSet<>(subjects);
        if (WenzhouReadTools.resourceId(target) != null) ids.add(target);
        if (command) ID.matcher(target).results().limit(24).forEach(m -> ids.add(m.group(1)));
        var rows = result.getAsJsonArray("entries");
        if (rows != null) for (var entry : rows) {
            if (!entry.isJsonObject()) continue;
            for (String key : List.of("block","configured_feature","placed_feature","biome")) {
                String value = WzData.string(entry.getAsJsonObject(),key);
                if (WenzhouReadTools.resourceId(value) != null) ids.add(value);
            }
        }
        if (result.has("tables")) for (var row : result.getAsJsonArray("tables")) ids.add(WzData.string(row.getAsJsonObject(),"table"));
        var mods = new TreeSet<String>();
        String owner = target.split("[: ]", 2)[0];
        if (target.contains(":") && owner.matches("[a-z][a-z0-9_]{0,63}")) mods.add(owner);
        if (target.startsWith("classes ") || target.startsWith("javap ")) {
            String[] parts = target.split(" +", 3);
            if (parts.length > 1 && parts[1].matches("[a-z][a-z0-9_]{0,63}")) mods.add(parts[1]);
        }
        String context = kind + " " + question + " " + hint + " " + target + " " + WzData.string(result,"class") + " " + WzData.string(result,"method");
        var domains = new TreeSet<String>();
        EVENTS.forEach((domain, pattern) -> { if (pattern.matcher(context.toLowerCase(Locale.ROOT)).find()) domains.add(domain); });
        if (kind.equals("trade_offers")) domains.add("trade");
        if (kind.equals("structure_chests") || kind.equals("chest_sources")) domains.add("loot");
        if (kind.equals("entity_spawns")) domains.add("spawn");
        if (kind.equals("food_effects")) domains.add("food");
        if (kind.equals("worldgen_sources")) domains.add("worldgen");
        if (hint.equals("@interactions")) domains.add("interact");
        var requested = new LinkedHashSet<String>();
        for (String id : ids) {
            if (requested.size() >= 128) break;
            if (WenzhouReadTools.resourceId(id) != null) requested.add(id);
        }
        try {
            return com.pixelmc.pixelmcmanager.api.PackGameplayApi.context(server, requested, mods, domains)
                    .thenApply(check -> {
                        result.add("pack_check", check);
                        return result;
                    });
        } catch (LinkageError oldManager) {
            result.add("pack_check", WenzhouReadTools.message("unavailable", "当前 Manager 未提供整合包核对。"));
            return CompletableFuture.completedFuture(result);
        }
    }
}

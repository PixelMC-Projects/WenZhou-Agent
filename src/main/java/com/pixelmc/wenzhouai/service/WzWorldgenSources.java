package com.pixelmc.wenzhouai.service;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.*;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Reverse index of outputs of features actually attached to biomes after Forge biome modifiers. */
final class WzWorldgenSources {
    private record Route(String configured, String placed, String evidence, List<String> biomes) {}
    private record Index(Map<String,List<Route>> blocks, int unreadable) {}
    private static RegistryAccess owner;
    private static CompletableFuture<Index> pending;

    static synchronized void clear() {
        owner = null;
        pending = null;
    }

    static CompletableFuture<JsonObject> query(MinecraftServer server, ResourceLocation id, int page) {
        var item = BuiltInRegistries.ITEM.getOptional(id);
        var block = BuiltInRegistries.BLOCK.getOptional(id);
        var targets = new LinkedHashSet<String>();
        block.ifPresent(b -> targets.add(id.toString()));
        if (item.orElse(null) instanceof BlockItem placeable)
            targets.add(BuiltInRegistries.BLOCK.getKey(placeable.getBlock()).toString());
        if (targets.isEmpty()) return CompletableFuture.completedFuture(WenzhouReadTools.message("not_found",
                "此 ID 未对应到可查的方块。采收物或掉落物请先查 loot_sources 或交互实现，再用产出方块 ID 查询。"));
        return index(server.registryAccess()).thenApply(index -> {
            var rows = new JsonArray();
            var biomes = new HashSet<String>();
            int total = 0;
            long start = (page - 1L) * 12;
            for (String target : targets) for (Route route : index.blocks.getOrDefault(target, List.of())) {
                for (String biome : route.biomes) {
                    biomes.add(biome);
                    if (total++ < start || rows.size() == 12) continue;
                    var row = new JsonObject();
                    row.addProperty("block", target);
                    row.addProperty("configured_feature", route.configured);
                    row.addProperty("placed_feature", route.placed);
                    row.addProperty("evidence", route.evidence);
                    if (route.evidence.startsWith("decorator placement:"))
                        row.addProperty("placement_status","code_candidate_check_conditions");
                    row.addProperty("biome", biome);
                    rows.add(row);
                }
            }
            if (page < 1 || page > 1 && start >= total) return WenzhouReadTools.message("invalid_page", "页码超出范围。");
            var result = WenzhouReadTools.message("live",
                    "已加载群系挂载的地物及其可核对产物。地形、概率和维度仍限制实际生成。未覆盖结构、事件及任意自定义代码，不能据此断言唯一或不存在。");
            result.add("entries", rows);
            result.addProperty("page", page);
            result.addProperty("total", total);
            result.addProperty("more", start + rows.size() < total);
            result.addProperty("biome_count", biomes.size());
            result.addProperty("unreadable_features", index.unreadable);
            if (item.orElse(null) instanceof BlockItem placeable) {
                result.addProperty("places_block", BuiltInRegistries.BLOCK.getKey(placeable.getBlock()).toString());
                result.addProperty("placement_note", "物品注册为该方块的放置物；支撑和生长条件须另行核对。");
            }
            return result;
        });
    }

    private static synchronized CompletableFuture<Index> index(RegistryAccess registries) {
        if (owner != registries || pending == null || pending.isCompletedExceptionally()) {
            owner = registries;
            pending = CompletableFuture.supplyAsync(() -> build(registries), net.minecraft.Util.backgroundExecutor());
        }
        return pending;
    }

    private static Index build(RegistryAccess registries) {
        var configured = registries.registryOrThrow(Registries.CONFIGURED_FEATURE);
        var placed = registries.registryOrThrow(Registries.PLACED_FEATURE);
        var biomes = registries.registryOrThrow(Registries.BIOME);
        var attached = new IdentityHashMap<PlacedFeature,SortedSet<String>>();
        for (var entry : biomes.entrySet()) {
            for (var step : entry.getValue().getGenerationSettings().features()) for (var holder : step) {
                attached.computeIfAbsent(holder.value(), k -> new TreeSet<>()).add(entry.getKey().location().toString());
            }
        }
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        var outputs = new IdentityHashMap<ConfiguredFeature<?,?>,Map<String,String>>();
        var index = new HashMap<String,List<Route>>();
        var decoratorOutputs = new HashMap<Class<?>,Set<String>>();
        int failures = 0;
        for (var entry : attached.entrySet()) {
            var placedId = Objects.toString(placed.getKey(entry.getKey()), "(inline)");
            var biomeList = List.copyOf(entry.getValue());
            try {
                for (var feature : entry.getKey().getFeatures().distinct().toList()) {
                    var evidence = outputs.get(feature);
                    if (evidence == null) {
                        evidence = new TreeMap<>();
                        outputs.put(feature, evidence);
                        var encoded = ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, feature).result();
                        if (encoded.isEmpty()) { failures++; continue; }
                        var config = encoded.get().getAsJsonObject().get("config");
                        if (config != null && config.isJsonObject()) collect(feature.config(), config.getAsJsonObject(), evidence, decoratorOutputs);
                    }
                    String configId = Objects.toString(configured.getKey(feature), "(inline)");
                    evidence.forEach((block, reason) -> index.computeIfAbsent(block, k -> new ArrayList<>())
                            .add(new Route(configId, placedId, reason, biomeList)));
                }
            } catch (RuntimeException unsupported) { failures++; }
        }
        index.values().forEach(routes -> routes.sort(Comparator.comparing(Route::placed)
                .thenComparing(Route::configured).thenComparing(Route::evidence)
                .thenComparing(route -> route.biomes.get(0))));
        return new Index(index, failures);
    }

    // Only inspect output fields. A block mentioned in an ore target predicate or substrate filter is not an output.
    private static void collect(FeatureConfiguration kind, JsonObject config, Map<String,String> out, Map<Class<?>,Set<String>> decoratorOutputs) {
        if (kind instanceof OreConfiguration || kind instanceof ReplaceBlockConfiguration) {
            if (config.get("targets") instanceof JsonArray targets)
                for (var target : targets) if (target.isJsonObject())
                    states(target.getAsJsonObject().get("state"), "targets.state", out);
        }
        if (kind instanceof BlockColumnConfiguration && config.get("layers") instanceof JsonArray layers)
            for (var layer : layers) if (layer.isJsonObject()) states(layer.getAsJsonObject().get("provider"), "layers.provider", out);
        if (kind instanceof DiskConfiguration && config.get("state_provider") instanceof JsonObject provider) {
            states(provider.get("fallback"), "state_provider.fallback", out);
            if (provider.get("rules") instanceof JsonArray rules)
                for (var rule : rules) if (rule.isJsonObject()) states(rule.getAsJsonObject().get("block_state"), "state_provider.rules.block_state", out);
        }
        List<String> fields = List.of();
        if (kind instanceof SimpleBlockConfiguration) fields = List.of("to_place");
        else if (kind instanceof BlockPileConfiguration) fields = List.of("state_provider");
        else if (kind instanceof BlockStateConfiguration || kind instanceof LayerConfiguration) fields = List.of("state");
        else if (kind instanceof HugeMushroomFeatureConfiguration) fields = List.of("cap_provider", "stem_provider");
        else if (kind instanceof LakeFeature.Configuration) fields = List.of("barrier", "fluid");
        else if (kind instanceof TreeConfiguration) fields = List.of("trunk_provider", "foliage_provider", "dirt_provider");
        for (String field : fields) states(config.get(field), field, out);
        if (!(kind instanceof TreeConfiguration tree)) return;
        var definitions = config.getAsJsonArray("decorators");
        int index = 0;
        for (var decorator : tree.decorators) {
            var def = definitions != null && index < definitions.size() && definitions.get(index).isJsonObject()
                    ? definitions.get(index).getAsJsonObject() : new JsonObject();
            index++;
            String type = WzData.string(def,"type");
            boolean vanillaChance = Set.of("minecraft:attached_to_leaves","minecraft:beehive","minecraft:cocoa").contains(type);
            var chance = def.get("probability");
            if (vanillaChance && chance != null && chance.isJsonPrimitive() && chance.getAsDouble() == 0) continue;
            if (type.equals("minecraft:attached_to_leaves")) states(def.get("block_provider"), "decorator " + type, out);
            if (type.equals("minecraft:alter_ground")) states(def.get("provider"), "decorator " + type, out);
            Class<?> implementation = decorator.getClass();
            String detail = "decorator placement: " + implementation.getName() + ".place";
            for (String key : List.of("chance","probability","enabled")) {
                var value = def.get(key);
                if (value != null && value.isJsonPrimitive() && !value.getAsJsonPrimitive().isString()) detail += " " + key + "=" + value;
            }
            for (String block : decoratorOutputs.computeIfAbsent(implementation, WzDecoratorOutputs::find))
                out.putIfAbsent(block,detail);
        }
    }

    private static void states(JsonElement value, String source, Map<String,String> out) {
        if (!(value instanceof JsonObject obj)) return;
        String id = WzData.string(obj, "Name");
        if (!id.isEmpty()) { out.putIfAbsent(id, source); return; }
        states(obj.get("state"), source, out);
        if (obj.get("entries") instanceof JsonArray entries)
            for (var entry : entries) if (entry.isJsonObject()) states(entry.getAsJsonObject().get("data"), source, out);
        if (obj.get("states") instanceof JsonArray states)
            for (var state : states) states(state, source, out);
    }

    private WzWorldgenSources() {}
}

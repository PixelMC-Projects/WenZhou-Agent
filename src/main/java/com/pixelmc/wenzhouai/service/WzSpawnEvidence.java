package com.pixelmc.wenzhouai.service;

import com.google.gson.*;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import com.electronwill.nightconfig.toml.TomlParser;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

final class WzSpawnEvidence {

    private static final Set<String> NONPUBLIC = Set.of("command", "commands", "message", "nbt", "playerhelditem", "playerwearing");
    private WzSpawnEvidence() {}

    static CompletableFuture<JsonObject> query(MinecraftServer server, ResourceLocation id, int page) {
        var entity = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
        if (entity.isEmpty()) return CompletableFuture.completedFuture(WenzhouReadTools.message("not_registered", "没有注册这个实体。"));
        JsonArray rows = new JsonArray();
        var biomes = server.registryAccess().registryOrThrow(Registries.BIOME);
        for (var entry : biomes.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(k -> k.location()))).toList()) {
            for (var spawn : entry.getValue().getMobSettings().getMobs(entity.get().getCategory()).unwrap()) {
                if (spawn.type != entity.get()) continue;
                var row = new JsonObject();
                row.addProperty("biome", entry.getKey().location().toString());
                row.addProperty("weight", spawn.getWeight().asInt());
                row.addProperty("min_group", spawn.minCount);
                row.addProperty("max_group", spawn.maxCount);
                rows.add(row);
            }
        }
        var tags = new HashSet<String>();
        entity.get().builtInRegistryHolder().tags().forEach(tag -> tags.add("#" + tag.location()));
        boolean installed = ModList.get().isLoaded("incontrol");
        boolean badMobs = ModList.get().isLoaded("badmobs");
        return CompletableFuture.supplyAsync(() -> {
            JsonArray rules = new JsonArray(), warnings = new JsonArray();
            if (installed) {
                var dir = FMLPaths.CONFIGDIR.get().resolve("incontrol");
                for (String file : List.of("spawn.json", "spawner.json")) {
                    inspect(dir.resolve(file), id.toString(), tags, rules, warnings);
                }
            }
            // Put explicit rules first so an extensive biome list cannot bury a pack override.
            if (badMobs) badMobsRule(FMLPaths.CONFIGDIR.get().resolve("badmobs-common.toml"), id, rules, warnings);
            int ruleCount = rules.size();
            rules.addAll(rows);
            var result = WenzhouReadTools.entriesPage(rules, page, "群系表是自然生成候选。InControl 和 Bad Mobs 条目来自磁盘配置；InControl 按文件顺序列出明确指定实体的规则。全局条件、其他模组和实地判定仍可改变结果。");
            result.addProperty("biome_entries", rows.size());
            result.addProperty("targeted_config_rules", ruleCount);
            result.add("warnings", warnings);
            return result;
        }, net.minecraft.Util.ioPool());
    }

    private static void inspect(Path file, String id, Set<String> tags, JsonArray rows, JsonArray warnings) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return;
        try (var in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(2_000_001);
            if (bytes.length > 2_000_000) throw new IOException();
            int order = 0;
            for (JsonElement element : JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonArray()) {
                order++;
                if (!element.isJsonObject()) continue;
                var rule = element.getAsJsonObject();
                if (!matches(rule.get("mob"), id, tags)) continue;
                var row = new JsonObject();
                row.addProperty("config", "incontrol/" + file.getFileName());
                row.addProperty("rule_number", order);
                var fields = new JsonObject();
                for (String key : List.of("result","when","biome","biometags","biometype","dimension","minheight","maxheight","persecond","attempts","amount","gamestage")) {
                    if (rule.has(key)) fields.add(key, rule.get(key));
                }
                if (rule.has("conditions") && rule.get("conditions").isJsonObject()) {
                    var conditions = rule.getAsJsonObject("conditions");
                    var spawnFields = new JsonObject();
                    for (String key : List.of("dimension","biome","mindist","maxdist","minheight","maxheight","maxthis","maxlocal","maxhostile","maxneutral","maxpassive","inwater","inlava","inair","seesky")) {
                        if (conditions.has(key)) spawnFields.add(key, conditions.get(key));
                    }
                    fields.add("conditions", spawnFields);
                    if (conditions.size() > spawnFields.size()) row.addProperty("additional_conditions", true);
                }
                var other = new JsonArray();
                for (String key : rule.keySet()) {
                    if (!key.equals("mob") && !fields.has(key) && !NONPUBLIC.contains(key)) other.add(key);
                }
                if (!other.isEmpty()) row.add("other_rule_fields", other);
                if (fields.toString().length() <= 5000) row.add("conditions_and_result", fields);
                else row.addProperty("omitted", "条件过长，请按文件和序号核对。");
                rows.add(row);
            }
        } catch (IOException | RuntimeException e) {
            warnings.add(file.getFileName() + " 未能完整读取");
        }
    }

    private static void badMobsRule(Path file, ResourceLocation id, JsonArray rows, JsonArray warnings) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return;
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(3_000_001);
            if (bytes.length > 3_000_000) throw new IOException();
            var config = new TomlParser().parse(new String(bytes, StandardCharsets.UTF_8));
            var flags = new JsonObject();
            for (String key : List.of("allowNormalSpawning","allowSpawners","allowSpawnEggs","allowConversions")) {
                Object value = config.get(List.of(id.getNamespace(), id.getPath(), key));
                if (value instanceof Boolean enabled) flags.addProperty(key, enabled);
            }
            if (flags.size() == 0) return;
            var row = new JsonObject();
            row.addProperty("config", "badmobs-common.toml");
            row.add("permissions", flags);
            rows.add(row);
        } catch (IOException | RuntimeException failed) {
            warnings.add("Bad Mobs 配置未能完整读取");
        }
    }

    private static boolean matches(JsonElement selector, String id, Set<String> tags) {
        if (selector == null) return false;
        if (selector.isJsonArray()) {
            for (var candidate : selector.getAsJsonArray()) if (matches(candidate, id, tags)) return true;
        } else if (selector.isJsonPrimitive()) {
            String value = selector.getAsString();
            return value.equals(id) || tags.contains(value);
        }
        return false;
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

package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

/** Joins registered items' actual description keys to language assets once per resource reload. */
final class WzRegisteredItems {
    record Row(String id, String description, String display, String modName) {}
    record Catalog(WzItemIndex index, int unreadableLanguageFiles) {}
    private static final Map<ResourceManager, CompletableFuture<Catalog>> CACHE = new WeakHashMap<>();
    private static final int FILE_LIMIT = 4_000_000;

    static synchronized CompletableFuture<Catalog> index(MinecraftServer server) {
        var resources = server.getResourceManager();
        var previous = CACHE.get(resources);
        if (previous != null && !previous.isCompletedExceptionally()) return previous;
        var index = server.submit(WzRegisteredItems::snapshot).thenApplyAsync(rows -> {
            List<Path> roots = new ArrayList<>();
            var vanilla = WzVanillaLanguage.assets(FMLPaths.CONFIGDIR.get()
                    .resolve("wenzhouai/lang-cache/1.20.1/assets"));
            vanilla.ifPresent(roots::add);
            ModList.get().getModFiles().forEach(file -> roots.add(file.getFile().findResource("assets")));
            roots.add(FMLPaths.GAMEDIR.get().resolve("kubejs/assets"));
            roots.add(FMLPaths.CONFIGDIR.get().resolve("wenzhouai/pack-language/assets"));
            Catalog catalog = from(rows, roots);
            return vanilla.isPresent() ? catalog
                    : new Catalog(catalog.index(), catalog.unreadableLanguageFiles() + 1);
        }, Util.ioPool());
        CACHE.put(resources, index);
        return index;
    }

    private static List<Row> snapshot() {
        Map<String,String> mods = new HashMap<>();
        ModList.get().getMods().forEach(mod -> mods.put(mod.getModId(), mod.getDisplayName()));
        List<Row> rows = new ArrayList<>();
        Language language = Language.getInstance();
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            var id = entry.getKey().location();
            String key = entry.getValue().getDescriptionId();
            String name = language.getOrDefault(key, "");
            rows.add(new Row(id.toString(), key, name, mods.getOrDefault(id.getNamespace(), id.getNamespace())));
        }
        return rows;
    }

    static Catalog from(List<Row> rows, List<Path> roots) {
        var chinese = new HashMap<String,List<String>>();
        var english = new HashMap<String,List<String>>();
        var registeredKeys = new java.util.HashSet<String>();
        for (Row row : rows) registeredKeys.add(row.description());
        int unreadable = 0;
        for (Path assets : roots) {
            if (!Files.isDirectory(assets, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var namespaces = Files.list(assets)) {
                for (Path namespace : namespaces.toList()) {
                    if (!Files.isDirectory(namespace, LinkOption.NOFOLLOW_LINKS)) continue;
                    Path lang = namespace.resolve("lang");
                    if (!Files.isDirectory(lang, LinkOption.NOFOLLOW_LINKS)) continue;
                    // An addon may translate another mod's keys in its own namespace.
                    unreadable += read(lang.resolve("en_us.json"), registeredKeys, english);
                    unreadable += read(lang.resolve("zh_cn.json"), registeredKeys, chinese);
                }
            } catch (IOException | java.io.UncheckedIOException ignored) {
                unreadable++;
            }
        }
        List<WzItemIndex.Entry> items = new ArrayList<>(rows.size());
        for (Row row : rows) {
            List<String> zh = chinese.getOrDefault(row.description(), List.of());
            List<String> en = english.getOrDefault(row.description(), List.of());
            String display = !zh.isEmpty() ? zh.get(0) : !row.display().isEmpty() ? net.minecraft.ChatFormatting.stripFormatting(row.display())
                    : !en.isEmpty() ? en.get(0) : row.id();
            items.add(new WzItemIndex.Entry(row.id(), row.modName(), display, zh, en));
        }
        return new Catalog(new WzItemIndex(items), unreadable);
    }

    private static int read(Path path, Set<String> keys, Map<String,List<String>> lang) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return 0;
        try (var file = Files.newInputStream(path)) {
            byte[] bytes = file.readNBytes(FILE_LIMIT + 1);
            if (bytes.length > FILE_LIMIT) return 1;
            var labels = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : labels.entrySet()) {
                if (!keys.contains(entry.getKey()) || !entry.getValue().isJsonPrimitive()
                        || !entry.getValue().getAsJsonPrimitive().isString()) continue;
                String name = net.minecraft.ChatFormatting.stripFormatting(entry.getValue().getAsString()).strip();
                if (name.isEmpty() || name.length() > 160 || name.codePoints().anyMatch(Character::isISOControl)) continue;
                var aliases = lang.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>());
                // Keep source variants searchable; later pack-level names are displayed first.
                aliases.remove(name);
                aliases.add(0, name);
            }
            return 0;
        } catch (IOException | RuntimeException ignored) {
            return 1;
        }
    }

    private WzRegisteredItems() {}
}

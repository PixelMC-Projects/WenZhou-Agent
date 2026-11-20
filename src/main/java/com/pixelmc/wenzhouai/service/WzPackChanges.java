package com.pixelmc.wenzhouai.service;

import com.google.gson.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

final class WzPackChanges {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final List<String> GROUPS = List.of("KubeJS","LootJS","CraftTweaker","数据包","掉落修饰器");
    private static final java.util.concurrent.ExecutorService WORKER = new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> { var t = new Thread(task,"Wenzhou pack changes"); t.setDaemon(true); return t; });

    static CompletableFuture<JsonObject> query(MinecraftServer server, ResourceLocation id, int page) {
        var manager = server.getResourceManager();
        var tags = new HashSet<String>();
        BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> item.builtInRegistryHolder().tags()
                .forEach(tag -> tags.add("#" + tag.location())));
        try {
            return CompletableFuture.supplyAsync(() -> {
                var scan = new Scan(id, tags);
                scan.resources(manager);
                scan.scripts(FMLPaths.GAMEDIR.get());
                return scan.page(page);
            }, WORKER).exceptionally(ex -> WenzhouReadTools.message("unavailable","本次整合包资料未能读取。"));
        } catch (RejectedExecutionException busy) {
            return CompletableFuture.completedFuture(WenzhouReadTools.message("busy","整合包资料正在查阅。"));
        }
    }

    private static final class Scan {
        final ResourceLocation target;
        final Set<String> tags;
        final Pattern ids = Pattern.compile("#?[a-z0-9_.-]+:[a-z0-9_./-]+");
        final Pattern tableCall = Pattern.compile("\\.addLootTableModifier\\(\\s*['\"]([a-z0-9_.-]+:[a-z0-9_./-]+)['\"]");
        final List<JsonObject> matches = new ArrayList<>();
        int unreadable;

        Scan(ResourceLocation target, Set<String> tags) { this.target = target; this.tags = tags; }

        String match(String text) {
            var matcher = ids.matcher(text.replace("<item:", "<").replace("<tag:items:", "<#"));
            String via = "";
            while (matcher.find()) {
                String token = matcher.group();
                if (token.equals(target.toString())) return token;
                if (via.isEmpty() && tags.contains(token)) via = token;
            }
            return via;
        }
        JsonObject add(String source, String path, String evidence, String via, int line) {
            var row = new JsonObject();
            row.addProperty("origin", source);
            row.addProperty("path", path);
            row.addProperty("evidence", evidence);
            row.addProperty("match", via);
            if (line > 0) row.addProperty("line", line);
            matches.add(row);
            return row;
        }

        void resources(ResourceManager manager) {
            for (String folder : List.of("recipes","tags/items","loot_tables","loot_modifiers")) {
                boolean tag = folder.equals("tags/items"), modifier = folder.equals("loot_modifiers");
                var stacks = manager.listResourceStacks(folder, id -> id.getPath().endsWith(".json"));
                for (var entry : stacks.entrySet()) {
                    var id = entry.getKey();
                    var layers = entry.getValue();
                    if (!modifier && layers.stream().noneMatch(layer -> customPack(layer.sourcePackId()))) continue;
                    String logical = id.getNamespace() + ":" + id.getPath().substring(folder.length() + 1, id.getPath().length() - 5);
                    // The last resource wins for single-definition types. Item tags can merge multiple layers.
                    for (int i = layers.size() - 1; i >= 0; i--) {
                        var resource = layers.get(i);
                        String pack = resource.sourcePackId();
                        boolean kube = pack.toLowerCase(Locale.ROOT).contains("kubejs");
                        boolean custom = customPack(pack);
                        if (!custom && !modifier && (!tag || i == 0)) break;
                        try (var in = resource.open()) {
                            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                            if (bytes.length > MAX_BYTES) { unreadable++; break; }
                            String json = new String(bytes, StandardCharsets.UTF_8);
                            String via = logical.equals(target.toString()) ? logical : match(json);
                            if (!via.isEmpty() && (custom || modifier)) {
                                String source = kube ? "KubeJS" : modifier ? "掉落修饰器" : "数据包";
                                String evidence = tag ? "已加载的标签层" : modifier ? "当前修饰器定义，触发与启用条件另查"
                                        : i > 0 ? "当前数据包覆盖了同名定义" : "当前数据包提供的定义";
                                add(source, pack + "/" + id, evidence, via, 0);
                            }
                            if (!tag || (i > 0 && replacesTag(json))) break;
                        } catch (IOException | RuntimeException invalid) { unreadable++; break; }
                    }
                }
            }
        }
        boolean customPack(String id) {
            String pack = id.toLowerCase(Locale.ROOT);
            return pack.startsWith("file/") || pack.contains("kubejs") || pack.startsWith("openloader/")
                    || pack.startsWith("paxi/") || pack.startsWith("globalpacks/");
        }
        boolean replacesTag(String json) {
            var obj = JsonParser.parseString(json).getAsJsonObject();
            return obj.has("replace") && obj.get("replace").getAsBoolean();
        }
        void scripts(Path game) {
            for (String folder : List.of("kubejs/server_scripts","kubejs/startup_scripts","scripts")) {
                boolean zen = folder.equals("scripts");
                Path root = game.resolve(folder);
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    if (!root.toRealPath().startsWith(game.toRealPath())) { unreadable++; continue; }
                    try (var files = Files.walk(root)) {
                        for (var file : files.filter(p -> p.toString().endsWith(zen ? ".zs" : ".js"))
                                .filter(p -> Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList()) {
                            try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                                if (bytes.length > MAX_BYTES) { unreadable++; continue; }
                                String script = new String(bytes, StandardCharsets.UTF_8);
                                String source = zen ? "CraftTweaker" : script.contains("LootJS.") ? "LootJS" : "KubeJS";
                                String path = folder + "/" + root.relativize(file).toString().replace('\\','/');
                                JsonArray tables = null;
                                int line = 0;
                                for (String text : script.lines().toList()) {
                                    line++;
                                    if (text.startsWith("LootJS.modifiers(")) tables = new JsonArray();
                                    if (tables != null) {
                                        var calls = tableCall.matcher(text);
                                        while (calls.find() && tables.size() < 4) {
                                            var table = new JsonPrimitive(calls.group(1));
                                            if (!tables.contains(table)) tables.add(table);
                                        }
                                    }
                                    // Keep original column offsets while recognizing Zen bracket handlers.
                                    String searchable = zen ? text.replace("<item:", " ".repeat(6))
                                            .replace("<tag:items:", " ".repeat(10) + "#") : text;
                                    var tokens = ids.matcher(searchable);
                                    while (tokens.find()) {
                                        String via = tokens.group();
                                        if (!via.equals(target.toString()) && !tags.contains(via)) continue;
                                        var row = add(source,path,"脚本位置；同段表名是定位线索，执行条件另查",via,line);
                                        row.addProperty("column",tokens.start()+1);
                                        if (tables != null) row.add("nearby_tables",tables);
                                    }
                                    if (text.startsWith("})")) tables = null;
                                }
                            } catch (IOException failure) { unreadable++; }
                        }
                    }
                } catch (IOException | UncheckedIOException error) { unreadable++; }
            }
        }
        JsonObject page(int page) {
            matches.sort(Comparator.comparing((JsonObject row) -> !row.get("match").getAsString().equals(target.toString()))
                    .thenComparing(row -> row.get("path").getAsString()));
            var result = WenzhouReadTools.message("pack_changes","counts 按文件或资源层去重，occurrences 按匹配位置计数。未命中不能排除动态脚本。");
            var counts = new JsonObject();
            var occurrences = new JsonObject();
            var paths = new HashSet<List<String>>();
            for (var row : matches) {
                var source = row.get("origin").getAsString();
                if (paths.add(List.of(source,row.get("path").getAsString())))
                    counts.addProperty(source, WzData.number(counts,source) + 1);
                occurrences.addProperty(source,WzData.number(occurrences,source)+1);
            }
            result.add("counts", counts);
            result.add("occurrences",occurrences);
            result.addProperty("total", matches.size());
            result.addProperty("unreadable", unreadable);
            result.addProperty("page",page);
            var rows = new JsonArray();
            matches.stream().skip((page - 1L)*12).limit(12).forEach(rows::add);
            result.add("matches",rows);
            return result;
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

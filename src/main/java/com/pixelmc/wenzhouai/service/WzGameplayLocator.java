package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.ModList;
import org.objectweb.asm.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.zip.ZipFile;

/** Reads symbols and line tables without loading classes or executing mod code. */
final class WzGameplayLocator {
    private record Hit(int score, String owner, String method, String descriptor, int line, List<String> refs) {}
    private static final Map<List<Object>, List<Hit>> cache = new LinkedHashMap<>(16, .75f, true);
    private static final int PAGE_SIZE = 5;

    static CompletableFuture<JsonObject> query(String target, int page, Executor worker, boolean interactions) {
        String[] parts = target.split(":", -1);
        if (parts.length != 2 || !parts[0].matches("[a-z][a-z0-9_]{1,63}")
                || !parts[1].matches("[A-Za-z0-9_.$/-]{1,180}") || page < 1)
            return CompletableFuture.completedFuture(WenzhouReadTools.message("invalid_request", "请提供 modid:物品路径 或 modid:类名。"));
        var mod = ModList.get().getModFileById(parts[0]);
        if (mod == null) return CompletableFuture.completedFuture(WenzhouReadTools.message("not_found", "未安装这个模组。"));
        Path jar = mod.getFile().getFilePath();
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    var result = interactions ? WzInteractionFinder.find(jar, parts[1], page) : locate(jar, parts[1], page);
                    if (interactions && result.has("total") && result.get("total").getAsInt() == 0) {
                        result = locate(jar, parts[1], page);
                        result.addProperty("fallback", "symbol_references");
                        result.addProperty("message", "专用交互索引未命中，已用相同标识查找普通符号引用；用 read_method 确认入口和产物方向。");
                    }
                    String sha = WzJarFingerprint.of(jar);
                    if (result.has("methods")) for (var entry : result.getAsJsonArray("methods")) {
                        var row = entry.getAsJsonObject();
                        row.addProperty("method_id", parts[0] + ":" + row.get("class").getAsString() + "#"
                                + WzMethodEvidence.key(row.get("method").getAsString(), row.get("descriptor").getAsString())
                                + "@" + (row.has("line") ? row.get("line").getAsInt() : 0) + "!" + sha);
                    }
                    return result;
                }
                catch (IOException | RuntimeException failed) { return WenzhouReadTools.message("unavailable", "无法读取这个安装包的玩法实现。"); }
            }, worker);
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            return CompletableFuture.completedFuture(WenzhouReadTools.message("busy", "代码查阅繁忙。"));
        }
    }

    static JsonObject locate(Path jar, String term, int page) throws IOException {
        String needle = normalized(term.substring(Math.max(term.lastIndexOf('.'), term.lastIndexOf('/')) + 1));
        if (needle.length() < 2) return WenzhouReadTools.message("invalid_request", "请提供更具体的物品或类名。");
        var key = List.<Object>of(jar.toAbsolutePath(), java.nio.file.Files.size(jar),
                java.nio.file.Files.getLastModifiedTime(jar), needle);
        List<Hit> hits;
        synchronized (cache) { hits = cache.get(key); }
        if (hits == null) {
            hits = scan(jar, needle);
            synchronized (cache) {
                cache.put(key, hits);
                if (cache.size() > 16) cache.remove(cache.keySet().iterator().next());
            }
        }
        var result = WenzhouReadTools.message("installed_code", "本模组方法中的实际符号引用。按相关性排序，用方法体核对具体条件与顺序。");
        JsonArray rows = new JsonArray();
        hits.stream().skip((page - 1L) * PAGE_SIZE).limit(PAGE_SIZE).forEach(hit -> {
            JsonObject row = new JsonObject();
            row.addProperty("class", hit.owner.replace('/', '.'));
            row.addProperty("method", hit.method);
            row.addProperty("descriptor", hit.descriptor);
            if (hit.line > 0) row.addProperty("line", hit.line);
            JsonArray refs = new JsonArray();
            hit.refs.forEach(refs::add);
            row.add("references", refs);
            rows.add(row);
        });
        result.add("methods", rows);
        result.addProperty("total", hits.size());
        result.addProperty("page", page);
        return result;
    }

    private static final Comparator<Hit> ORDER = Comparator.comparingInt(Hit::score).reversed()
            .thenComparing(Hit::owner).thenComparing(Hit::method).thenComparing(Hit::descriptor);

    private static List<Hit> scan(Path jar, String needle) throws IOException {
        List<Hit> hits = new ArrayList<>();
        try (var zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")
                        || entry.getSize() <= 0 || entry.getSize() > 2_000_000) continue;
                byte[] bytes;
                try (var input = zip.getInputStream(entry)) { bytes = input.readNBytes((int) entry.getSize()); }
                // Constant-pool names contain plain ASCII for registry paths, fields and Java classes.
                String pool = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
                if (!pool.replace("_", "").contains(needle)) continue;
                ClassReader reader = new ClassReader(bytes);
                String owner = reader.getClassName();
                boolean ownerMatches = normalized(owner.substring(owner.lastIndexOf('/') + 1)).contains(needle);
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            int firstLine, line, matchLine;
                            boolean match, hasStack, hasTag;
                            final Set<String> refs = new LinkedHashSet<>();

                            @Override public void visitLineNumber(int n, Label start) {
                                line = n;
                                if (firstLine == 0) firstLine = n;
                            }
                            private void reference(String type, String symbol, String signature) {
                                String simple = type.substring(type.lastIndexOf('/') + 1);
                                boolean matched = normalized(symbol).contains(needle);
                                if (matched && !match) matchLine = line;
                                match |= matched;
                                hasStack |= type.endsWith("/ItemStack");
                                hasTag |= signature.contains("/TagKey;");
                                boolean useful = matched || signature.contains("/TagKey;") || signature.contains("/RegistryObject;")
                                        || signature.contains("/ItemEntry;") || signature.contains("/BlockEntry;");
                                if (useful && !symbol.equals("INSTANCE") && refs.size() < 32)
                                    refs.add(simple + "." + symbol);
                            }
                            @Override public void visitFieldInsn(int op, String type, String field, String signature) {
                                reference(type, field, signature);
                            }
                            @Override public void visitMethodInsn(int op, String type, String method, String signature, boolean iface) {
                                reference(type, method, signature);
                            }
                            @Override public void visitLdcInsn(Object value) {
                                if (value instanceof String literal && literal.length() < 241 && normalized(literal).equals(needle)) {
                                    if (!match) matchLine = line;
                                    match = true;
                                }
                            }
                            @Override public void visitEnd() {
                                if (!match && !ownerMatches) return;
                                int score = (match ? 10 : 0) + (ownerMatches ? 6 : 0) + (hasStack ? 15 : 0)
                                        + (hasTag ? 8 : 0) + Math.min(5, refs.size()) - (name.startsWith("<") ? 8 : 0);
                                hits.add(new Hit(score, owner, name, desc, matchLine > 0 ? matchLine : firstLine, List.copyOf(refs)));
                            }
                        };
                    }
                }, ClassReader.SKIP_FRAMES);
            }
        }
        hits.sort(ORDER);
        return List.copyOf(hits.subList(0, Math.min(hits.size(), 500)));
    }

    private static String normalized(String name) {
        return name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }
    private WzGameplayLocator() {}
}

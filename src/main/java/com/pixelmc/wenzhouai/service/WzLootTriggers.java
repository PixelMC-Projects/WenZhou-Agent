package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;

final class WzLootTriggers {
    private static final Map<String,JsonArray> cache = new LinkedHashMap<>(32, .75f, true);

    static JsonObject references(ResourceLocation table) {
        var result = new JsonObject();
        result.addProperty("scope", "所属模组代码中的完整表 ID 常量及同类直接调用者。动态拼接和跨模组引用可能另有入口。");
        var file = ModList.get().getModFileById(table.getNamespace());
        if (file != null) {
            try {
                result.add("methods", scan(file.getFile().getFilePath(), table.toString()));
            } catch (IOException | RuntimeException ignored) {
                result.addProperty("unavailable", true);
            }
        } else result.addProperty("unavailable", true);
        return result;
    }

    static JsonArray scan(Path jar, String table) throws IOException {
        String sha = WzJarFingerprint.of(jar);
        String key = sha + ":" + table;
        synchronized (cache) {
            if (cache.containsKey(key)) return cache.get(key).deepCopy();
        }
        var rows = new JsonArray();
        try (var zip = new ZipFile(jar.toFile())) {
            var files = zip.entries();
            while (files.hasMoreElements() && rows.size() < 12) {
                var entry = files.nextElement();
                if (!entry.getName().endsWith(".class") || entry.getSize() < 1 || entry.getSize() > 2_000_000) continue;
                byte[] bytes;
                try (var in = zip.getInputStream(entry)) { bytes = in.readNBytes((int)entry.getSize()); }
                if (!new String(bytes, StandardCharsets.ISO_8859_1).contains(table)) continue;
                var type = new ClassNode();
                new ClassReader(bytes).accept(type, ClassReader.SKIP_FRAMES);
                var hits = new HashMap<String,MethodNode>();
                for (var method : type.methods) {
                    int line = 0;
                    for (var op : method.instructions) {
                        if (op instanceof LineNumberNode at) line = at.line;
                        if (op instanceof LdcInsnNode ldc && table.equals(ldc.cst)) {
                            hits.put(method.name + method.desc, method);
                            add(rows, table, sha, type, method, line, "table_literal");
                            break;
                        }
                    }
                }
                for (var method : type.methods) {
                    int line = 0;
                    for (var op : method.instructions) {
                        if (op instanceof LineNumberNode at) line = at.line;
                        if (op instanceof MethodInsnNode call && call.owner.equals(type.name) && hits.containsKey(call.name + call.desc)) {
                            add(rows, table, sha, type, method, line, "calls_" + call.name);
                            break;
                        }
                    }
                }
            }
        }
        synchronized (cache) {
            cache.put(key, rows.deepCopy());
            if (cache.size() > 48) cache.remove(cache.keySet().iterator().next());
        }
        return rows;
    }

    private static void add(JsonArray rows, String table, String sha, ClassNode type, MethodNode method, int line, String via) {
        if (rows.size() >= 12) return;
        var row = new JsonObject();
        String owner = type.name.replace('/', '.');
        row.addProperty("method", owner + "." + method.name);
        row.addProperty("via", via);
        row.addProperty("method_id", table.substring(0, table.indexOf(':')) + ":" + owner + "#"
                + WzMethodEvidence.key(method.name, method.desc) + "@" + line + "!" + sha);
        rows.add(row);
    }
    private WzLootTriggers() {}
}

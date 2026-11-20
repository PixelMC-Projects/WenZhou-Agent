package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.ModList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Printer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

final class WzMethodEvidence {
    private static final Pattern HANDLE = Pattern.compile("([a-z][a-z0-9_]{1,63}):([A-Za-z0-9_$]+(?:\\.[A-Za-z0-9_$]+)*)#([0-9a-f-]{36})@(\\d{1,6})");
    private static final int PAGE = 100;

    static String key(String name, String descriptor) {
        return UUID.nameUUIDFromBytes((name + descriptor).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    static CompletableFuture<JsonObject> query(String handle, int page, Executor worker) {
        var parsed = HANDLE.matcher(handle);
        if (!parsed.matches() || page < 1) return CompletableFuture.completedFuture(error("无效的方法编号。"));
        var mod = ModList.get().getModFileById(parsed.group(1));
        if (mod == null) return CompletableFuture.completedFuture(error("未安装对应模组。"));
        try {
            return CompletableFuture.supplyAsync(() -> {
                try { return read(mod.getFile().getFilePath(), parsed.group(2), parsed.group(3),
                        Integer.parseInt(parsed.group(4)), page); }
                catch (IOException | RuntimeException e) { return error("方法无法读取，请重新定位。"); }
            }, worker);
        } catch (RejectedExecutionException busy) { return CompletableFuture.completedFuture(error("代码查阅繁忙。")); }
    }

    static JsonObject read(Path jar, String owner, String key, int hitLine, int page) throws IOException {
        try (var zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(owner.replace('.', '/') + ".class");
            if (entry == null || entry.getSize() <= 0 || entry.getSize() > 2_000_000) return error("类不在当前安装包。");
            var type = new ClassNode();
            try (var input = zip.getInputStream(entry)) { new ClassReader(input).accept(type, ClassReader.SKIP_FRAMES); }
            for (var method : type.methods) {
                if (!key(method.name, method.desc).equals(key)) continue;
                List<String> lines = new ArrayList<>();
                Map<LabelNode,Integer> labels = new IdentityHashMap<>();
                int focus = 0;
                for (var insn : method.instructions) {
                    if (insn instanceof LineNumberNode n && n.line == hitLine) focus = lines.size();
                    if (insn instanceof LabelNode l) labels.put(l, lines.size());
                    if (insn.getOpcode() >= 0) lines.add("");
                }
                int pos = 0;
                for (var insn : method.instructions) if (insn.getOpcode() >= 0) {
                    String value = operand(insn, labels);
                    lines.set(pos, pos + " " + Printer.OPCODES[insn.getOpcode()] + (value.isEmpty() ? "" : " " + value));
                    pos++;
                }
                // First read centres on the hit. Later pages cover the entire method with stable offsets.
                boolean excerpt = lines.size() > PAGE;
                long start = page == 1 ? Math.min(Math.max(0, focus - PAGE / 2), Math.max(0, lines.size() - PAGE))
                        : (long)(page - 2) * PAGE;
                if (page > 1 && (!excerpt || start >= lines.size())) return error("方法没有这一页。");
                JsonArray body = new JsonArray();
                lines.stream().skip(start).limit(PAGE).forEach(body::add);
                var result = WenzhouReadTools.message("installed_code", excerpt
                        ? "跳转目标为指令编号。第 2 页起按顺序覆盖完整方法。" : "当前安装包中的完整方法。");
                result.addProperty("class", owner);
                result.addProperty("method", method.name);
                result.addProperty("descriptor", method.desc);
                result.addProperty("instructions", lines.size());
                result.addProperty("complete", !excerpt);
                result.add("body", body);
                return result;
            }
        }
        return error("该版本没有这个方法。");
    }

    private static String operand(AbstractInsnNode insn, Map<LabelNode,Integer> labels) {
        if (insn instanceof MethodInsnNode m) return m.owner + "." + m.name + m.desc;
        if (insn instanceof FieldInsnNode f) return f.owner + "." + f.name + " " + f.desc;
        if (insn instanceof JumpInsnNode j) return String.valueOf(labels.get(j.label));
        if (insn instanceof IntInsnNode n) return Integer.toString(n.operand);
        if (insn instanceof VarInsnNode v) return Integer.toString(v.var);
        if (insn instanceof IincInsnNode v) return v.var + " " + v.incr;
        if (insn instanceof TypeInsnNode t) return t.desc;
        if (insn instanceof LdcInsnNode c) {
            String s = String.valueOf(c.cst);
            return c.cst instanceof String && !s.matches("[a-z0-9_.:/-]{1,120}") ? "[text]" : s;
        }
        if (insn instanceof InvokeDynamicInsnNode d) return d.name + d.desc;
        if (insn instanceof MultiANewArrayInsnNode a) return a.desc + " " + a.dims;
        if (insn instanceof TableSwitchInsnNode sw) return "from " + sw.min + " to " + sw.max
                + " targets " + sw.labels.stream().map(labels::get).limit(32).toList() + " default " + labels.get(sw.dflt);
        if (insn instanceof LookupSwitchInsnNode sw) return "cases " + java.util.stream.IntStream.range(0, Math.min(sw.keys.size(),32))
                .mapToObj(i -> sw.keys.get(i) + "=" + labels.get(sw.labels.get(i))).toList() + " default " + labels.get(sw.dflt);
        return "";
    }
    private static JsonObject error(String text) { return WenzhouReadTools.message("unavailable", text); }
    private WzMethodEvidence() {}
}

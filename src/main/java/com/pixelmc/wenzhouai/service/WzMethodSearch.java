package com.pixelmc.wenzhouai.service;

import com.google.gson.*;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Printer;
import java.util.*;

/** Instruction offsets are local to each method. Follow explicit same-class lambda handles only. */
final class WzMethodSearch {
    private static final int PAGE_ROWS = 5;
    private static final int RADIUS = 4;

    static JsonObject search(ClassNode type, MethodNode root, String options, int page) {
        boolean fields = options.startsWith("@fields");
        String terms = fields ? options.substring("@fields".length()).strip() : options;
        var words = terms.isEmpty() ? List.<String>of() : List.of(terms.toLowerCase(Locale.ROOT).split(" +"));
        var methods = callbacks(type, root);
        var scope = new JsonArray();
        var rows = new JsonArray();
        long start = (page - 1L) * PAGE_ROWS;
        int total = 0;
        for (var method : methods) {
            String signature = method.name + method.desc;
            scope.add(signature);
            var lines = new ArrayList<String>();
            var labels = new IdentityHashMap<LabelNode,Integer>();
            int pos = 0;
            for (var insn : method.instructions) {
                if (insn instanceof LabelNode label) labels.put(label, pos);
                if (insn.getOpcode() >= 0) pos++;
            }
            var hits = new BitSet();
            for (var insn : method.instructions) {
                if (insn.getOpcode() < 0) continue;
                String line = Printer.OPCODES[insn.getOpcode()] + " " + WzMethodEvidence.operand(insn, labels);
                String lower = line.toLowerCase(Locale.ROOT);
                boolean access = insn instanceof FieldInsnNode || insn instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/nbt/CompoundTag");
                if ((!fields || access) && (words.isEmpty() || words.stream().anyMatch(lower::contains)))
                    hits.set(lines.size());
                lines.add(line.stripTrailing());
            }
            int previousEnd = -1;
            for (int hit = hits.nextSetBit(0); hit >= 0; hit = hits.nextSetBit(hit + 1)) {
                if (hit <= previousEnd) continue;
                int first = Math.max(previousEnd + 1, hit - RADIUS);
                int last = Math.min(lines.size() - 1, hit + RADIUS);
                previousEnd = last;
                if (total++ < start || rows.size() >= PAGE_ROWS) continue;
                var row = new JsonObject();
                row.addProperty("method", signature);
                row.addProperty("start", first);
                var window = new JsonArray();
                for (int i = first; i <= last; i++)
                    window.add(i + (hits.get(i) ? "* " : "  ") + lines.get(i));
                row.add("instructions", window);
                rows.add(row);
            }
        }
        if (page < 1 || (page > 1 && start >= total))
            return WenzhouReadTools.message("invalid_page", "没有这一页匹配。");
        var result = WenzhouReadTools.message("installed_code",
                "仅检查 listed_methods 中的方法和同类 LambdaMetafactory 回调。未遍历普通被调方法、其他类或脚本。* 标出命中，跳转编号属于各自方法。"
                + (fields ? "包括 JVM 字段访问与直接 CompoundTag 调用，辅助类中的存档读写须另查。" : ""));
        result.addProperty("class", type.name.replace('/', '.'));
        result.addProperty("method", root.name);
        result.addProperty("descriptor", root.desc);
        result.add("listed_methods", scope);
        result.addProperty("windows", total);
        result.addProperty("page", page);
        result.add("matches", rows);
        return result;
    }

    private static Set<MethodNode> callbacks(ClassNode type, MethodNode root) {
        var bySignature = new HashMap<String,MethodNode>();
        for (var method : type.methods) bySignature.put(method.name + method.desc, method);
        var seen = new LinkedHashSet<MethodNode>();
        var queue = new ArrayDeque<MethodNode>();
        seen.add(root);
        queue.add(root);
        while (!queue.isEmpty()) {
            for (var insn : queue.remove().instructions) {
                if (!(insn instanceof InvokeDynamicInsnNode call)
                        || !call.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                        || call.bsmArgs.length < 2 || !(call.bsmArgs[1] instanceof Handle target)
                        || !target.getOwner().equals(type.name)) continue;
                var method = bySignature.get(target.getName() + target.getDesc());
                if (method != null && seen.add(method)) queue.add(method);
            }
        }
        return seen;
    }
    private WzMethodSearch() {}
}

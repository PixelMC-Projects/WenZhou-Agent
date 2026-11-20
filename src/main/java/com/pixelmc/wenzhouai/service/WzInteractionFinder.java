package com.pixelmc.wenzhouai.service;

import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/** Reverse candidates from the installed mod's bytecode. No mod classes are loaded or executed. */
final class WzInteractionFinder {
    private record Getter(String owner, String name, String desc) {}
    private record Hit(String owner, String name, String desc, int line, String via) {}
    private static final Map<List<String>,List<Hit>> CACHE = new LinkedHashMap<>(16, .75f, true);

    static JsonObject find(Path jar, String item, int page) throws IOException {
        var key = List.of(WzJarFingerprint.of(jar), item);
        List<Hit> hits;
        synchronized (CACHE) { hits = CACHE.get(key); }
        if (hits == null) {
            hits = scan(jar, item);
            synchronized (CACHE) {
                CACHE.put(key, hits);
                if (CACHE.size() > 24) CACHE.remove(CACHE.keySet().iterator().next());
            }
        }
        var result = WenzhouReadTools.message("installed_code","本模组内直接引用或经变体字段与 getter 关联的交互候选。用 read_method 核对产物方向、玩家操作和条件；不覆盖其他模组、脚本或动态分派。");
        var rows = new JsonArray();
        hits.stream().skip((page-1L)*5).limit(5).forEach(hit -> {
            var row = new JsonObject();
            row.addProperty("class",hit.owner.replace('/','.'));
            row.addProperty("method",hit.name);
            row.addProperty("descriptor",hit.desc);
            row.addProperty("line",hit.line);
            var refs = new JsonArray(); refs.add(hit.via);
            row.add("references",refs);
            rows.add(row);
        });
        result.add("methods",rows);
        result.addProperty("total",hits.size());
        result.addProperty("page",page);
        return result;
    }

    private static List<Hit> scan(Path jar, String item) throws IOException {
        var getters = new LinkedHashMap<Getter,Set<String>>();
        var hits = new LinkedHashSet<Hit>();
        String symbol = item.replace('/','_').toUpperCase(Locale.ROOT);
        try (var zip = new ZipFile(jar.toFile())) {
            for (int pass = 0; pass < 2; pass++) {
                var owners = getters.keySet().stream().map(Getter::owner).distinct().toList();
                if (pass == 1 && owners.isEmpty()) break;
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")
                            || entry.getSize() <= 0 || entry.getSize() > 2_000_000) continue;
                    byte[] bytes;
                    try (var in = zip.getInputStream(entry)) { bytes = in.readNBytes((int)entry.getSize()); }
                    String pool = new String(bytes, StandardCharsets.ISO_8859_1);
                    if (pass == 0 && !pool.contains(symbol) && !pool.contains(item)) continue;
                    if (pass == 1 && owners.stream().noneMatch(pool::contains)) continue;
                    var type = new ClassNode();
                    new ClassReader(bytes).accept(type,ClassReader.SKIP_FRAMES);
                    for (var method : type.methods) {
                        if (pass == 0 && method.name.equals("<clinit>") && (type.access & Opcodes.ACC_ENUM) != 0)
                            variantGetters(type,method,symbol,getters);
                        if (!(method.desc.contains("Lnet/minecraft/world/entity/player/Player;")
                                || method.desc.contains("Lnet/minecraft/world/InteractionResult")
                                || method.desc.contains("PlayerInteractEvent"))) continue;
                        int line = 0;
                        for (var insn : method.instructions) {
                            if (insn instanceof LineNumberNode marker) line = marker.line;
                            if (pass == 0 && insn instanceof FieldInsnNode f && f.getOpcode()==Opcodes.GETSTATIC
                                    && f.name.equalsIgnoreCase(symbol))
                                hits.add(new Hit(type.name,method.name,method.desc,line,f.owner+"."+f.name));
                            if (pass == 1 && insn instanceof MethodInsnNode call) {
                                var paths = getters.get(new Getter(call.owner,call.name,call.desc));
                                if (paths != null) for (String path : paths)
                                    hits.add(new Hit(type.name,method.name,method.desc,line,path));
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(hits);
    }

    private static AbstractInsnNode adjacent(AbstractInsnNode n, boolean next) {
        do { n = next ? n.getNext() : n.getPrevious(); } while (n != null && n.getOpcode()<0);
        return n;
    }

    private static void variantGetters(ClassNode type, MethodNode init, String symbol, Map<Getter,Set<String>> out) {
        // Track the actual constructor argument through a cast and registry supplier dereference.
        // Adjacent enum constants and other Item fields must not become aliases of the product.
        Frame<SourceValue>[] frames;
        try { frames = new Analyzer<>(new SourceInterpreter()).analyze(type.name,init); }
        catch (AnalyzerException invalid) { return; }
        for (var insn : init.instructions) {
            if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(type.name) || !call.name.equals("<init>")) continue;
            if (!(adjacent(call,true) instanceof FieldInsnNode variant) || variant.getOpcode()!=Opcodes.PUTSTATIC
                    || !variant.owner.equals(type.name)) continue;
            var args = Type.getArgumentTypes(call.desc);
            var stack = frames[init.instructions.indexOf(call)];
            if (stack == null) continue;
            int local = 1;
            for (int i=0; i<args.length; i++) {
                var value = stack.getStack(stack.getStackSize()-args.length+i);
                if (matchesArgument(init,frames,value,symbol,0))
                    addGetters(type,call.desc,local,symbol,variant.name,out);
                local += args[i].getSize();
            }
        }
    }

    private static boolean matchesArgument(MethodNode method, Frame<SourceValue>[] frames, SourceValue value, String symbol, int depth) {
        if (depth>5 || value.insns.size()!=1) return false;
        var insn = value.insns.iterator().next();
        if (insn instanceof FieldInsnNode f) return f.getOpcode()==Opcodes.GETSTATIC && f.name.equalsIgnoreCase(symbol);
        boolean deref = insn instanceof MethodInsnNode call && call.getOpcode()!=Opcodes.INVOKESTATIC
                && call.name.equals("get") && call.desc.startsWith("()");
        if (!deref && insn.getOpcode()!=Opcodes.CHECKCAST) return false;
        var frame = frames[method.instructions.indexOf(insn)];
        return frame!=null && frame.getStackSize()>0 && matchesArgument(method,frames,frame.getStack(frame.getStackSize()-1),symbol,depth+1);
    }

    private static void addGetters(ClassNode type, String descriptor, int slot, String symbol, String variant, Map<Getter,Set<String>> out) {
        for (var ctor : type.methods) {
            if (!ctor.name.equals("<init>") || !ctor.desc.equals(descriptor)) continue;
            for (var node : ctor.instructions) {
                if (!(node instanceof FieldInsnNode field) || field.getOpcode()!=Opcodes.PUTFIELD || !field.owner.equals(type.name)) continue;
                if (!(adjacent(field,false) instanceof VarInsnNode load) || load.getOpcode()!=Opcodes.ALOAD || load.var!=slot) continue;
                for (var getter : type.methods) {
                    if (!getter.desc.equals("()"+field.desc)) continue;
                    for (var ret : getter.instructions) {
                        if (ret.getOpcode()!=Opcodes.ARETURN || !(adjacent(ret,false) instanceof FieldInsnNode read)
                                || read.getOpcode()!=Opcodes.GETFIELD || !read.owner.equals(type.name) || !read.name.equals(field.name)) continue;
                        out.computeIfAbsent(new Getter(type.name,getter.name,getter.desc),unused->new LinkedHashSet<>())
                                .add(symbol+" → "+type.name+"."+variant+"."+field.name+" → "+getter.name+getter.desc);
                    }
                }
            }
        }
    }
    private WzInteractionFinder() {}
}

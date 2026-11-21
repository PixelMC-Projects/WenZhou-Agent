package com.pixelmc.wenzhouai.service;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Trace the state passed to a tree decorator's placement call. Never run the decorator against a world. */
final class WzDecoratorOutputs {
    private static final String CONTEXT = "net/minecraft/world/level/levelgen/feature/treedecorators/TreeDecorator$Context";
    private static final String STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String REGISTRY = "net/minecraftforge/registries/RegistryObject";
    private static final String WRITE_DESC = "(Lnet/minecraft/core/BlockPos;L" + STATE + ";)V";

    static Set<String> find(Class<?> decorator) {
        for (Class<?> c = decorator; c != null && c != TreeDecorator.class; c = c.getSuperclass()) {
            String owner = Type.getInternalName(c);
            try (var input = c.getResourceAsStream("/" + owner + ".class")) {
                if (input == null) return Set.of();
                var node = new ClassNode();
                new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                var methods = new HashMap<String, MethodNode>();
                var todo = new ArrayDeque<MethodNode>();
                for (var m : node.methods) {
                    methods.put(m.name + m.desc, m);
                    if ((m.name.equals("place") || m.name.equals("m_214187_"))
                            && m.desc.equals("(L" + CONTEXT + ";)V")) todo.add(m);
                }
                if (todo.isEmpty()) continue;
                var blocks = new TreeSet<String>();
                var visited = new HashSet<MethodNode>();
                while (!todo.isEmpty()) {
                    var method = todo.remove();
                    if (!visited.add(method)) continue;
                    boolean writes = false;
                    for (var insn : method.instructions) {
                        if (insn instanceof MethodInsnNode call) {
                            writes |= isWrite(call);
                            if (call.owner.equals(owner)) addMethod(todo, methods, call.name, call.desc);
                        } else if (insn instanceof InvokeDynamicInsnNode dynamic
                                && dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
                            for (var arg : dynamic.bsmArgs) if (arg instanceof Handle h && h.getOwner().equals(owner))
                                addMethod(todo, methods, h.getName(), h.getDesc());
                        }
                    }
                    if (!writes) continue;
                    var frames = new Analyzer<>(new Origins()).analyze(owner, method);
                    for (int i = 0; i < frames.length; i++) {
                        var frame = frames[i];
                        if (frame == null || !(method.instructions.get(i) instanceof MethodInsnNode call) || !isWrite(call)) continue;
                        var value = frame.getStack(frame.getStackSize() - 1);
                        for (var origin : value.insns) if (origin instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) {
                            var id = registeredBlock(c.getClassLoader(), field);
                            if (id != null) blocks.add(id);
                        }
                    }
                }
                return Set.copyOf(blocks);
            } catch (IOException | AnalyzerException | LinkageError | RuntimeException unsupported) {
                return Set.of();
            }
        }
        return Set.of();
    }

    private static void addMethod(Deque<MethodNode> todo, Map<String,MethodNode> methods, String name, String desc) {
        var target = methods.get(name + desc);
        if (target != null) todo.add(target);
    }

    private static boolean isWrite(MethodInsnNode call) {
        return call.owner.equals(CONTEXT) && call.desc.equals(WRITE_DESC)
                && (call.name.equals("setBlock") || call.name.equals("m_226061_"));
    }

    private static String registeredBlock(ClassLoader loader, FieldInsnNode insn) {
        try {
            var owner = Class.forName(insn.owner.replace('/', '.'), false, loader);
            var field = owner.getField(insn.name);
            if (!Modifier.isStatic(field.getModifiers())) return null;
            if (field.getType() != RegistryObject.class && !Block.class.isAssignableFrom(field.getType())) return null;
            var value = field.get(null);
            if (value instanceof RegistryObject<?> handle && handle.isPresent()) value = handle.get();
            if (!(value instanceof Block block)) return null;
            var id = ForgeRegistries.BLOCKS.getKey(block);
            return id != null && ForgeRegistries.BLOCKS.getValue(id) == block ? id.toString() : null;
        } catch (ReflectiveOperationException | LinkageError unavailable) { return null; }
    }

    /** Preserve origin through moves, casts, RegistryObject.get, defaultBlockState and property updates only. */
    private static final class Origins extends SourceInterpreter {
        Origins() { super(Opcodes.ASM9); }
        @Override public SourceValue copyOperation(AbstractInsnNode insn, SourceValue value) { return value; }
        @Override public SourceValue unaryOperation(AbstractInsnNode insn, SourceValue value) {
            return insn.getOpcode() == Opcodes.CHECKCAST ? value : super.unaryOperation(insn, value);
        }
        @Override public SourceValue naryOperation(AbstractInsnNode insn, List<? extends SourceValue> values) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESTATIC && !values.isEmpty()) {
                boolean get = call.owner.equals(REGISTRY) && call.name.equals("get") && call.desc.equals("()Ljava/lang/Object;");
                boolean state = (call.name.equals("defaultBlockState") || call.name.equals("m_49966_"))
                        && call.desc.equals("()L" + STATE + ";") && call.owner.equals(BLOCK);
                boolean property = (call.owner.equals(STATE) || call.owner.equals("net/minecraft/world/level/block/state/StateHolder"))
                        && (call.name.equals("setValue") || call.name.equals("m_61124_"))
                        && call.desc.equals("(Lnet/minecraft/world/level/block/state/properties/Property;Ljava/lang/Comparable;)Ljava/lang/Object;");
                if (get || state || property) return values.get(0);
            }
            return super.naryOperation(insn, values);
        }
    }

    private WzDecoratorOutputs() {}
}

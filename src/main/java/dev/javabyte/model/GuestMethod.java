package dev.javabyte.model;

import java.util.IdentityHashMap;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * An interpreted method, pre-digested for fast execution.
 *
 * <p>ASM's tree form addresses jump targets and exception ranges by label object. Resolving those
 * on every branch would dominate run time, so the constructor converts them once into plain
 * instruction indexes.
 */
public final class GuestMethod {

    /** Exception table entry in instruction indexes; {@code type} is null for catch-all (finally). */
    public record Handler(int start, int end, int target, String type) {}

    public final GuestClass owner;
    public final MethodNode node;
    public final String name;
    public final String desc;
    /** {@code name + desc}: unique within a class. */
    public final String key;
    /**
     * {@code owner.name + '.' + name + desc} (e.g. {@code com/example/Foo.bar(I)V}): unique within
     * a run; the form used in hooks, traces and reports.
     */
    public final String id;

    public final Type[] argTypes;
    public final Type returnType;
    public final boolean isStatic;
    public final boolean isNative;
    public final boolean isAbstract;
    public final boolean isSynchronized;

    public final AbstractInsnNode[] code;
    /** Jump target index for the {@link JumpInsnNode} at the same index; unused elsewhere. */
    public final int[] jumpTargets;
    /** Source line for each instruction, or -1 when the class carries no line table. */
    public final int[] lines;
    public final Handler[] handlers;
    /**
     * Per-instruction scratch slot. The interpreter caches resolved call sites, fields and
     * constants here so each instruction is resolved once rather than on every execution.
     */
    public final Object[] aux;

    private final Map<LabelNode, Integer> labelIndex = new IdentityHashMap<>();

    /** Config-driven override, or null. Typed loosely to keep the model free of config classes. */
    public Object hook;
    public boolean traceCalls;
    public boolean traceInstructions;

    /**
     * Prepares a method for the interpreter: its instructions as an array, jump targets and
     * handlers as indexes into it.
     */
    GuestMethod(GuestClass owner, MethodNode node) {
        this.owner = owner;
        this.node = node;
        this.name = node.name;
        this.desc = node.desc;
        this.key = node.name + node.desc;
        this.id = owner.name + '.' + key;
        this.argTypes = Type.getArgumentTypes(node.desc);
        this.returnType = Type.getReturnType(node.desc);
        this.isStatic = (node.access & Opcodes.ACC_STATIC) != 0;
        this.isNative = (node.access & Opcodes.ACC_NATIVE) != 0;
        this.isAbstract = (node.access & Opcodes.ACC_ABSTRACT) != 0;
        this.isSynchronized = (node.access & Opcodes.ACC_SYNCHRONIZED) != 0;

        this.code = node.instructions.toArray();
        this.jumpTargets = new int[code.length];
        this.lines = new int[code.length];
        this.aux = new Object[code.length];

        int line = -1;
        for (int i = 0; i < code.length; i++) {
            if (code[i] instanceof LabelNode label) {
                labelIndex.put(label, i);
            }
        }
        for (int i = 0; i < code.length; i++) {
            if (code[i] instanceof LineNumberNode lineNode) {
                line = lineNode.line;
            } else if (code[i] instanceof JumpInsnNode jump) {
                jumpTargets[i] = indexOf(jump.label);
            }
            lines[i] = line;
        }

        this.handlers = new Handler[node.tryCatchBlocks == null ? 0 : node.tryCatchBlocks.size()];
        for (int i = 0; i < handlers.length; i++) {
            TryCatchBlockNode block = node.tryCatchBlocks.get(i);
            handlers[i] = new Handler(indexOf(block.start), indexOf(block.end), indexOf(block.handler), block.type);
        }
    }

    /** Instruction index of a label; used for switch targets, which are resolved lazily. */
    public int indexOf(LabelNode label) {
        Integer index = labelIndex.get(label);
        if (index == null) {
            throw new IllegalStateException("Label outside method body in " + id);
        }
        return index;
    }

    public boolean isConstructor() {
        return "<init>".equals(name);
    }

    @Override
    public String toString() {
        return id;
    }
}

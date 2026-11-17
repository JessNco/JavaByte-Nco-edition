package dev.javabyte.vm;

import static org.objectweb.asm.Opcodes.*;

import dev.javabyte.model.FieldRef;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestMethod;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.Printer;

/**
 * Executes guest bytecode one instruction at a time.
 *
 * <p>The interpreter is recursive: each guest call is a Java call to {@link #execute}. That keeps
 * the code close to the JVM specification and lets real JDK code sit between two guest frames
 * (a JDK sort calling a guest comparator, for example) without any special machinery. The cost,
 * host stack depth, is handled by running on a thread with a very large stack.
 *
 * <p>Nothing here touches the outside world. Every call leaves through
 * {@link InvocationRouter}, which is where policy is enforced.
 */
public final class Interpreter {

    /** Marks an instruction's cached field resolution as "not a guest field". */
    private static final Object HOST_FIELD = new Object();

    /** Return address pushed by the legacy {@code JSR} opcode. */
    private record ReturnAddress(int pc) {}

    /** Pre-resolved targets of a switch instruction. */
    private record SwitchTable(int defaultTarget, int[] keys, int[] targets) {}

    private final VirtualMachine vm;

    Interpreter(VirtualMachine vm) {
        this.vm = vm;
    }

    /**
     * Runs one guest method to completion.
     *
     * @param receiver {@code this} for instance methods, ignored for static ones
     * @return the method's result in interpreter representation, or null for void
     * @throws GuestThrow if the method completes by throwing
     */
    Object execute(GuestMethod method, Object receiver, Object[] args) {
        Frame frame = new Frame(method);
        int slot = 0;
        if (!method.isStatic) {
            frame.locals[slot++] = receiver;
        }
        for (int i = 0; i < args.length; i++) {
            frame.locals[slot] = args[i];
            slot += method.argTypes[i].getSize();
        }
        // The stack is captured once: after a thread switch "the current stack" is another
        // thread's, and this frame must be removed from the one it was pushed on.
        java.util.List<Frame> stack = vm.currentStack();
        int depth = stack.size();
        vm.pushFrame(frame);
        try {
            return run(frame);
        } finally {
            vm.popFrame(stack, depth);
        }
    }

    @SuppressWarnings("fallthrough")
    private Object run(Frame frame) {
        final GuestMethod method = frame.method;
        final AbstractInsnNode[] code = method.code;
        final Object[] stack = frame.stack;
        final Object[] locals = frame.locals;
        final boolean tracing = method.traceInstructions;
        int sp = 0;
        int pc = 0;

        while (true) {
            try {
                while (true) {
                    AbstractInsnNode insn = code[pc];
                    int op = insn.getOpcode();
                    if (op < 0) { // label, line number or frame pseudo-instruction
                        pc++;
                        continue;
                    }
                    frame.pc = pc;
                    vm.countInstruction();
                    if (tracing) {
                        vm.events.writeTrace("    " + method.name + '@' + pc + ' ' + Printer.OPCODES[op]
                                + (sp > 0 ? "  top=" + Values.describe(stack[sp - 1]) : ""));
                    }

                    switch (op) {
                        case NOP:
                            break;

                        // ---- constants
                        case ACONST_NULL:
                            stack[sp++] = null;
                            break;
                        case ICONST_M1: case ICONST_0: case ICONST_1: case ICONST_2:
                        case ICONST_3: case ICONST_4: case ICONST_5:
                            stack[sp++] = op - ICONST_0;
                            break;
                        case LCONST_0: case LCONST_1:
                            stack[sp++] = (long) (op - LCONST_0);
                            break;
                        case FCONST_0: case FCONST_1: case FCONST_2:
                            stack[sp++] = (float) (op - FCONST_0);
                            break;
                        case DCONST_0: case DCONST_1:
                            stack[sp++] = (double) (op - DCONST_0);
                            break;
                        case BIPUSH: case SIPUSH:
                            stack[sp++] = ((IntInsnNode) insn).operand;
                            break;
                        case LDC:
                            stack[sp++] = constant(method, pc, (LdcInsnNode) insn);
                            break;

                        // ---- locals
                        case ILOAD: case LLOAD: case FLOAD: case DLOAD: case ALOAD:
                            stack[sp++] = locals[((VarInsnNode) insn).var];
                            break;
                        case ISTORE: case LSTORE: case FSTORE: case DSTORE: case ASTORE:
                            locals[((VarInsnNode) insn).var] = stack[--sp];
                            break;
                        case IINC: {
                            IincInsnNode iinc = (IincInsnNode) insn;
                            locals[iinc.var] = (Integer) locals[iinc.var] + iinc.incr;
                            break;
                        }

                        // ---- arrays
                        case IALOAD: case LALOAD: case FALOAD: case DALOAD:
                        case AALOAD: case BALOAD: case CALOAD: case SALOAD: {
                            int index = (Integer) stack[--sp];
                            stack[sp - 1] = Arrays.load(vm, stack[sp - 1], index);
                            break;
                        }
                        case IASTORE: case LASTORE: case FASTORE: case DASTORE:
                        case AASTORE: case BASTORE: case CASTORE: case SASTORE: {
                            Object value = stack[--sp];
                            int index = (Integer) stack[--sp];
                            Arrays.store(vm, stack[--sp], index, value);
                            break;
                        }
                        case ARRAYLENGTH:
                            stack[sp - 1] = Arrays.length(vm, stack[sp - 1]);
                            break;
                        case NEWARRAY:
                            stack[sp - 1] = Arrays.newPrimitive(vm, ((IntInsnNode) insn).operand, (Integer) stack[sp - 1]);
                            break;
                        case ANEWARRAY:
                            stack[sp - 1] = Arrays.newReference(vm, ((TypeInsnNode) insn).desc, (Integer) stack[sp - 1]);
                            break;
                        case MULTIANEWARRAY: {
                            MultiANewArrayInsnNode multi = (MultiANewArrayInsnNode) insn;
                            int[] dims = new int[multi.dims];
                            for (int i = multi.dims - 1; i >= 0; i--) {
                                dims[i] = (Integer) stack[--sp];
                            }
                            stack[sp++] = Arrays.newMulti(vm, multi.desc, dims);
                            break;
                        }

                        // ---- operand stack shuffling (width-dependent forms follow JVMS 6.5)
                        case POP:
                            sp--;
                            break;
                        case POP2:
                            sp -= Values.isWide(stack[sp - 1]) ? 1 : 2;
                            break;
                        case DUP:
                            stack[sp] = stack[sp - 1];
                            sp++;
                            break;
                        case DUP_X1: {
                            Object v1 = stack[sp - 1];
                            stack[sp - 1] = stack[sp - 2];
                            stack[sp - 2] = v1;
                            stack[sp++] = v1;
                            break;
                        }
                        case DUP_X2:
                            sp = insertBelow(stack, sp, 1, Values.isWide(stack[sp - 2]) ? 1 : 2);
                            break;
                        case DUP2:
                            if (Values.isWide(stack[sp - 1])) {
                                stack[sp] = stack[sp - 1];
                                sp++;
                            } else {
                                stack[sp] = stack[sp - 2];
                                stack[sp + 1] = stack[sp - 1];
                                sp += 2;
                            }
                            break;
                        case DUP2_X1:
                            sp = insertBelow(stack, sp, Values.isWide(stack[sp - 1]) ? 1 : 2, 1);
                            break;
                        case DUP2_X2: {
                            int top = Values.isWide(stack[sp - 1]) ? 1 : 2;
                            int below = Values.isWide(stack[sp - 1 - top]) ? 1 : 2;
                            sp = insertBelow(stack, sp, top, below);
                            break;
                        }
                        case SWAP: {
                            Object v1 = stack[sp - 1];
                            stack[sp - 1] = stack[sp - 2];
                            stack[sp - 2] = v1;
                            break;
                        }

                        // ---- arithmetic
                        case IADD: case ISUB: case IMUL: case IDIV: case IREM: case IAND:
                        case IOR: case IXOR: case ISHL: case ISHR: case IUSHR: {
                            int right = (Integer) stack[--sp];
                            stack[sp - 1] = intOp(op, (Integer) stack[sp - 1], right);
                            break;
                        }
                        case LADD: case LSUB: case LMUL: case LDIV: case LREM:
                        case LAND: case LOR: case LXOR: {
                            long right = (Long) stack[--sp];
                            stack[sp - 1] = longOp(op, (Long) stack[sp - 1], right);
                            break;
                        }
                        case LSHL: case LSHR: case LUSHR: {
                            int shift = (Integer) stack[--sp];
                            long left = (Long) stack[sp - 1];
                            stack[sp - 1] = op == LSHL ? left << shift : op == LSHR ? left >> shift : left >>> shift;
                            break;
                        }
                        case FADD: case FSUB: case FMUL: case FDIV: case FREM: {
                            float right = (Float) stack[--sp];
                            float left = (Float) stack[sp - 1];
                            stack[sp - 1] = op == FADD ? left + right : op == FSUB ? left - right
                                    : op == FMUL ? left * right : op == FDIV ? left / right : left % right;
                            break;
                        }
                        case DADD: case DSUB: case DMUL: case DDIV: case DREM: {
                            double right = (Double) stack[--sp];
                            double left = (Double) stack[sp - 1];
                            stack[sp - 1] = op == DADD ? left + right : op == DSUB ? left - right
                                    : op == DMUL ? left * right : op == DDIV ? left / right : left % right;
                            break;
                        }
                        case INEG: stack[sp - 1] = -(Integer) stack[sp - 1]; break;
                        case LNEG: stack[sp - 1] = -(Long) stack[sp - 1]; break;
                        case FNEG: stack[sp - 1] = -(Float) stack[sp - 1]; break;
                        case DNEG: stack[sp - 1] = -(Double) stack[sp - 1]; break;

                        // ---- conversions (Java casts have the same saturating semantics as the JVM)
                        case I2L: stack[sp - 1] = (long) (Integer) stack[sp - 1]; break;
                        case I2F: stack[sp - 1] = (float) (Integer) stack[sp - 1]; break;
                        case I2D: stack[sp - 1] = (double) (Integer) stack[sp - 1]; break;
                        case L2I: stack[sp - 1] = (int) (long) (Long) stack[sp - 1]; break;
                        case L2F: stack[sp - 1] = (float) (Long) stack[sp - 1]; break;
                        case L2D: stack[sp - 1] = (double) (Long) stack[sp - 1]; break;
                        case F2I: stack[sp - 1] = (int) (float) (Float) stack[sp - 1]; break;
                        case F2L: stack[sp - 1] = (long) (float) (Float) stack[sp - 1]; break;
                        case F2D: stack[sp - 1] = (double) (Float) stack[sp - 1]; break;
                        case D2I: stack[sp - 1] = (int) (double) (Double) stack[sp - 1]; break;
                        case D2L: stack[sp - 1] = (long) (double) (Double) stack[sp - 1]; break;
                        case D2F: stack[sp - 1] = (float) (double) (Double) stack[sp - 1]; break;
                        case I2B: stack[sp - 1] = (int) (byte) (int) (Integer) stack[sp - 1]; break;
                        case I2C: stack[sp - 1] = (int) (char) (int) (Integer) stack[sp - 1]; break;
                        case I2S: stack[sp - 1] = (int) (short) (int) (Integer) stack[sp - 1]; break;

                        // ---- comparisons
                        case LCMP: {
                            long right = (Long) stack[--sp];
                            stack[sp - 1] = Long.compare((Long) stack[sp - 1], right);
                            break;
                        }
                        case FCMPL: case FCMPG: {
                            float right = (Float) stack[--sp];
                            float left = (Float) stack[sp - 1];
                            // The two variants differ only in what NaN compares as.
                            stack[sp - 1] = Float.isNaN(left) || Float.isNaN(right)
                                    ? (op == FCMPG ? 1 : -1) : left == right ? 0 : left < right ? -1 : 1;
                            break;
                        }
                        case DCMPL: case DCMPG: {
                            double right = (Double) stack[--sp];
                            double left = (Double) stack[sp - 1];
                            stack[sp - 1] = Double.isNaN(left) || Double.isNaN(right)
                                    ? (op == DCMPG ? 1 : -1) : left == right ? 0 : left < right ? -1 : 1;
                            break;
                        }

                        // ---- branches
                        case IFEQ: case IFNE: case IFLT: case IFGE: case IFGT: case IFLE:
                            if (intCondition(op - IFEQ, (Integer) stack[--sp], 0)) {
                                pc = method.jumpTargets[pc];
                                continue;
                            }
                            break;
                        case IF_ICMPEQ: case IF_ICMPNE: case IF_ICMPLT:
                        case IF_ICMPGE: case IF_ICMPGT: case IF_ICMPLE: {
                            int right = (Integer) stack[--sp];
                            if (intCondition(op - IF_ICMPEQ, (Integer) stack[--sp], right)) {
                                pc = method.jumpTargets[pc];
                                continue;
                            }
                            break;
                        }
                        case IF_ACMPEQ: case IF_ACMPNE: {
                            Object right = stack[--sp];
                            if ((stack[--sp] == right) == (op == IF_ACMPEQ)) {
                                pc = method.jumpTargets[pc];
                                continue;
                            }
                            break;
                        }
                        case IFNULL: case IFNONNULL:
                            if ((stack[--sp] == null) == (op == IFNULL)) {
                                pc = method.jumpTargets[pc];
                                continue;
                            }
                            break;
                        case GOTO:
                            pc = method.jumpTargets[pc];
                            continue;
                        case JSR:
                            stack[sp++] = new ReturnAddress(pc + 1);
                            pc = method.jumpTargets[pc];
                            continue;
                        case RET:
                            pc = ((ReturnAddress) locals[((VarInsnNode) insn).var]).pc();
                            continue;
                        case TABLESWITCH: case LOOKUPSWITCH:
                            pc = switchTarget(method, pc, insn, (Integer) stack[--sp]);
                            continue;

                        // ---- returns
                        case IRETURN: case LRETURN: case FRETURN: case DRETURN: case ARETURN:
                            return stack[--sp];
                        case RETURN:
                            return null;

                        // ---- fields
                        case GETSTATIC: {
                            FieldInsnNode field = (FieldInsnNode) insn;
                            stack[sp++] = resolveField(method, pc, field) instanceof FieldRef ref
                                    ? vm.fields.getStatic(ref)
                                    : vm.fields.getHostStatic(field.owner, field.name, field.desc);
                            break;
                        }
                        case PUTSTATIC: {
                            FieldInsnNode field = (FieldInsnNode) insn;
                            Object value = stack[--sp];
                            if (resolveField(method, pc, field) instanceof FieldRef ref) {
                                vm.fields.putStatic(ref, value);
                            } else {
                                vm.fields.putHostStatic(field.owner, field.name, field.desc, value);
                            }
                            break;
                        }
                        case GETFIELD: {
                            FieldInsnNode field = (FieldInsnNode) insn;
                            Object ref = resolveField(method, pc, field);
                            stack[sp - 1] = vm.fields.get(stack[sp - 1], ref instanceof FieldRef f ? f : null, field);
                            break;
                        }
                        case PUTFIELD: {
                            FieldInsnNode field = (FieldInsnNode) insn;
                            Object ref = resolveField(method, pc, field);
                            Object value = stack[--sp];
                            vm.fields.put(stack[--sp], ref instanceof FieldRef f ? f : null, field, value);
                            break;
                        }

                        // ---- calls
                        case INVOKEVIRTUAL: case INVOKESPECIAL: case INVOKESTATIC: case INVOKEINTERFACE: {
                            CallSite site = (CallSite) method.aux[pc];
                            if (site == null) {
                                MethodInsnNode call = (MethodInsnNode) insn;
                                method.aux[pc] = site = CallSite.of(call.owner, call.name, call.desc);
                            }
                            Object[] args = new Object[site.argCount()];
                            for (int i = args.length - 1; i >= 0; i--) {
                                args[i] = stack[--sp];
                            }
                            Object receiver = op == INVOKESTATIC ? null : stack[--sp];
                            Object result = vm.router.invoke(op, site.owner(), site.name(), site.desc(), receiver, args);
                            if (site.isConstructor()) {
                                frame.replaceResolved(sp);
                            } else if (!site.returnsVoid()) {
                                stack[sp++] = result;
                            }
                            break;
                        }
                        case INVOKEDYNAMIC: {
                            InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                            Object[] args = new Object[Type.getArgumentTypes(indy.desc).length];
                            for (int i = args.length - 1; i >= 0; i--) {
                                args[i] = stack[--sp];
                            }
                            Object result = vm.indy.invoke(method, pc, indy, args);
                            if (Type.getReturnType(indy.desc).getSort() != Type.VOID) {
                                stack[sp++] = result;
                            }
                            break;
                        }

                        // ---- objects and types
                        case NEW:
                            stack[sp++] = vm.allocate(((TypeInsnNode) insn).desc);
                            break;
                        case ATHROW: {
                            Object thrown = stack[--sp];
                            if (thrown == null) {
                                throw vm.raise(new NullPointerException("Cannot throw exception because value is null"));
                            }
                            throw new GuestThrow(thrown);
                        }
                        case CHECKCAST: {
                            String type = ((TypeInsnNode) insn).desc;
                            Object value = stack[sp - 1];
                            if (value != null && !vm.types.isInstance(value, type)) {
                                throw vm.raise(new ClassCastException("class " + vm.types.nameOf(value)
                                        + " cannot be cast to class " + type.replace('/', '.')));
                            }
                            break;
                        }
                        case INSTANCEOF: {
                            Object value = stack[sp - 1];
                            stack[sp - 1] = value != null && vm.types.isInstance(value, ((TypeInsnNode) insn).desc) ? 1 : 0;
                            break;
                        }
                        case MONITORENTER: case MONITOREXIT: {
                            Object lock = stack[--sp];
                            if (lock == null) {
                                throw vm.raise(new NullPointerException("Cannot enter synchronized block because value is null"));
                            }
                            if (op == MONITORENTER) {
                                vm.scheduler.monitorEnter(lock);
                            } else {
                                vm.scheduler.monitorExit(lock);
                            }
                            break;
                        }

                        default:
                            throw new IllegalStateException("Unsupported opcode " + op + " in " + method.id);
                    }
                    pc++;
                }
            } catch (GuestThrow thrown) {
                int handler = findHandler(method, frame.pc, thrown.value);
                if (handler < 0) {
                    throw thrown;
                }
                // Entering a handler discards the operand stack and leaves only the exception on it.
                stack[0] = thrown.value;
                sp = 1;
                pc = handler;
            } catch (RuntimeException failure) {
                GuestThrow thrown = invalidBytecode(frame, pc, failure);
                int handler = findHandler(method, frame.pc, thrown.value);
                if (handler < 0) {
                    throw thrown;
                }
                stack[0] = thrown.value;
                sp = 1;
                pc = handler;
            }
        }
    }

    /**
     * Decides whether a host exception inside the interpreter loop was caused by bytecode that
     * a JVM would have refused to load, and if so turns it into the {@code VerifyError} the
     * sample would have received.
     *
     * <p>There is deliberately no verifier pass: samples run code that standard verifiers
     * reject yet HotSpot accepts (old class versions, {@code -noverify} launchers), and a
     * second, stricter judge would stop analysis of exactly the samples worth analysing.
     * Instead the interpreter runs unchecked and its own type and bounds failures are read as
     * the verdict. Those surface as a small set of exception types raised by the emulator's own
     * code or by the reflection it uses to reach the JDK (wrong argument types); anything else
     * is still an emulator defect and is rethrown unchanged.
     *
     * <p>The risk is that a real interpreter bug is reported as invalid bytecode. Two things
     * keep that visible: the {@code gap.invalid-bytecode} event carries the host exception, and
     * the differential tests require valid programs to finish with no gap events at all.
     */
    /** Packages whose frames may sit between a type or bounds failure and the interpreter loop. */
    private static final String[] CORE_FRAME_PREFIXES = {
        "dev.javabyte.", "java.lang.reflect.", "jdk.internal.reflect.", "java.lang.invoke.",
        // ASM parses descriptors lazily, so a corrupted constant pool is only noticed here.
        "org.objectweb.asm.",
    };

    /**
     * True if every frame from the point of failure down to the interpreter loop belongs to
     * the emulator or to the reflection machinery, so that no JDK library logic was involved.
     */
    private static boolean raisedByEmulatorCore(StackTraceElement[] trace) {
        // An empty trace is HotSpot's pre-allocated exception for implicit checks in compiled
        // code (OmitStackTraceInFastThrow): by then the interpreter loop is the hot code.
        for (int i = 0; i < trace.length; i++) {
            String frameClass = trace[i].getClassName();
            if (frameClass.equals(Interpreter.class.getName())) {
                return true;
            }
            boolean core = false;
            for (String prefix : CORE_FRAME_PREFIXES) {
                core |= frameClass.startsWith(prefix);
            }
            // System.arraycopy, Class.cast, String.charAt, Objects.checkIndex and similar checks
            // that the emulator's own code runs into. JDK calls made on the sample's behalf do
            // not arrive here: the host bridge has already turned their failures into guest
            // exceptions.
            boolean leafCheck = frameClass.startsWith("java.lang.") || frameClass.startsWith("java.util.Objects")
                    || frameClass.startsWith("jdk.internal.util.");
            if (!core && !leafCheck) {
                return false;
            }
        }
        return true;
    }

    private GuestThrow invalidBytecode(Frame frame, int pc, RuntimeException failure) {
        boolean typeOrBounds = failure instanceof ClassCastException || failure instanceof NullPointerException
                || failure instanceof IndexOutOfBoundsException || failure instanceof NegativeArraySizeException
                || failure instanceof ArrayStoreException || failure instanceof IllegalStateException
                || failure instanceof IllegalArgumentException;
        boolean fromCore = raisedByEmulatorCore(failure.getStackTrace());
        if (!typeOrBounds || !fromCore || failure instanceof dev.javabyte.config.ConfigLoader.ConfigException) {
            throw failure;
        }
        GuestMethod method = frame.method;
        int opcode = pc >= 0 && pc < method.code.length ? method.code[pc].getOpcode() : -1;
        String where = method.id + " instruction " + pc
                + (opcode >= 0 ? " (" + org.objectweb.asm.util.Printer.OPCODES[opcode] + ")" : " (outside the method)");
        vm.events.addOnce("invalid:" + method.id, "gap", "invalid-bytecode", "method", method.id, "instruction", pc,
                "opcode", opcode >= 0 ? org.objectweb.asm.util.Printer.OPCODES[opcode] : null, "hostFailure", failure.toString());
        return vm.raise(new VerifyError("Bad bytecode in " + where));
    }

    /**
     * Implements the DUP_X and DUP2_X family: copies the top {@code count} entries and inserts the
     * copy beneath the next {@code below} entries.
     */
    private static int insertBelow(Object[] stack, int sp, int count, int below) {
        int span = count + below;
        System.arraycopy(stack, sp - span, stack, sp - span + count, span);
        System.arraycopy(stack, sp, stack, sp - span, count);
        return sp + count;
    }

    private int intOp(int op, int left, int right) {
        switch (op) {
            case IADD: return left + right;
            case ISUB: return left - right;
            case IMUL: return left * right;
            case IDIV:
            case IREM:
                if (right == 0) {
                    throw vm.raise(new ArithmeticException("/ by zero"));
                }
                return op == IDIV ? left / right : left % right;
            case IAND: return left & right;
            case IOR: return left | right;
            case IXOR: return left ^ right;
            case ISHL: return left << right;
            case ISHR: return left >> right;
            default: return left >>> right;
        }
    }

    private long longOp(int op, long left, long right) {
        switch (op) {
            case LADD: return left + right;
            case LSUB: return left - right;
            case LMUL: return left * right;
            case LDIV:
            case LREM:
                if (right == 0) {
                    throw vm.raise(new ArithmeticException("/ by zero"));
                }
                return op == LDIV ? left / right : left % right;
            case LAND: return left & right;
            case LOR: return left | right;
            default: return left ^ right;
        }
    }

    /** {@code kind} is the opcode's offset from IFEQ / IF_ICMPEQ: EQ, NE, LT, GE, GT, LE. */
    private static boolean intCondition(int kind, int left, int right) {
        switch (kind) {
            case 0: return left == right;
            case 1: return left != right;
            case 2: return left < right;
            case 3: return left >= right;
            case 4: return left > right;
            default: return left <= right;
        }
    }

    private static int switchTarget(GuestMethod method, int pc, AbstractInsnNode insn, int key) {
        SwitchTable table = (SwitchTable) method.aux[pc];
        if (table == null) {
            method.aux[pc] = table = buildSwitchTable(method, insn);
        }
        int index = java.util.Arrays.binarySearch(table.keys(), key);
        return index >= 0 ? table.targets()[index] : table.defaultTarget();
    }

    private static SwitchTable buildSwitchTable(GuestMethod method, AbstractInsnNode insn) {
        if (insn instanceof TableSwitchInsnNode table) {
            int[] keys = new int[table.labels.size()];
            int[] targets = new int[keys.length];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = table.min + i;
                targets[i] = method.indexOf(table.labels.get(i));
            }
            return new SwitchTable(method.indexOf(table.dflt), keys, targets);
        }
        LookupSwitchInsnNode lookup = (LookupSwitchInsnNode) insn;
        // The class-file format requires lookupswitch keys to be sorted, so binary search is valid.
        int[] keys = lookup.keys.stream().mapToInt(Integer::intValue).toArray();
        int[] targets = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            targets[i] = method.indexOf(lookup.labels.get(i));
        }
        return new SwitchTable(method.indexOf(lookup.dflt), keys, targets);
    }

    private Object constant(GuestMethod method, int pc, LdcInsnNode ldc) {
        Object cached = method.aux[pc];
        if (cached != null) {
            return cached;
        }
        Object value = ldc.cst;
        if (value instanceof String text) {
            // The JVM interns string constants, and obfuscated code does compare them with ==.
            value = text.intern();
        } else if (!(value instanceof Number)) {
            // Class literals, method types, method handles and dynamic constants.
            value = vm.indy.loadConstant(method, value);
        }
        if (value != null) {
            method.aux[pc] = value;
        }
        return value;
    }

    /** Resolves a field instruction once; returns a {@link FieldRef} or {@link #HOST_FIELD}. */
    private Object resolveField(GuestMethod method, int pc, FieldInsnNode field) {
        Object cached = method.aux[pc];
        if (cached == null) {
            GuestClass owner = vm.classes.find(field.owner);
            FieldRef ref = owner == null ? null : owner.resolveField(field.name, field.desc);
            method.aux[pc] = cached = ref != null ? ref : HOST_FIELD;
        }
        return cached;
    }

    /** Finds the first exception table entry covering {@code pc} whose type matches, or -1. */
    private int findHandler(GuestMethod method, int pc, Object thrown) {
        for (GuestMethod.Handler handler : method.handlers) {
            if (pc >= handler.start() && pc < handler.end()
                    && (handler.type() == null || vm.types.isInstance(thrown, handler.type()))) {
                return handler.target();
            }
        }
        return -1;
    }
}

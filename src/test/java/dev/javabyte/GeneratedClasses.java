package dev.javabyte;

import static org.objectweb.asm.Opcodes.*;

import dev.javabyte.guest.sim.IndyTargets;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Class files written with ASM for the tests, covering bytecode that the Java compiler never
 * or rarely emits but that obfuscators and old compilers do.
 *
 * <p>Each generated class has {@code public static void main(String[])} that prints its
 * results, so one check suffices: the emulator must print what the real JVM prints.
 */
final class GeneratedClasses {

    private GeneratedClasses() {}

    private static final String PRINT_STREAM = "java/io/PrintStream";

    private static ClassWriter begin(String name, int version) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(version, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        return writer;
    }

    private static MethodVisitor beginMain(ClassWriter writer) {
        MethodVisitor main = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        return main;
    }

    private static byte[] end(ClassWriter writer, MethodVisitor main) {
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Emits code that prints the value on top of the stack, of the given descriptor. */
    private static void print(MethodVisitor main, String descriptor) {
        // The value is under the stream reference after this swap-like shuffle.
        if (descriptor.equals("J") || descriptor.equals("D")) {
            main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "L" + PRINT_STREAM + ";");
            main.visitInsn(DUP_X2);
            main.visitInsn(POP);
        } else {
            main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "L" + PRINT_STREAM + ";");
            main.visitInsn(SWAP);
        }
        main.visitMethodInsn(INVOKEVIRTUAL, PRINT_STREAM, "println", "(" + descriptor + ")V", false);
    }

    /**
     * Every operand-stack shuffling opcode in every width combination. A mistake in any of them
     * changes at least one printed number.
     */
    static byte[] stackOps(String name) {
        // Version 49 predates stack map frames, so the branches below need none.
        ClassWriter writer = begin(name, V1_5);
        MethodVisitor m = beginMain(writer);

        // DUP_X1: a b -> b a b ; then (b - a) ... keep it simple: compute 10*(b) + a patterns.
        m.visitIntInsn(BIPUSH, 3);
        m.visitIntInsn(BIPUSH, 5);
        m.visitInsn(DUP_X1);            // 5 3 5
        m.visitInsn(ISUB);              // 5 (3-5)
        m.visitInsn(IMUL);              // 5 * -2
        print(m, "I");                  // -10

        // DUP_X2, form 1 (three narrow values): c b a -> a c b a
        m.visitIntInsn(BIPUSH, 2);
        m.visitIntInsn(BIPUSH, 7);
        m.visitIntInsn(BIPUSH, 11);
        m.visitInsn(DUP_X2);            // 11 2 7 11
        m.visitInsn(IADD);              // 11 2 18
        m.visitInsn(IMUL);              // 11 36
        m.visitInsn(ISUB);              // -25
        print(m, "I");

        // DUP_X2, form 2 (narrow over wide): L i -> i L i
        m.visitLdcInsn(1000L);
        m.visitIntInsn(BIPUSH, 9);
        m.visitInsn(DUP_X2);            // 9 1000L 9
        m.visitInsn(I2L);
        m.visitInsn(LADD);              // 9 1009L
        m.visitInsn(L2I);
        m.visitInsn(IADD);              // 1018
        print(m, "I");

        // DUP2, form 1 (two narrow) and form 2 (one wide)
        m.visitIntInsn(BIPUSH, 4);
        m.visitIntInsn(BIPUSH, 6);
        m.visitInsn(DUP2);              // 4 6 4 6
        m.visitInsn(IMUL);
        m.visitInsn(IADD);
        m.visitInsn(IADD);              // 4 + 6 + 24
        print(m, "I");
        m.visitLdcInsn(21L);
        m.visitInsn(DUP2);              // 21L 21L
        m.visitInsn(LMUL);
        print(m, "J");                  // 441

        // DUP2_X1, form 1 (two narrow over narrow): c b a -> b a c b a
        m.visitIntInsn(BIPUSH, 1);
        m.visitIntInsn(BIPUSH, 2);
        m.visitIntInsn(BIPUSH, 3);
        m.visitInsn(DUP2_X1);           // 2 3 1 2 3
        m.visitInsn(IADD);              // 2 3 1 5
        m.visitInsn(IMUL);              // 2 3 5
        m.visitInsn(ISUB);              // 2 -2
        m.visitInsn(IMUL);              // -4
        print(m, "I");

        // DUP2_X1, form 2 (wide over narrow): i L -> L i L
        m.visitIntInsn(BIPUSH, 8);
        m.visitLdcInsn(100L);
        m.visitInsn(DUP2_X1);           // 100L 8 100L
        m.visitInsn(L2I);
        m.visitInsn(IADD);              // 100L 108
        m.visitInsn(I2L);
        m.visitInsn(LSUB);              // -8
        print(m, "J");

        // DUP2_X2, form 4 (wide over wide): L1 L2 -> L2 L1 L2
        m.visitLdcInsn(5L);
        m.visitLdcInsn(7L);
        m.visitInsn(DUP2_X2);           // 7 5 7
        m.visitInsn(LMUL);              // 7 35
        m.visitInsn(LSUB);              // -28
        print(m, "J");

        // DUP2_X2, form 1 (four narrow): d c b a -> b a d c b a
        m.visitIntInsn(BIPUSH, 1);
        m.visitIntInsn(BIPUSH, 2);
        m.visitIntInsn(BIPUSH, 3);
        m.visitIntInsn(BIPUSH, 4);
        m.visitInsn(DUP2_X2);           // 3 4 1 2 3 4
        m.visitInsn(IADD);              // 3 4 1 2 7
        m.visitInsn(IMUL);              // 3 4 1 14
        m.visitInsn(IADD);              // 3 4 15
        m.visitInsn(IMUL);              // 3 60
        m.visitInsn(ISUB);              // -57
        print(m, "I");

        // DUP2_X2, form 2 (wide over two narrow): b a L -> L b a L
        m.visitIntInsn(BIPUSH, 2);
        m.visitIntInsn(BIPUSH, 3);
        m.visitLdcInsn(10L);
        m.visitInsn(DUP2_X2);           // 10L 2 3 10L
        m.visitInsn(L2I);
        m.visitInsn(IADD);              // 10L 2 13
        m.visitInsn(IMUL);              // 10L 26
        m.visitInsn(I2L);
        m.visitInsn(LADD);              // 36
        print(m, "J");

        // DUP2_X2, form 3 (two narrow over wide): L b a -> b a L b a
        m.visitLdcInsn(50L);
        m.visitIntInsn(BIPUSH, 6);
        m.visitIntInsn(BIPUSH, 7);
        m.visitInsn(DUP2_X2);           // 6 7 50L 6 7
        m.visitInsn(IMUL);              // 6 7 50L 42
        m.visitInsn(I2L);
        m.visitInsn(LADD);              // 6 7 92L
        m.visitInsn(L2I);
        m.visitInsn(IADD);              // 6 99
        m.visitInsn(IADD);              // 105
        print(m, "I");

        // POP2 in both forms, SWAP and NOP
        m.visitIntInsn(BIPUSH, 42);
        m.visitIntInsn(BIPUSH, 1);
        m.visitIntInsn(BIPUSH, 2);
        m.visitInsn(POP2);              // 42
        m.visitLdcInsn(3.5d);
        m.visitInsn(POP2);              // 42
        m.visitIntInsn(BIPUSH, 2);
        m.visitInsn(SWAP);              // 2 42
        m.visitInsn(NOP);
        m.visitInsn(ISUB);              // -40
        print(m, "I");

        // A lookup switch with negative and sparse keys, reached in a loop.
        int local = 1;
        m.visitInsn(ICONST_0);
        m.visitVarInsn(ISTORE, local);
        for (int key : new int[] {-1000, -1, 0, 77, 100000, 5}) {
            Label negative = new Label();
            Label zero = new Label();
            Label big = new Label();
            Label otherwise = new Label();
            Label done = new Label();
            m.visitLdcInsn(key);
            m.visitLookupSwitchInsn(otherwise, new int[] {-1000, 0, 100000}, new Label[] {negative, zero, big});
            m.visitLabel(negative);
            m.visitIincInsn(local, 1);
            m.visitJumpInsn(GOTO, done);
            m.visitLabel(zero);
            m.visitIincInsn(local, 10);
            m.visitJumpInsn(GOTO, done);
            m.visitLabel(big);
            m.visitIincInsn(local, 100);
            m.visitJumpInsn(GOTO, done);
            m.visitLabel(otherwise);
            m.visitIincInsn(local, 1000);
            m.visitLabel(done);
        }
        m.visitVarInsn(ILOAD, local);
        print(m, "I");                  // 3111
        return end(writer, m);
    }

    /**
     * A subroutine called with JSR and left with RET, as compilers before Java 6 generated for
     * {@code finally}. Such classes must carry an old version number, and still appear in
     * long-lived malware families.
     */
    static byte[] subroutine(String name) {
        ClassWriter writer = begin(name, V1_4);
        MethodVisitor m = beginMain(writer);
        Label subroutine = new Label();
        Label after = new Label();
        m.visitInsn(ICONST_0);
        m.visitVarInsn(ISTORE, 1);
        m.visitJumpInsn(JSR, subroutine);
        m.visitJumpInsn(JSR, subroutine);
        m.visitJumpInsn(JSR, subroutine);
        m.visitJumpInsn(GOTO, after);
        m.visitLabel(subroutine);
        m.visitVarInsn(ASTORE, 2);      // the return address
        m.visitIincInsn(1, 7);
        m.visitVarInsn(RET, 2);
        m.visitLabel(after);
        m.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "L" + PRINT_STREAM + ";");
        m.visitVarInsn(ILOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, PRINT_STREAM, "println", "(I)V", false);
        return end(writer, m);
    }

    /**
     * Call sites whose targets are hidden behind the sample's own bootstrap methods, plus a
     * method handle and a method type loaded as constants.
     */
    static byte[] hiddenCalls(String name) {
        String targets = Type.getInternalName(IndyTargets.class);
        String bootstrapDesc = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                + "Ljava/lang/String;)Ljava/lang/invoke/CallSite;";
        Handle hiddenStatic = new Handle(H_INVOKESTATIC, targets, "hiddenStatic", bootstrapDesc, false);
        Handle hiddenVirtual = new Handle(H_INVOKESTATIC, targets, "hiddenVirtual", bootstrapDesc, false);
        Handle retargeted = new Handle(H_INVOKESTATIC, targets, "retargeted",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

        ClassWriter writer = begin(name, V1_8);
        MethodVisitor m = beginMain(writer);
        // Each site is executed twice in a loop-free way to exercise the linked-site cache.
        for (int round = 0; round < 2; round++) {
            m.visitIntInsn(BIPUSH, 40);
            m.visitIntInsn(BIPUSH, 2 + round);
            m.visitInvokeDynamicInsn("x", "(II)I", hiddenStatic, IndyTargets.encrypt("add"));
            print(m, "I");
        }
        m.visitLdcInsn("hey");
        m.visitLdcInsn(2L);
        m.visitInvokeDynamicInsn("y", "(Ljava/lang/String;J)Ljava/lang/String;", hiddenStatic, IndyTargets.encrypt("shout"));
        print(m, "Ljava/lang/String;");
        m.visitLdcInsn("hidden virtual call");
        m.visitInvokeDynamicInsn("z", "(Ljava/lang/String;)I", hiddenVirtual, IndyTargets.encrypt("length"));
        print(m, "I");
        m.visitInvokeDynamicInsn("w", "()Ljava/lang/String;", retargeted);
        print(m, "Ljava/lang/String;");

        // A method handle constant, invoked exactly.
        m.visitLdcInsn(new Handle(H_INVOKESTATIC, targets, "add", "(II)I", false));
        m.visitIntInsn(BIPUSH, 20);
        m.visitIntInsn(BIPUSH, 22);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", "(II)I", false);
        print(m, "I");
        // A method type constant.
        m.visitLdcInsn(Type.getMethodType("(Ljava/lang/String;J)Ljava/lang/String;"));
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodType", "parameterCount", "()I", false);
        print(m, "I");
        return end(writer, m);
    }

    /**
     * Bootstrap methods declared with varargs, for both a dynamic constant and an
     * {@code invokedynamic}. The JVM packs the trailing static arguments into one array; a
     * dynamic constant needs class version 55 (Java 11).
     */
    static byte[] varargsBootstraps(String name) {
        String targets = Type.getInternalName(IndyTargets.class);
        Handle joined = new Handle(H_INVOKESTATIC, targets, "joined",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/String;",
                false);
        Handle lastNamed = new Handle(H_INVOKESTATIC, targets, "lastNamed",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/String;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        ClassWriter writer = begin(name, V11);
        MethodVisitor m = beginMain(writer);
        m.visitLdcInsn(new ConstantDynamic("label", "Ljava/lang/String;", joined, "a", 1, "b"));
        print(m, "Ljava/lang/String;");
        m.visitLdcInsn(new ConstantDynamic("one", "Ljava/lang/String;", joined, "only"));
        print(m, "Ljava/lang/String;");
        m.visitLdcInsn(new ConstantDynamic("bare", "Ljava/lang/String;", joined));
        print(m, "Ljava/lang/String;");
        m.visitInvokeDynamicInsn("v", "()Ljava/lang/String;", lastNamed, "first", "second");
        print(m, "Ljava/lang/String;");
        m.visitInvokeDynamicInsn("u", "()Ljava/lang/String;", lastNamed, "first");
        print(m, "Ljava/lang/String;");
        return end(writer, m);
    }

    /** More local variables than fit in one byte, forcing the wide instruction forms. */
    static byte[] manyLocals(String name) {
        ClassWriter writer = begin(name, V1_8);
        MethodVisitor m = beginMain(writer);
        for (int i = 1; i <= 300; i++) {
            m.visitLdcInsn(i);
            m.visitVarInsn(ISTORE, i);
        }
        m.visitIincInsn(299, 1000);     // wide iinc: index and constant both exceed a byte
        m.visitVarInsn(ILOAD, 299);
        m.visitVarInsn(ILOAD, 300);
        m.visitInsn(IADD);
        print(m, "I");                  // 299 + 1000 + 300
        return end(writer, m);
    }

    /**
     * Defines a class through {@code sun.misc.Unsafe.defineClass}, which current JDKs no longer
     * have and which therefore cannot be written in Java source. Emulator only.
     *
     * <p>Reads the class bytes from the resource {@code /payload.bin}, defines them, creates an
     * instance and calls {@code run(String)}, printing the result.
     */
    static byte[] unsafeLoader(String name) {
        ClassWriter writer = begin(name, V1_8);
        MethodVisitor m = beginMain(writer);
        m.visitLdcInsn(Type.getObjectType(name));
        m.visitLdcInsn("/payload.bin");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getResourceAsStream", "(Ljava/lang/String;)Ljava/io/InputStream;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/io/InputStream", "readAllBytes", "()[B", false);
        m.visitVarInsn(ASTORE, 1);
        m.visitMethodInsn(INVOKESTATIC, "sun/misc/Unsafe", "getUnsafe", "()Lsun/misc/Unsafe;", false);
        m.visitInsn(ACONST_NULL);
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(ICONST_0);
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(ARRAYLENGTH);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKEVIRTUAL, "sun/misc/Unsafe", "defineClass",
                "(Ljava/lang/String;[BIILjava/lang/ClassLoader;Ljava/security/ProtectionDomain;)Ljava/lang/Class;", false);
        m.visitVarInsn(ASTORE, 2);
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "newInstance", "()Ljava/lang/Object;", false);
        m.visitTypeInsn(CHECKCAST, "dev/javabyte/guest/sim/Payload");
        m.visitLdcInsn("unsafe");
        m.visitMethodInsn(INVOKEVIRTUAL, "dev/javabyte/guest/sim/Payload", "run", "(Ljava/lang/String;)Ljava/lang/String;", false);
        print(m, "Ljava/lang/String;");
        return end(writer, m);
    }

    /**
     * Code a JVM verifier rejects: it pops from an empty stack. Version 49 keeps ASM from
     * needing stack map frames. Emulator only; a real JVM refuses to load this class.
     */
    static byte[] stackUnderflow(String name) {
        ClassWriter writer = begin(name, V1_5);
        MethodVisitor m = beginMain(writer);
        m.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "L" + PRINT_STREAM + ";");
        m.visitLdcInsn("before the bad instruction");
        m.visitMethodInsn(INVOKEVIRTUAL, PRINT_STREAM, "println", "(Ljava/lang/String;)V", false);
        m.visitInsn(POP);
        m.visitInsn(IADD);
        return end(writer, m);
    }
}

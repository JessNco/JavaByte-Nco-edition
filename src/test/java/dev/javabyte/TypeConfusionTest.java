package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

import dev.javabyte.report.Report;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Calls whose receiver is not what the call site says it is.
 *
 * <p>There is no verifier, so bytecode can name any class at a call or field instruction and
 * leave any object underneath. The Java compiler never writes such code, which is why these
 * classes are generated. Each one tries to turn that mismatch into a real effect on the
 * analysis JVM; the checks look at the JVM itself, not at what the emulator says happened.
 */
class TypeConfusionTest {

    private static final String PROPERTY = "javabyte.type-confusion";
    private static final String STRING = "java/lang/String";
    private static final String OBJECT = "java/lang/Object";
    private static final String GET_METHOD = "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;";
    private static final String INVOKE = "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";

    @TempDir
    Path temp;

    private Report run(String name, Map<String, byte[]> classes) throws Exception {
        Report report = Fixtures.run(Fixtures.config(Fixtures.rawJar(temp.resolve(name + ".jar"), name, classes)));
        assertFalse(report.hasOutcome("internal-error"), Fixtures.outcomes(report));
        return report;
    }

    private static byte[] mainClass(String name, String superName, Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        // Version 49 predates stack map frames, so none are needed.
        writer.visit(V1_5, ACC_PUBLIC | ACC_SUPER, name, null, superName, null);
        MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor main = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        body.accept(main);
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void println(MethodVisitor main) {
        main.visitMethodInsn(INVOKESTATIC, STRING, "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitInsn(SWAP);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
    }

    /** Pushes {@code new Class[] {String.class, String.class}}. */
    private static void twoStringClasses(MethodVisitor main) {
        main.visitInsn(ICONST_2);
        main.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        for (int i = 0; i < 2; i++) {
            main.visitInsn(DUP);
            main.visitInsn(i == 0 ? ICONST_0 : ICONST_1);
            main.visitLdcInsn(Type.getObjectType(STRING));
            main.visitInsn(AASTORE);
        }
    }

    /** Pushes {@code new Object[] {PROPERTY, "set"}}. */
    private static void propertyArguments(MethodVisitor main) {
        main.visitInsn(ICONST_2);
        main.visitTypeInsn(ANEWARRAY, OBJECT);
        main.visitInsn(DUP);
        main.visitInsn(ICONST_0);
        main.visitLdcInsn(PROPERTY);
        main.visitInsn(AASTORE);
        main.visitInsn(DUP);
        main.visitInsn(ICONST_1);
        main.visitLdcInsn("set");
        main.visitInsn(AASTORE);
    }

    /**
     * {@code System.class} and the {@code Method} it returns are called as if they were
     * strings. Every member of {@code String} is allowed, so the policy says yes to
     * "String.getMethod" and "String.invoke"; the real {@code Class.getMethod} and
     * {@code Method.invoke} must not be what runs.
     */
    @Test
    void reflectionObjectsCalledAsStringsStayBehindTheirStubs() throws Exception {
        System.clearProperty(PROPERTY);
        byte[] bytes = mainClass("AsString", OBJECT, main -> {
            main.visitLdcInsn(Type.getObjectType("java/lang/System"));
            main.visitLdcInsn("setProperty");
            twoStringClasses(main);
            main.visitMethodInsn(INVOKEVIRTUAL, STRING, "getMethod", GET_METHOD, false);
            main.visitInsn(ACONST_NULL);
            propertyArguments(main);
            main.visitMethodInsn(INVOKEVIRTUAL, STRING, "invoke", INVOKE, false);
            main.visitInsn(POP);
        });
        Report report = run("AsString", Map.of("AsString.class", bytes));
        assertNull(System.getProperty(PROPERTY), "a real System.setProperty ran");
        assertTrue(report.hasOutcome("completed"), Fixtures.outcomes(report));
        // The calls reached the reflection stubs, so the report shows what was attempted.
        assertEquals("java/lang/System.setProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                Fixtures.only(report, "reflect", "invoke").data().get("method"));
    }

    /**
     * The same through {@code Object}, a real supertype of the receiver that does not have the
     * method. The name must not be looked up on the receiver instead.
     */
    @Test
    void methodsTheNamedClassLacksAreNotFoundOnTheReceiver() throws Exception {
        System.clearProperty(PROPERTY);
        byte[] bytes = mainClass("AsObject", OBJECT, main -> {
            main.visitLdcInsn(Type.getObjectType("java/lang/System"));
            main.visitLdcInsn("setProperty");
            twoStringClasses(main);
            main.visitMethodInsn(INVOKEVIRTUAL, OBJECT, "getMethod", GET_METHOD, false);
            main.visitInsn(ACONST_NULL);
            propertyArguments(main);
            main.visitMethodInsn(INVOKEVIRTUAL, OBJECT, "invoke", INVOKE, false);
            main.visitInsn(POP);
            // Runtime.getRuntime() hands out the real object as a token.
            main.visitMethodInsn(INVOKESTATIC, "java/lang/Runtime", "getRuntime", "()Ljava/lang/Runtime;", false);
            main.visitLdcInsn("javabyte-confusion-no-such-program");
            main.visitMethodInsn(INVOKEVIRTUAL, OBJECT, "exec", "(Ljava/lang/String;)Ljava/lang/Process;", false);
            main.visitInsn(POP);
        });
        Report report = run("AsObject", Map.of("AsObject.class", bytes));
        assertNull(System.getProperty(PROPERTY), "a real System.setProperty ran");
        assertTrue(report.hasOutcome("completed"), Fixtures.outcomes(report));
        // Runtime.exec met its stub, which simulates and reports the launch.
        assertEquals("javabyte-confusion-no-such-program", Fixtures.only(report, "process", "exec").data().get("command"));
    }

    /**
     * A sample's {@code Thread} subclass is a generated class with public helpers that call
     * the JDK superclass directly. Neither they nor {@code start()} named through
     * {@code Object} may start a real thread.
     */
    @Test
    void shellHelpersAndSupertypeNamesDoNotStartARealThread() throws Exception {
        byte[] bytes = mainClass("Spawner", "java/lang/Thread", main -> {
            for (String name : List.of("javabyte$super$start", "start")) {
                main.visitTypeInsn(NEW, "Spawner");
                main.visitInsn(DUP);
                main.visitMethodInsn(INVOKESPECIAL, "Spawner", "<init>", "()V", false);
                main.visitMethodInsn(INVOKEVIRTUAL, name.equals("start") ? "java/lang/Runnable" : OBJECT, name, "()V",
                        name.equals("start"));
            }
            main.visitTypeInsn(NEW, "Spawner");
            main.visitInsn(DUP);
            main.visitMethodInsn(INVOKESPECIAL, "Spawner", "<init>", "()V", false);
            main.visitMethodInsn(INVOKESPECIAL, STRING, "start", "()V", false);
        });
        Report report = run("Spawner", Map.of("Spawner.class", bytes));
        Thread.sleep(200);
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            assertFalse(thread.getClass().getName().startsWith("dev.javabyte.shell.gen."),
                    "a guest Thread subclass is running as a real thread: " + thread);
        }
        assertFalse(report.events().byCategory("policy").isEmpty(), Fixtures.outcomes(report));
    }

    /**
     * A class literal for a sample's own class is one of the emulator's objects. Its fields
     * are the emulator's state and must read as absent, whatever class the instruction names.
     */
    @Test
    void fieldsOfEmulatorObjectsCannotBeReadOrWritten() throws Exception {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("Peek.class", mainClass("Peek", OBJECT, main -> {
            for (String field : List.of("repository", "node", "shellClass", "bytes")) {
                main.visitLdcInsn(Type.getObjectType("Peek"));
                main.visitFieldInsn(GETFIELD, "java/lang/Class", field, "Ljava/lang/Object;");
                println(main);
            }
            main.visitLdcInsn(Type.getObjectType("Peek"));
            main.visitLdcInsn(Type.getObjectType("java/lang/Runtime"));
            main.visitFieldInsn(PUTFIELD, "java/lang/Class", "shellClass", "Ljava/lang/Class;");
            main.visitLdcInsn(Type.getObjectType("Peek"));
            main.visitFieldInsn(GETFIELD, "java/lang/Class", "shellClass", "Ljava/lang/Object;");
            println(main);
        }));
        Report report = run("Peek", classes);
        assertEquals(List.of("null", "null", "null", "null", "null"), Fixtures.stdout(report), Fixtures.outcomes(report));
    }
}

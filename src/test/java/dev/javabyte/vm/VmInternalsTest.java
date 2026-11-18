package dev.javabyte.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.env.SimClassLoader;
import dev.javabyte.env.SimFile;
import dev.javabyte.env.VirtualFileSystem;
import dev.javabyte.guest.StackKeyed;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestMethod;
import dev.javabyte.report.Artifacts;
import dev.javabyte.report.EventLog;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks of VM components that are hard to reach from a whole run: hook registration, the
 * stack trace the guest sees, and the class names it is told. No guest code is executed.
 */
class VmInternalsTest {

    private static VirtualMachine newVm() {
        return new VirtualMachine(new EmulatorConfig(), new EventLog(1000, null), new Artifacts(null));
    }

    /** Loads a harmless compiled test class into the VM's repository, as data. */
    private static GuestClass load(VirtualMachine vm, Class<?> cls) throws IOException {
        String name = cls.getName().replace('.', '/');
        try (InputStream in = cls.getResourceAsStream('/' + name + ".class")) {
            vm.classes.addClassBytes(name, in.readAllBytes(), "test");
        }
        return vm.classes.find(name);
    }

    @Test
    void pluginTraceRequestIsATraceAndNotASkipHook() throws IOException {
        VirtualMachine vm = newVm();
        vm.hooks.add("dev/javabyte/guest/StackKeyed.first", "trace", null);
        assertNull(vm.hooks.forHost("dev/javabyte/guest/StackKeyed", "first", "()Ljava/lang/String;"),
                "a trace request must not replace the method");
        GuestMethod first = load(vm, StackKeyed.class).declared("first", "()Ljava/lang/String;");
        assertNull(first.hook);
        assertTrue(first.traceCalls);
    }

    @Test
    void pluginHookPatternsAcceptTheQuestionMarkWildcard() throws IOException {
        VirtualMachine vm = newVm();
        vm.hooks.add("dev/javabyte/guest/StackKeyed.firs?", "skip", null);
        GuestClass cls = load(vm, StackKeyed.class);
        assertEquals("skip", ((Hooks.Hook) cls.declared("first", "()Ljava/lang/String;").hook).action());
        assertNull(cls.declared("second", "()Ljava/lang/String;").hook);
    }

    @Test
    void unknownHookActionIsRejected() {
        VirtualMachine vm = newVm();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> vm.hooks.add("a/B.c", "tracee", null));
        assertTrue(error.getMessage().contains("return, skip, throw or trace"), error.getMessage());
    }

    @Test
    void nativeHandlersUseTheSameGlobSyntaxAsHooks() throws IOException {
        VirtualMachine vm = newVm();
        vm.natives.register("dev/javabyte/guest/StackKeyed.firs?()*", (machine, method, receiver, args) -> null);
        GuestClass cls = load(vm, StackKeyed.class);
        assertTrue(vm.natives.find(cls.declared("first", "()Ljava/lang/String;")) != null);
        assertNull(vm.natives.find(cls.declared("second", "()Ljava/lang/String;")));
    }

    @Test
    void reflectiveCallsShowJava8AccessorFramesBetweenCallerAndCallee() throws IOException {
        VirtualMachine vm = newVm();
        GuestClass cls = load(vm, StackKeyed.class);
        vm.pushFrame(new Frame(cls.declared("main", "([Ljava/lang/String;)V")));
        StackTraceElement[] inside = vm.callThroughJdk(VirtualMachine.METHOD_INVOKE_FRAMES, () -> {
            vm.pushFrame(new Frame(cls.declared("first", "()Ljava/lang/String;")));
            return vm.guestStackTrace();
        });
        List<String> names = Arrays.stream(inside).map(e -> e.getClassName() + '.' + e.getMethodName()).toList();
        assertEquals(List.of("dev.javabyte.guest.StackKeyed.first", "sun.reflect.NativeMethodAccessorImpl.invoke0",
                "sun.reflect.NativeMethodAccessorImpl.invoke", "sun.reflect.DelegatingMethodAccessorImpl.invoke",
                "java.lang.reflect.Method.invoke", "dev.javabyte.guest.StackKeyed.main"), names);
        assertTrue(inside[1].isNativeMethod());
        vm.popFrame(vm.currentStack(), 1);
        assertEquals(1, vm.guestStackTrace().length, "the reflective frames end with the call");
    }

    @Test
    void hostExceptionsKeepTheirJdkFramesWithoutModuleNames() throws IOException {
        VirtualMachine vm = newVm();
        GuestClass cls = load(vm, StackKeyed.class);
        vm.pushFrame(new Frame(cls.declared("main", "([Ljava/lang/String;)V")));
        NumberFormatException failure = assertThrows(NumberFormatException.class, () -> Integer.parseInt("zz"));
        StackTraceElement[] trace = ((Throwable) vm.raiseKeepingTrace(failure).value).getStackTrace();
        assertEquals("java.lang.NumberFormatException", trace[0].getClassName());
        assertEquals("forInputString", trace[0].getMethodName());
        assertNull(trace[0].getModuleName(), "a module name would reveal the host's real JDK");
        assertEquals("dev.javabyte.guest.StackKeyed", trace[trace.length - 1].getClassName());
        for (StackTraceElement frame : trace) {
            assertFalse(frame.getClassName().startsWith("dev.javabyte.vm") || frame.getClassName().startsWith("org.junit")
                    || frame.getClassName().startsWith("jdk.internal"), frame.toString());
        }
    }

    @Test
    void emulatorObjectsAreNamedAsTheJdkClassesTheyImitate() {
        VirtualMachine vm = newVm();
        VirtualFileSystem files = new VirtualFileSystem(true, vm.events, 1000, 0);
        assertEquals("java.io.File", vm.types.nameOf(new SimFile(files, "C:\\a.txt")));
        // Without the stubs' imitation rules, the nearest JDK superclass: the application
        // loader is a URLClassLoader, as Java 8's is.
        assertEquals("java.net.URLClassLoader", vm.types.nameOf(new SimClassLoader()));
        assertEquals(java.net.URLClassLoader.class, vm.types.reportedClass(new SimClassLoader()));
    }

    @Test
    void implicitExceptionWordingFollowsTheAdvertisedJavaVersion() {
        VirtualMachine vm = newVm();
        VirtualFileSystem files = new VirtualFileSystem(true, vm.events, 1000, 0);
        SimFile file = new SimFile(files, "C:\\a.txt");
        assertEquals(8, vm.implicitExceptions.guestFeatureVersion(), "Java 8 is the default identity");
        assertNull(((Throwable) vm.implicitExceptions.nullPointer("helpful").value).getMessage());
        assertEquals("java.io.File cannot be cast to java.lang.String",
                ((Throwable) vm.implicitExceptions.classCast(file, "java/lang/String").value).getMessage());

        vm.env.properties.setProperty("java.version", "17.0.9");
        assertEquals(17, vm.implicitExceptions.guestFeatureVersion());
        assertEquals("helpful", ((Throwable) vm.implicitExceptions.nullPointer("helpful").value).getMessage());
        assertEquals("class java.io.File cannot be cast to class java.lang.String (java.io.File and java.lang.String "
                + "are in module java.base of loader 'bootstrap')",
                ((Throwable) vm.implicitExceptions.classCast(file, "java/lang/String").value).getMessage());
    }
}

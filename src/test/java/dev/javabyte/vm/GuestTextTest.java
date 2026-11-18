package dev.javabyte.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.env.SimClassLoader;
import dev.javabyte.env.SimProcess;
import dev.javabyte.report.Artifacts;
import dev.javabyte.report.Event;
import dev.javabyte.report.EventLog;
import dev.javabyte.stubs.BuiltinStubs;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;

/**
 * Component checks of text the guest can read without it naming the emulator: class names of
 * simulated objects, stand-in identity hashes, and what a real JDK thread meets when it calls
 * a guest lambda. No guest code is executed.
 */
class GuestTextTest {

    /** A machine with the built-in stubs, and so the imitation rules, installed. */
    private static VirtualMachine newVm(String javaSpecification) {
        EmulatorConfig config = new EmulatorConfig();
        VirtualMachine vm = new VirtualMachine(config, new EventLog(1000, null), new Artifacts(null));
        BuiltinStubs.install(vm);
        if (javaSpecification != null) {
            vm.env.properties.setProperty("java.specification.version", javaSpecification);
        }
        return vm;
    }

    @Test
    void identityHashesAreStablePerObjectAndRepeatableBetweenRuns() {
        VirtualMachine first = newVm(null);
        VirtualMachine second = newVm(null);
        Object a = new Object();
        Object b = new Object();
        int hashA = first.types.identityHash(a);
        assertEquals(hashA, first.types.identityHash(a), "an object keeps its hash");
        assertNotEquals(hashA, first.types.identityHash(b));
        assertEquals(hashA, second.types.identityHash(new Object()), "the first object of every run gets the same hash");
        assertTrue(hashA > 0 && Integer.toHexString(hashA).length() >= 7, Integer.toHexString(hashA));
    }

    @Test
    void builtInLoaderNamesFollowTheAdvertisedJava() {
        VirtualMachine java8 = newVm(null);
        SimClassLoader app8 = java8.env.appLoader;
        assertEquals("sun.misc.Launcher$AppClassLoader@18b4aac2", app8.toString());
        assertEquals(0x18b4aac2, app8.hashCode());
        assertEquals("sun.misc.Launcher$AppClassLoader", java8.types.nameOf(app8), "cast messages name the Java 8 class");

        VirtualMachine modern = newVm("17");
        SimClassLoader app17 = modern.env.appLoader;
        assertEquals("jdk.internal.loader.ClassLoaders$AppClassLoader@1b6d3586", app17.toString());
        assertEquals(0x1b6d3586, app17.hashCode());
        assertEquals("jdk.internal.loader.ClassLoaders$AppClassLoader", modern.types.reportedClass(app17).getName());
    }

    @Test
    void simulatedProcessesReportTheConcreteJdkClass() {
        VirtualMachine vm = newVm(null);
        SimProcess process = new SimProcess("", "", 0);
        String expected = vm.env.windows ? "java.lang.ProcessImpl" : "java.lang.UNIXProcess";
        assertEquals(expected, vm.types.nameOf(process));
        assertEquals("java.lang.ProcessImpl", vm.types.reportedClass(process).getName(),
                "getClass() falls back to the class this JDK has");
    }

    @Test
    void aLambdaCalledFromAForeignThreadFailsNeutrallyAndIsReported() throws Exception {
        VirtualMachine vm = newVm(null);
        Handle implementation = new Handle(Opcodes.H_INVOKESTATIC, "sample/Main", "lambda$main$0", "()V", false);
        Runnable proxy = (Runnable) vm.indy.newLambda("java/lang/Runnable", "run", implementation, new Object[0]);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread foreign = new Thread(() -> {
            try {
                proxy.run();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        foreign.start();
        foreign.join();
        assertTrue(thrown.get() instanceof IllegalStateException, String.valueOf(thrown.get()));
        assertNull(thrown.get().getMessage(), "the message must not mention the emulator");
        vm.indy.reportForeignCalls();
        List<Event> gaps = vm.events.events().stream()
                .filter(event -> event.category().equals("gap") && event.action().equals("foreign-thread-call")).toList();
        assertEquals(1, gaps.size(), vm.events.events().toString());
        assertTrue(String.valueOf(gaps.get(0).data().get("target")).contains("lambda$main$0"), gaps.toString());
    }
}

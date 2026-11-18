package dev.javabyte.nativebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.report.Artifacts;
import dev.javabyte.report.Event;
import dev.javabyte.report.EventLog;
import dev.javabyte.vm.EmulationAbort;
import dev.javabyte.vm.VirtualMachine;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Java side of the native bridge protocol, checked without Python.
 *
 * <p>The helper's lines are prepared in advance and what the plugin sends back is captured. The
 * rule under test is that every callback gets exactly one answer before anything else happens,
 * whatever the callback does; otherwise every later request would be paired with the wrong reply.
 */
class NativeBridgeProtocolTest {

    private VirtualMachine vm;
    private NativeBridgePlugin plugin;
    private StringWriter sent;

    @BeforeEach
    void setUp() {
        vm = new VirtualMachine(new EmulatorConfig(), new EventLog(1000, null), new Artifacts(null));
        vm.bindToCurrentThread();
        plugin = new NativeBridgePlugin();
        sent = new StringWriter();
    }

    @AfterEach
    void tearDown() {
        vm.unbind();
    }

    /** Connects the plugin to a helper that will send these lines, one per read. */
    private void helperSends(String... lines) {
        plugin.attach(vm, new BufferedReader(new StringReader(String.join("\n", lines) + "\n")), new BufferedWriter(sent));
    }

    private List<String> sentLines() {
        return sent.toString().lines().toList();
    }

    private Map<String, Object> call() throws Exception {
        return plugin.exclusive(Map.of("op", "call"));
    }

    @Test
    void aBadArrayIndexBecomesAPendingJavaExceptionAndIsStillAnswered() throws Exception {
        int array = plugin.handle(new Object[] {"only"});
        helperSends("{\"callback\":\"objectArrayGet\",\"h\":" + array + ",\"index\":5}", "{\"result\":{\"v\":7}}");

        assertEquals(7, call().get("v"));
        assertEquals(List.of("{\"op\":\"call\"}", "{\"v\":0}"), sentLines());
        assertInstanceOf(ArrayIndexOutOfBoundsException.class, plugin.pendingException());
    }

    @Test
    void aNullArrayHandleBecomesANullPointerException() throws Exception {
        helperSends("{\"callback\":\"objectArraySet\",\"h\":0,\"index\":0,\"v\":0}", "{\"result\":{\"v\":0}}");
        call();
        assertEquals("{\"v\":0}", sentLines().get(1));
        assertInstanceOf(NullPointerException.class, plugin.pendingException());
    }

    @Test
    void aPrimitiveArrayRegionOutOfRangeIsAJavaException() throws Exception {
        int array = plugin.handle(new int[2]);
        helperSends("{\"callback\":\"arrayGet\",\"h\":" + array + ",\"start\":1,\"length\":4}", "{\"result\":{\"v\":0}}");
        call();
        assertEquals("{\"v\":0}", sentLines().get(1));
        assertInstanceOf(ArrayIndexOutOfBoundsException.class, plugin.pendingException());
    }

    @Test
    void anEmulatorFailureInACallbackIsReportedAndAnswered() throws Exception {
        plugin.callbacks = (name, request) -> {
            throw new IllegalStateException("emulator defect");
        };
        helperSends("{\"callback\":\"findClass\",\"name\":\"a/B\"}", "{\"result\":{\"v\":3}}");

        assertEquals(3, call().get("v"));
        assertEquals("{\"v\":0}", sentLines().get(1));
        Event gap = vm.events.byCategory("gap").get(0);
        assertEquals("native-bridge-callback-failed", gap.action());
        assertEquals("findClass", gap.data().get("callback"));
        assertFalse(plugin.isDisabled(), "one failed callback does not end the bridge");
    }

    @Test
    void aStoppedRunEndsTheHelperInsteadOfLeavingItOutOfStep() throws Exception {
        EmulationAbort stop = new EmulationAbort(EmulationAbort.Reason.BLOCKED, "waiting for ever");
        plugin.callbacks = (name, request) -> {
            throw stop;
        };
        helperSends("{\"callback\":\"call\"}", "{\"result\":{\"v\":0}}");

        assertSame(stop, assertThrows(EmulationAbort.class, this::call));
        assertTrue(plugin.isDisabled(), "a helper left waiting for an answer must not be used again");
        assertTrue(vm.events.byCategory("gap").stream().anyMatch(event -> event.action().equals("native-bridge-stopped")));
    }

    @Test
    void aLibraryLoadedFromInsideANativeCallIsNotSentToTheHelper() throws Exception {
        byte[] library = NativeBridgeProtocolTest.class.getResourceAsStream("/native/testlib.dll").readAllBytes();
        plugin.callbacks = (name, request) -> {
            plugin.loadLibrary("testlib.dll", library);
            return 1;
        };
        helperSends("{\"callback\":\"call\"}", "{\"result\":{\"v\":0}}");

        call();
        // Only the call and the callback's answer: no load request was sent while the call ran.
        assertEquals(List.of("{\"op\":\"call\"}", "{\"v\":1}"), sentLines());
        Event gap = vm.events.byCategory("gap").get(0);
        assertEquals("native-bridge-load-failed", gap.action());
        assertTrue(String.valueOf(gap.data().get("error")).contains("inside a native call"), gap.toString());
    }
}

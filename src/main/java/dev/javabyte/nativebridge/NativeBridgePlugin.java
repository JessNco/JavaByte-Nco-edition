package dev.javabyte.nativebridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javabyte.model.FieldRef;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestMethod;
import dev.javabyte.plugin.EmulatorPlugin;
import dev.javabyte.plugin.NativeHandler;
import dev.javabyte.report.NativeLibraryInfo;
import dev.javabyte.vm.EmulationAbort;
import dev.javabyte.vm.GuestThrow;
import dev.javabyte.vm.VirtualMachine;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;

/**
 * Optional plugin that executes a sample's native code in a CPU emulator.
 *
 * <p>JavaByte cannot interpret machine code. This plugin delegates it to a helper process
 * ({@code native_bridge.py}) built on Speakeasy and Unicorn, which runs a Windows DLL's code
 * instruction by instruction without ever executing it on the real processor. The two sides
 * speak a line-based JSON protocol, described at the top of the helper script.
 *
 * <p>The division of labour is deliberate. The helper owns memory and registers; this class
 * owns every Java object. JNI calls the native code makes ({@code FindClass},
 * {@code CallObjectMethod}, {@code NewStringUTF} and so on) come back here as callbacks and are
 * carried out through the ordinary call router, so a native method that calls
 * {@code Runtime.exec} through JNI meets the same stub as bytecode would. Objects cross the
 * boundary only as integer handles.
 *
 * <p>Enable it in the config:
 * <pre>
 * plugins:
 *   native-bridge:
 *     python: /path/to/venv/bin/python   # an interpreter with speakeasy-emulator installed
 * </pre>
 *
 * <p>Limits of this first version: 64-bit Windows DLLs only; one native call at a time (a
 * native method reached from inside another native call is reported, not emulated, and so is a
 * library loaded from inside one).
 *
 * <p>The protocol depends on this class answering every callback before it sends anything else.
 * {@link #request} therefore always replies, whatever the callback throws; when the run itself is
 * being stopped it cannot, and the helper is ended instead, so that a later call never reads an
 * answer meant for an earlier question.
 */
public final class NativeBridgePlugin implements EmulatorPlugin {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Raised internally when the helper cannot serve a request; never reaches guest code. */
    private static final class BridgeUnavailable extends Exception {
        BridgeUnavailable(String message) {
            super(message);
        }
    }

    private VirtualMachine vm;
    private String python;
    private Process helper;
    private BufferedWriter toHelper;
    private BufferedReader fromHelper;
    private Path workDirectory;
    private boolean disabled;
    /** True while a native call is running in the helper; the CPU emulator cannot be re-entered. */
    private boolean busy;

    /**
     * Carries out one JNI callback. It is {@link #callback} except in tests, which replace it to
     * make a callback fail in ways real guest code produces only with difficulty.
     */
    BiFunction<String, Map<String, Object>, Object> callbacks = this::callback;

    /** Exported symbol name to address, across all loaded libraries. */
    private final Map<String, Long> exports = new HashMap<>();
    /** Methods bound with RegisterNatives: {@code owner.name+descriptor} to address. */
    private final Map<String, Long> registered = new HashMap<>();

    /** Java objects known to native code. Index is the handle; handle 0 is null. */
    private final List<Object> objects = new ArrayList<>(java.util.Collections.singletonList(null));
    private final Map<Object, Integer> handles = new IdentityHashMap<>();
    /** The exception native code has raised or received and not yet cleared, as JNI defines it. */
    private Object pendingException;

    @Override
    public String id() {
        return "native-bridge";
    }

    @Override
    public String description() {
        return "Runs the sample's 64-bit Windows native libraries (DLLs) in a CPU emulator, so their behaviour is analysed too. Needs Python with speakeasy-emulator.";
    }

    @Override
    public List<dev.javabyte.config.ConfigSchema.Option> settings() {
        return List.of(new dev.javabyte.config.ConfigSchema.Option("python", "string", null, "python3", List.of(),
                "The Python interpreter that has speakeasy-emulator installed. Default: JAVABYTE_BRIDGE_PYTHON if set, else python3."));
    }

    @Override
    public void install(VirtualMachine machine, Map<String, Object> settings) {
        this.vm = machine;
        this.python = String.valueOf(settings.getOrDefault("python", defaultPython()));
        vm.natives.onLibraryLoaded(this::loadLibrary);
        vm.natives.register("*", this::callNative);
    }

    /**
     * The interpreter used when the config names none. The desktop app sets
     * {@code JAVABYTE_BRIDGE_PYTHON} to the Python it bundles, so turning the plugin on is enough.
     */
    static String defaultPython() {
        String fromEnvironment = System.getenv("JAVABYTE_BRIDGE_PYTHON");
        return fromEnvironment == null || fromEnvironment.isBlank() ? "python3" : fromEnvironment;
    }

    @Override
    public void close() {
        if (helper != null) {
            helper.destroyForcibly();
        }
        if (workDirectory != null) {
            try (var files = Files.walk(workDirectory)) {
                files.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            } catch (IOException ignored) {
                // Best effort: the directory is in the system temp area either way.
            }
        }
    }

    /**
     * Speaks the protocol over the given streams instead of a helper process. Used by tests, so
     * that the Java side can be checked without Python; nothing is started.
     */
    void attach(VirtualMachine machine, BufferedReader from, BufferedWriter to) {
        this.vm = machine;
        this.fromHelper = from;
        this.toHelper = to;
    }

    /** True once the bridge has given up; every later native call is reported, not emulated. */
    boolean isDisabled() {
        return disabled;
    }

    /** The exception native code has pending, as JNI defines it, or null. */
    Object pendingException() {
        return pendingException;
    }

    // ------------------------------------------------------------------ helper process

    private void ensureStarted() throws BridgeUnavailable {
        if (disabled) {
            throw new BridgeUnavailable("bridge disabled after an earlier failure");
        }
        if (helper != null) {
            return;
        }
        try {
            workDirectory = Files.createTempDirectory("javabyte-native");
            Path script = workDirectory.resolve("native_bridge.py");
            try (InputStream in = NativeBridgePlugin.class.getResourceAsStream("native_bridge.py")) {
                Files.copy(in, script);
            }
            // This is the one place the emulator starts a process. It is the emulator's own
            // helper, started only when the user enabled this plugin; the sample has no say in
            // the command line.
            ProcessBuilder builder = new ProcessBuilder(python, script.toString());
            Path log = vm.artifacts.root() == null ? null : vm.artifacts.root().resolve("native-bridge.log");
            builder.redirectError(log == null ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.to(log.toFile()));
            helper = builder.start();
            toHelper = new BufferedWriter(new OutputStreamWriter(helper.getOutputStream(), StandardCharsets.UTF_8));
            fromHelper = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            disable("cannot start helper with '" + python + "': " + e.getMessage());
            throw new BridgeUnavailable(e.getMessage());
        }
    }

    private void disable(String reason) {
        disabled = true;
        vm.events.add("gap", "native-bridge-unavailable", "reason", reason,
                "hint", "plugins.native-bridge.python must name an interpreter with speakeasy-emulator installed");
    }

    /**
     * Ends the helper while it is waiting for a callback answer that will never come, because
     * the run (or this guest thread) is being stopped. Its native code cannot be resumed
     * safely, and a later request would otherwise read the rest of this one's traffic.
     */
    private void abandonHelper(String reason) {
        disabled = true;
        if (helper != null) {
            helper.destroyForcibly();
        }
        vm.events.add("gap", "native-bridge-stopped", "reason", reason,
                "detail", "native code was interrupted part way through; later native calls are reported, not emulated");
    }

    private void send(Map<String, Object> message) throws BridgeUnavailable {
        try {
            toHelper.write(JSON.writeValueAsString(message));
            toHelper.write('\n');
            toHelper.flush();
        } catch (IOException e) {
            disable("helper stopped accepting input: " + e.getMessage());
            throw new BridgeUnavailable(e.getMessage());
        }
    }

    /**
     * Sends a request with the bridge marked busy, so that nothing else is sent to the helper
     * until its result arrives. The previous state is restored afterwards rather than cleared,
     * because a request can be made from inside a callback of another one.
     */
    Map<String, Object> exclusive(Map<String, Object> message) throws BridgeUnavailable {
        boolean wasBusy = busy;
        busy = true;
        try {
            return request(message);
        } finally {
            busy = wasBusy;
        }
    }

    /**
     * Sends a request and serves the helper's callbacks until its result arrives.
     *
     * <p>Every callback gets exactly one answer, sent before anything else is read or written:
     * the helper is blocked waiting for it. A Java exception becomes the pending JNI exception, as
     * on a real JVM. Any other failure (an emulator defect) is reported as a gap and answered
     * with zero. An {@link EmulationAbort} is the one exception that cannot be answered, so the
     * helper is ended before it propagates.
     *
     * @return the result object
     */
    private Map<String, Object> request(Map<String, Object> message) throws BridgeUnavailable {
        send(message);
        while (true) {
            Map<String, Object> reply = read();
            if (reply.containsKey("result")) {
                return asMap(reply.get("result"));
            }
            if (reply.containsKey("error")) {
                throw new BridgeUnavailable(String.valueOf(reply.get("error")));
            }
            if (reply.containsKey("event")) {
                Map<String, Object> event = asMap(reply.get("event"));
                List<Object> pairs = new ArrayList<>();
                asMap(event.get("data")).forEach((key, value) -> {
                    pairs.add(key);
                    pairs.add(value);
                });
                vm.events.add(String.valueOf(event.get("category")), String.valueOf(event.get("action")), pairs.toArray());
                continue;
            }
            String name = String.valueOf(reply.get("callback"));
            Object value;
            try {
                value = callbacks.apply(name, reply);
            } catch (GuestThrow thrown) {
                // A Java exception during a JNI call becomes the pending exception; the native
                // code sees a zero result and may check for it.
                pendingException = thrown.value;
                value = 0;
            } catch (EmulationAbort stop) {
                abandonHelper("run stopped during JNI callback " + name + ": " + stop.getMessage());
                throw stop;
            } catch (RuntimeException failure) {
                vm.events.add("gap", "native-bridge-callback-failed", "callback", name, "error", failure.toString());
                value = 0;
            }
            send(Map.of("v", value == null ? 0 : value));
        }
    }

    private Map<String, Object> read() throws BridgeUnavailable {
        try {
            String line = fromHelper.readLine();
            if (line == null) {
                disable("helper exited; see native-bridge.log in the output directory");
                throw new BridgeUnavailable("helper exited");
            }
            return asMap(JSON.readValue(line, Map.class));
        } catch (IOException e) {
            disable("helper sent unreadable output: " + e.getMessage());
            throw new BridgeUnavailable(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    // ------------------------------------------------------------------ entry points from the VM

    /**
     * Called when the sample loads a library: hands a supported one to the helper.
     *
     * <p>A library loaded by Java code that native code called back into is not handed over:
     * the helper is in the middle of that native call and cannot load a module until it ends.
     */
    void loadLibrary(String name, byte[] data) {
        NativeLibraryInfo info = NativeLibraryInfo.parse(data);
        if (info == null || !"PE".equals(info.format) || !"x86-64".equals(info.architecture)) {
            vm.events.add("native", "bridge-skipped", "library", name,
                    "reason", "only 64-bit Windows DLLs are supported, found " + (info == null ? "unknown" : info.format + ' ' + info.architecture));
            return;
        }
        if (busy) {
            vm.events.add("gap", "native-bridge-load-failed", "library", name,
                    "error", "loaded from inside a native call; the CPU emulator cannot load a library until that call ends");
            return;
        }
        try {
            ensureStarted();
            Path copy = workDirectory.resolve("library-" + exports.size() + '-' + objects.size() + ".dll");
            Files.write(copy, data);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("op", "load");
            message.put("path", copy.toString());
            Map<String, Object> result = exclusive(message);
            asMap(result.get("exports")).forEach((symbol, address) -> exports.put(symbol, ((Number) address).longValue()));
            vm.events.add("native", "bridge-loaded", "library", name, "exports", asMap(result.get("exports")).size(),
                    "registeredNatives", registered.size());
        } catch (BridgeUnavailable | IOException e) {
            vm.events.add("gap", "native-bridge-load-failed", "library", name, "error", String.valueOf(e.getMessage()));
        }
        throwPending();
    }

    /** The {@link NativeHandler}: runs a native method in the helper if one of its libraries implements it. */
    private Object callNative(VirtualMachine machine, GuestMethod method, Object receiver, Object[] args) {
        Long address = registered.get(method.id);
        if (address == null) {
            address = exports.get(NativeLibraryInfo.jniName(method.owner.name, method.name));
        }
        if (address == null || helper == null || disabled) {
            return NativeHandler.NOT_HANDLED;
        }
        if (busy) {
            vm.events.addOnce("nested:" + method.id, "gap", "nested-native-call", "method", method.id,
                    "detail", "reached from inside another native call; not emulated");
            return NativeHandler.NOT_HANDLED;
        }
        List<Map<String, Object>> arguments = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            arguments.add(Map.of("t", letter(method.argTypes[i]), "v", toWire(args[i], method.argTypes[i])));
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("op", "call");
        message.put("address", address);
        // JNI passes the class for a static method and the object for an instance method.
        message.put("self", handle(method.isStatic ? method.owner : receiver));
        message.put("args", arguments);
        message.put("ret", letter(method.returnType));
        Object result;
        try {
            result = exclusive(message).get("v");
        } catch (BridgeUnavailable e) {
            vm.events.addOnce("failed:" + method.id, "gap", "native-bridge-call-failed", "method", method.id,
                    "error", String.valueOf(e.getMessage()));
            return NativeHandler.NOT_HANDLED;
        }
        vm.events.addOnce("bridged:" + method.id, "native", "emulated", "method", method.id,
                "address", "0x" + Long.toHexString(address));
        throwPending();
        return fromWire(result, method.returnType);
    }

    /** JNI rule: an exception left pending when native code returns is thrown in the caller. */
    private void throwPending() {
        if (pendingException != null) {
            Object exception = pendingException;
            pendingException = null;
            throw new GuestThrow(exception);
        }
    }

    // ------------------------------------------------------------------ handles and value conversion

    /** The handle native code knows an object by; the same object always gets the same one. */
    int handle(Object object) {
        if (object == null) {
            return 0;
        }
        // The same object must always get the same handle: native code compares references.
        return handles.computeIfAbsent(object, key -> {
            objects.add(key);
            return objects.size() - 1;
        });
    }

    private Object object(Object handleValue) {
        int index = ((Number) handleValue).intValue();
        return index > 0 && index < objects.size() ? objects.get(index) : null;
    }

    /** One-letter JNI type code; all object and array types are "L" on the wire. */
    private static String letter(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY ? "L" : type.getDescriptor();
    }

    private Object toWire(Object value, Type type) {
        if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
            return handle(value);
        }
        return type.getSort() == Type.VOID || value == null ? 0 : value;
    }

    /** Converts a number from the helper into the interpreter value for a JVM type. */
    private Object fromWire(Object value, Type type) {
        Number number = value instanceof Number n ? n : 0;
        switch (type.getSort()) {
            case Type.VOID:
                return null;
            case Type.BOOLEAN:
                // Native code may return any non-zero byte for true.
                return (number.intValue() & 0xFF) != 0 ? 1 : 0;
            case Type.BYTE:
                return (int) (byte) number.intValue();
            case Type.CHAR:
                return number.intValue() & 0xFFFF;
            case Type.SHORT:
                return (int) (short) number.intValue();
            case Type.INT:
                return number.intValue();
            case Type.LONG:
                return number.longValue();
            case Type.FLOAT:
                return number.floatValue();
            case Type.DOUBLE:
                return number.doubleValue();
            default:
                return object(number);
        }
    }

    private Object[] argumentsFromWire(Object list, String descriptor) {
        Type[] types = Type.getArgumentTypes(descriptor);
        List<?> wire = (List<?>) list;
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length && i < wire.size(); i++) {
            args[i] = fromWire(asMap(wire.get(i)).get("v"), types[i]);
        }
        return args;
    }

    private String className(Object classHandle) {
        Object mirror = object(classHandle);
        if (!(mirror instanceof GuestClass) && !(mirror instanceof Class<?>)) {
            throw vm.raise(new NullPointerException("JNI call with an invalid class reference"));
        }
        return vm.types.internalNameOf(mirror);
    }

    // ------------------------------------------------------------------ JNI callbacks

    /** Performs one JNI operation on behalf of native code and returns its wire result. */
    private Object callback(String name, Map<String, Object> request) {
        switch (name) {
            case "findClass": {
                Object mirror = vm.types.classMirror(Type.getObjectType(String.valueOf(request.get("name"))));
                vm.events.addOnce("jni-class:" + request.get("name"), "native", "jni-find-class",
                        "name", request.get("name"), "found", mirror != null);
                if (mirror == null) {
                    throw vm.raise(new NoClassDefFoundError(String.valueOf(request.get("name"))));
                }
                return handle(mirror);
            }
            case "hasMethod", "hasField":
                // Existence is checked when the member is used; reporting the lookup is the
                // useful part, since native code names its Java targets as plain strings.
                vm.events.addOnce("jni-member:" + request.get("cls") + request.get("name") + request.get("sig"), "native",
                        "jni-lookup", "class", className(request.get("cls")), "member", request.get("name") + " " + request.get("sig"));
                return 1;
            case "call":
                return call(request);
            case "newObject":
                return handle(vm.instantiate(className(request.get("cls")), String.valueOf(request.get("sig")),
                        argumentsFromWire(request.get("args"), String.valueOf(request.get("sig")))));
            case "getField", "setField":
                return field(name.equals("setField"), request);
            case "getObjectClass":
                return handle(vm.router.invokeVirtual("java/lang/Object", "getClass", "()Ljava/lang/Class;",
                        object(request.get("h")), new Object[0]));
            case "getSuperclass":
                return handle(vm.router.invokeVirtual("java/lang/Class", "getSuperclass", "()Ljava/lang/Class;",
                        object(request.get("cls")), new Object[0]));
            case "isAssignableFrom":
                return vm.router.invokeVirtual("java/lang/Class", "isAssignableFrom", "(Ljava/lang/Class;)Z",
                        object(request.get("sup")), new Object[] {object(request.get("sub"))});
            case "isInstanceOf": {
                Object target = object(request.get("h"));
                return target != null && vm.types.isInstance(target, className(request.get("cls"))) ? 1 : 0;
            }
            case "stringBytes": {
                Object text = object(request.get("h"));
                byte[] bytes = text instanceof String s ? s.getBytes(charset(request)) : new byte[0];
                return Base64.getEncoder().encodeToString(bytes);
            }
            case "newString":
                return handle(new String(Base64.getDecoder().decode(String.valueOf(request.get("data"))), charset(request)));
            case "arrayInfo": {
                Object array = object(request.get("h"));
                if (array != null && !array.getClass().isArray()) {
                    // A real JVM would crash here; report what native code passed instead, and
                    // answer as for an empty array so the helper stays in step.
                    vm.events.addOnce("not-array:" + vm.types.nameOf(array), "gap", "native-bridge-not-an-array",
                            "callback", "arrayInfo", "type", vm.types.nameOf(array));
                    array = null;
                }
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("length", array == null ? 0 : java.lang.reflect.Array.getLength(array));
                info.put("type", arrayLetter(array));
                return info;
            }
            case "arrayGet": {
                Object array = primitiveArray(request.get("h"));
                int start = ((Number) request.get("start")).intValue();
                int length = ((Number) request.get("length")).intValue();
                checkRange(array, start, length);
                return Base64.getEncoder().encodeToString(readArray(array, start, length));
            }
            case "arraySet": {
                Object array = primitiveArray(request.get("h"));
                int start = ((Number) request.get("start")).intValue();
                checkRange(array, start, 0);
                writeArray(array, start, Base64.getDecoder().decode(String.valueOf(request.get("data"))));
                return 0;
            }
            case "newArray":
                return handle(newArray(String.valueOf(request.get("type")), ((Number) request.get("length")).intValue(), request));
            case "objectArrayGet": {
                Object[] array = objectArray(request.get("h"));
                int index = ((Number) request.get("index")).intValue();
                checkRange(array, index, 1);
                return handle(array[index]);
            }
            case "objectArraySet": {
                Object[] array = objectArray(request.get("h"));
                int index = ((Number) request.get("index")).intValue();
                checkRange(array, index, 1);
                Object element = object(request.get("v"));
                if (element != null && !array.getClass().getComponentType().isInstance(element)) {
                    throw vm.raise(new ArrayStoreException(vm.types.nameOf(element)));
                }
                array[index] = element;
                return 0;
            }
            case "registerNatives":
                return registerNatives(request);
            case "throwNew":
                pendingException = vm.instantiate(className(request.get("cls")), "(Ljava/lang/String;)V",
                        new Object[] {String.valueOf(request.get("message"))});
                return 0;
            case "throw":
                pendingException = object(request.get("h"));
                return 0;
            case "exception":
                return exceptionQuery(String.valueOf(request.get("query")));
            default:
                vm.events.addOnce("jni-unknown:" + name, "gap", "unsupported-jni-callback", "callback", name);
                return 0;
        }
    }

    private static Charset charset(Map<String, Object> request) {
        return "utf-16le".equals(request.get("encoding")) ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8;
    }

    /** {@code Call<Type>Method} and its static and non-virtual forms, through the normal router. */
    private Object call(Map<String, Object> request) {
        String owner = className(request.get("cls"));
        String methodName = String.valueOf(request.get("name"));
        String descriptor = String.valueOf(request.get("sig"));
        Object[] args = argumentsFromWire(request.get("args"), descriptor);
        vm.events.addOnce("jni-call:" + owner + methodName + descriptor, "native", "jni-call",
                "method", owner + '.' + methodName + descriptor);
        Object result;
        if (Boolean.TRUE.equals(request.get("static"))) {
            result = vm.router.invokeStatic(owner, methodName, descriptor, args);
        } else if (methodName.equals("<init>")) {
            vm.router.construct(owner, descriptor, object(request.get("obj")), args);
            result = null;
        } else if (Boolean.TRUE.equals(request.get("nonvirtual"))) {
            result = vm.router.invokeSpecial(owner, methodName, descriptor, object(request.get("obj")), args);
        } else {
            result = vm.router.invokeVirtual(owner, methodName, descriptor, object(request.get("obj")), args);
        }
        return toWire(result, Type.getReturnType(descriptor));
    }

    private Object field(boolean write, Map<String, Object> request) {
        String owner = className(request.get("cls"));
        String fieldName = String.valueOf(request.get("name"));
        String descriptor = String.valueOf(request.get("sig"));
        Type type = Type.getType(descriptor);
        boolean isStatic = Boolean.TRUE.equals(request.get("static"));
        GuestClass guest = vm.classes.find(owner);
        FieldRef ref = guest == null ? null : guest.resolveField(fieldName, descriptor);
        FieldInsnNode node = new FieldInsnNode(isStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD, owner, fieldName, descriptor);
        Object target = object(request.get("obj"));
        if (!isStatic && "value".equals(fieldName) && isBox(target, owner)) {
            if (write) {
                // Boxes are immutable, and the JDK shares cached ones (Integer.valueOf(1) is the
                // same object everywhere), so changing one would change it for the whole JVM.
                vm.events.addOnce("box-write:" + owner, "gap", "unsupported",
                        "detail", "native code tried to change " + owner + ".value; boxes are not changed");
                return 0;
            }
            return boxValue(target);
        }
        if (write) {
            Object value = fromWire(request.get("v"), type);
            if (!isStatic) {
                vm.fields.put(target, ref, node, value);
            } else if (ref != null) {
                vm.fields.putStatic(ref, value);
            } else {
                vm.fields.putHostStatic(owner, fieldName, descriptor, value);
            }
            return 0;
        }
        Object value = !isStatic ? vm.fields.get(target, ref, node)
                : ref != null ? vm.fields.getStatic(ref) : vm.fields.getHostStatic(owner, fieldName, descriptor);
        return toWire(value, type);
    }

    /**
     * Whether {@code target} is a box of the class the field belongs to. Native code reads a
     * boxed argument through its private {@code value} field: JNA's dispatcher does this for
     * every {@code Boolean}, {@code Byte}, {@code Character}, {@code Short}, {@code Integer},
     * {@code Long}, {@code Float} and {@code Double} it passes on. JNI may read private
     * fields, while the interpreter's field access reaches only the public fields of JDK
     * objects, so these are answered here.
     */
    private static boolean isBox(Object target, String owner) {
        return target != null && owner.equals(Type.getInternalName(target.getClass()))
                && (target instanceof Number || target instanceof Boolean || target instanceof Character)
                && target.getClass().getName().startsWith("java.lang.");
    }

    /** A box's value in wire form: a number, with booleans as 0 or 1 and chars as their code. */
    private static Object boxValue(Object box) {
        if (box instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (box instanceof Character c) {
            return (int) c;
        }
        if (box instanceof Byte || box instanceof Short) {
            return ((Number) box).intValue();
        }
        return box; // Integer, Long, Float, Double
    }

    /**
     * Records methods a library binds with {@code RegisterNatives}. Libraries protected by a
     * native obfuscator export no JNI names at all and bind everything this way, so this event
     * is the only place the mapping from Java method to native address becomes visible.
     */
    private Object registerNatives(Map<String, Object> request) {
        String owner = className(request.get("cls"));
        List<String> bound = new ArrayList<>();
        for (Object entry : (List<?>) request.get("methods")) {
            Map<String, Object> method = asMap(entry);
            long address = ((Number) method.get("address")).longValue();
            registered.put(owner + '.' + method.get("name") + method.get("sig"), address);
            bound.add(method.get("name") + " " + method.get("sig") + " @0x" + Long.toHexString(address));
        }
        vm.events.add("native", "register-natives", "class", owner, "methods", bound);
        return 0;
    }

    private Object exceptionQuery(String query) {
        switch (query) {
            case "ExceptionOccurred":
                return handle(pendingException);
            case "ExceptionCheck":
                return pendingException == null ? 0 : 1;
            case "ExceptionClear":
                pendingException = null;
                return 0;
            default:
                return 0; // ExceptionDescribe prints to stderr on a real JVM; nothing to do here
        }
    }

    // ------------------------------------------------------------------ arrays

    /**
     * The object array a handle names. A bad handle raises a Java exception for native code to
     * see, rather than a host exception that would break off the callback.
     */
    private Object[] objectArray(Object handleValue) {
        Object array = object(handleValue);
        if (array instanceof Object[] objects) {
            return objects;
        }
        throw vm.raise(array == null ? new NullPointerException("JNI call with a null array reference")
                : new IllegalArgumentException("JNI call expected an object array, found " + vm.types.nameOf(array)));
    }

    /** The primitive array a handle names, raising a Java exception for anything else. */
    private Object primitiveArray(Object handleValue) {
        Object array = object(handleValue);
        if (array != null && array.getClass().isArray() && array.getClass().getComponentType().isPrimitive()) {
            return array;
        }
        throw vm.raise(array == null ? new NullPointerException("JNI call with a null array reference")
                : new IllegalArgumentException("JNI call expected a primitive array, found " + vm.types.nameOf(array)));
    }

    /**
     * Raises {@code ArrayIndexOutOfBoundsException} unless {@code start..start+length} lies inside
     * the array, as the JNI array functions do.
     */
    private void checkRange(Object array, int start, int length) {
        int size = java.lang.reflect.Array.getLength(array);
        if (start < 0 || length < 0 || (long) start + length > size) {
            throw vm.raise(new ArrayIndexOutOfBoundsException("Array region " + start + ".." + ((long) start + length)
                    + " out of bounds for length " + size));
        }
    }

    private static String arrayLetter(Object array) {
        if (array instanceof boolean[]) {
            return "Z";
        } else if (array instanceof byte[]) {
            return "B";
        } else if (array instanceof char[]) {
            return "C";
        } else if (array instanceof short[]) {
            return "S";
        } else if (array instanceof int[]) {
            return "I";
        } else if (array instanceof long[]) {
            return "J";
        } else if (array instanceof float[]) {
            return "F";
        } else if (array instanceof double[]) {
            return "D";
        }
        return "L";
    }

    /** Elements {@code start..start+length} as little-endian bytes, the layout native code expects. */
    private static byte[] readArray(Object array, int start, int length) {
        if (array instanceof byte[] bytes) {
            return java.util.Arrays.copyOfRange(bytes, start, start + length);
        }
        ByteBuffer buffer = ByteBuffer.allocate(length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = start; i < start + length; i++) {
            if (array instanceof boolean[] a) {
                buffer.put((byte) (a[i] ? 1 : 0));
            } else if (array instanceof char[] a) {
                buffer.putChar(a[i]);
            } else if (array instanceof short[] a) {
                buffer.putShort(a[i]);
            } else if (array instanceof int[] a) {
                buffer.putInt(a[i]);
            } else if (array instanceof long[] a) {
                buffer.putLong(a[i]);
            } else if (array instanceof float[] a) {
                buffer.putFloat(a[i]);
            } else if (array instanceof double[] a) {
                buffer.putDouble(a[i]);
            }
        }
        return java.util.Arrays.copyOf(buffer.array(), buffer.position());
    }

    private static void writeArray(Object array, int start, byte[] data) {
        if (array instanceof byte[] bytes) {
            System.arraycopy(data, 0, bytes, start, Math.min(data.length, bytes.length - start));
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = start; buffer.hasRemaining() && i < java.lang.reflect.Array.getLength(array); i++) {
            if (array instanceof boolean[] a) {
                a[i] = buffer.get() != 0;
            } else if (array instanceof char[] a) {
                a[i] = buffer.getChar();
            } else if (array instanceof short[] a) {
                a[i] = buffer.getShort();
            } else if (array instanceof int[] a) {
                a[i] = buffer.getInt();
            } else if (array instanceof long[] a) {
                a[i] = buffer.getLong();
            } else if (array instanceof float[] a) {
                a[i] = buffer.getFloat();
            } else if (array instanceof double[] a) {
                a[i] = buffer.getDouble();
            } else {
                return;
            }
        }
    }

    private Object newArray(String type, int length, Map<String, Object> request) {
        switch (type) {
            case "Z":
                return new boolean[length];
            case "B":
                return new byte[length];
            case "C":
                return new char[length];
            case "S":
                return new short[length];
            case "I":
                return new int[length];
            case "J":
                return new long[length];
            case "F":
                return new float[length];
            case "D":
                return new double[length];
            default: {
                Object[] array = (Object[]) java.lang.reflect.Array.newInstance(
                        vm.types.hostClassOrObject(className(request.get("cls"))), length);
                java.util.Arrays.fill(array, object(request.getOrDefault("init", 0)));
                return array;
            }
        }
    }
}

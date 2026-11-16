package dev.javabyte.stubs;

import dev.javabyte.host.CallContext;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestMethod;
import dev.javabyte.model.InstanceState;
import dev.javabyte.model.PhantomObject;
import dev.javabyte.vm.Values;
import dev.javabyte.vm.VirtualMachine;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Type;

/**
 * Class loading.
 *
 * <p>No guest class is ever loaded into the host JVM. Every loader method is answered from the
 * guest class repository, and the three ways of creating a class from bytes at run time
 * ({@code ClassLoader.defineClass}, {@code Unsafe.defineClass}, and {@code Lookup.defineClass}
 * in {@link InvokeStubs}) all end in {@link VirtualMachine#defineClass}, which records the class
 * and makes it available to the interpreter. This is how packed and staged payloads are both
 * captured and followed.
 */
final class ClassLoaderStubs {

    private ClassLoaderStubs() {}

    static void install(VirtualMachine vm) {
        // Archives already mounted from URLClassLoader URLs, so each is read only once.
        Set<String> mountedArchives = new HashSet<>();
        vm.stubs.onClass("java/lang/ClassLoader", ctx -> loaderOperation(vm, ctx, mountedArchives));
        vm.stubs.on("java/net/URLClassLoader", "newInstance", ctx -> new URLClassLoader((URL[]) ctx.arg(0), null));

        // sun.misc.Unsafe is never made available, but its class-defining methods are a common
        // way to load a payload without a class loader, so those calls are honoured.
        for (String unsafe : List.of("sun/misc/Unsafe", "jdk/internal/misc/Unsafe")) {
            vm.stubs.staticField(unsafe, "theUnsafe", () -> new PhantomObject(unsafe));
            vm.stubs.on(unsafe, "getUnsafe", ctx -> new PhantomObject(unsafe));
            vm.stubs.on(unsafe, "defineClass", ctx -> define(vm, ctx, "Unsafe.defineClass"));
            vm.stubs.on(unsafe, "defineAnonymousClass", ctx ->
                    vm.defineClass(null, (byte[]) ctx.arg(1), "Unsafe.defineAnonymousClass"));
        }
    }

    private static Object loaderOperation(VirtualMachine vm, CallContext ctx, Set<String> mountedArchives) throws Exception {
        switch (ctx.name) {
            case "<init>":
                return ctx.proceed();
            case "defineClass":
                return define(vm, ctx, "ClassLoader.defineClass");
            case "loadClass", "findClass", "findSystemClass", "findLoadedClass":
                return load(vm, ctx, mountedArchives);
            case "getSystemClassLoader", "getPlatformClassLoader":
                return vm.env.appLoader;
            case "getParent":
                return ctx.receiver == vm.env.appLoader ? null : vm.env.appLoader;
            case "getResourceAsStream", "getSystemResourceAsStream":
                return ReflectionStubs.resourceStream(vm, stripSlash(ctx.stringArg(0)));
            case "getResource", "getSystemResource", "findResource":
                return ReflectionStubs.resourceUrl(vm, stripSlash(ctx.stringArg(0)));
            case "getResources", "getSystemResources", "findResources": {
                Object url = ReflectionStubs.resourceUrl(vm, stripSlash(ctx.stringArg(0)));
                return Collections.enumeration(url == null ? List.of() : List.of(url));
            }
            case "getURLs":
                return ctx.receiver instanceof URLClassLoader ? ctx.proceed() : new URL[0];
            case "registerAsParallelCapable":
                return 1;
            case "getClassLoadingLock":
                return ctx.receiver;
            case "getPackages", "getDefinedPackages":
                return new Package[0];
            case "hashCode":
                return System.identityHashCode(ctx.receiver);
            case "equals":
                return ctx.receiver == ctx.arg(0) ? 1 : 0;
            case "toString":
                return vm.types.nameOf(ctx.receiver) + '@' + Integer.toHexString(System.identityHashCode(ctx.receiver));
            case "resolveClass", "addURL", "close", "setDefaultAssertionStatus", "setPackageAssertionStatus",
                    "setClassAssertionStatus", "clearAssertionStatus", "definePackage", "getPackage",
                    "getDefinedPackage", "getName":
                // Bookkeeping with no effect on what the guest can load.
                return Values.zero(Type.getReturnType(ctx.desc));
            default:
                return vm.router.gap("ClassLoader." + ctx.name + ctx.desc, ctx.desc);
        }
    }

    private static String stripSlash(String name) {
        return name.startsWith("/") ? name.substring(1) : name;
    }

    /**
     * Handles every {@code defineClass} overload by locating the bytes among the arguments:
     * either a {@code byte[]} followed by offset and length, or a {@code ByteBuffer}.
     */
    private static Object define(VirtualMachine vm, CallContext ctx, String via) {
        String requestedName = null;
        byte[] bytes = null;
        for (int i = 0; i < ctx.argCount() && bytes == null; i++) {
            Object arg = ctx.arg(i);
            if (arg instanceof String name && requestedName == null) {
                requestedName = name;
            } else if (arg instanceof byte[] array) {
                boolean ranged = i + 2 < ctx.argCount() && ctx.arg(i + 1) instanceof Integer && ctx.arg(i + 2) instanceof Integer;
                int offset = ranged ? ctx.intArg(i + 1) : 0;
                int length = ranged ? ctx.intArg(i + 2) : array.length;
                if (offset < 0 || length < 0 || offset + length > array.length) {
                    throw ctx.raise(new IndexOutOfBoundsException("defineClass range " + offset + "+" + length));
                }
                bytes = java.util.Arrays.copyOfRange(array, offset, offset + length);
            } else if (arg instanceof ByteBuffer buffer) {
                bytes = new byte[buffer.remaining()];
                buffer.duplicate().get(bytes);
            }
        }
        if (bytes == null) {
            throw ctx.raise(new NullPointerException("defineClass called without class bytes"));
        }
        return vm.defineClass(requestedName, bytes, via);
    }

    /**
     * Implements the loading methods with the JVM's delegation order: classes already known,
     * then the loader's own {@code findClass} if the sample overrides it, then any archives a
     * {@code URLClassLoader} points at, then the JDK.
     */
    private static Object load(VirtualMachine vm, CallContext ctx, Set<String> mountedArchives) throws Exception {
        String name = ctx.stringArg(0);
        String internalName = name.replace('.', '/');
        GuestClass guest = vm.classes.find(internalName);
        if (guest == null && ctx.name.equals("loadClass")) {
            InstanceState state = vm.stateOf(ctx.receiver);
            GuestMethod finder = state == null ? null
                    : state.type.findVirtual("findClass", "(Ljava/lang/String;)Ljava/lang/Class;");
            if (finder != null) {
                // A custom loader: let the sample's own findClass decrypt and define the class.
                return vm.router.callGuest(finder, ctx.receiver, new Object[] {name});
            }
        }
        if (guest == null && ctx.receiver instanceof URLClassLoader urlLoader) {
            mountArchives(vm, urlLoader, mountedArchives);
            guest = vm.classes.find(internalName);
        }
        if (guest != null) {
            vm.events.addOnce("load:" + name, "class", "load", "name", name, "via", ctx.name);
            return guest;
        }
        if (ctx.name.equals("findLoadedClass")) {
            return null;
        }
        Class<?> host = ctx.name.equals("findClass") ? null : vm.host.findClass(internalName);
        if (host == null) {
            vm.events.addOnce("load-miss:" + name, "class", "load-missing", "name", name);
            throw new ClassNotFoundException(name);
        }
        return host;
    }

    /** Reads the jars a {@code URLClassLoader} was given, from the virtual disk or simulated network. */
    private static void mountArchives(VirtualMachine vm, URLClassLoader loader, Set<String> mounted) {
        for (URL url : loader.getURLs()) {
            if (!mounted.add(url.toExternalForm())) {
                continue;
            }
            byte[] archive = null;
            try {
                if (url.getProtocol().equals("file")) {
                    String path = java.net.URLDecoder.decode(url.getPath(), java.nio.charset.StandardCharsets.UTF_8);
                    archive = vm.env.files.read(vm.env.windows && path.matches("/[A-Za-z]:.*") ? path.substring(1) : path);
                } else if (url.getProtocol().startsWith("http")) {
                    archive = vm.env.network.http("GET", url.toExternalForm(), Map.of(), new byte[0]).body();
                }
                if (archive != null && archive.length > 0) {
                    vm.classes.addArchive(archive, "URLClassLoader:" + url);
                    vm.artifacts.save("loaded-archives", VirtualMachine.sha256(archive).substring(0, 16) + ".jar", archive);
                }
            } catch (IOException e) {
                archive = null;
            }
            vm.events.add("class", "load-archive", "url", url.toExternalForm(),
                    "found", archive != null && archive.length > 0,
                    "sha256", archive == null || archive.length == 0 ? null : VirtualMachine.sha256(archive));
        }
    }
}

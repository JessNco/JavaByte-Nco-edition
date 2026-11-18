package dev.javabyte.stubs;

import dev.javabyte.env.SimPlatformClassLoader;
import dev.javabyte.env.SimURLClassLoader;
import dev.javabyte.host.CallContext;
import dev.javabyte.host.HostBridge;
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
 *
 * <p>The guest never holds a loader that fetches anything by itself. {@code new URLClassLoader}
 * yields a {@link SimURLClassLoader}, which only remembers its URLs; the archives they name are
 * read here, from the virtual disk or the network simulator. The application loader and its
 * parent are simulated too, and look like those of the Java version the guest environment
 * advertises.
 */
final class ClassLoaderStubs {

    private ClassLoaderStubs() {}

    static void install(VirtualMachine vm) {
        // Archives already mounted from URLClassLoader URLs, so each is read only once.
        Set<String> mountedArchives = new HashSet<>();
        BuiltinLoaders builtins = new BuiltinLoaders(vm);
        vm.stubs.onClass("java/lang/ClassLoader", ctx -> loaderOperation(vm, ctx, mountedArchives, builtins));
        // A real URLClassLoader fetches from its URLs whenever JDK code asks it for something,
        // so the guest gets a loader that only remembers them. The overloads are
        // (URL[]), (URL[], parent), (URL[], parent, factory), (name, URL[], parent) and
        // (name, URL[], parent, factory); newInstance has the first two shapes.
        vm.stubs.on("java/net/URLClassLoader", "<init>", ctx -> urlLoader(vm, ctx));
        vm.stubs.on("java/net/URLClassLoader", "newInstance", ctx -> urlLoader(vm, ctx));

        // sun.misc.Unsafe is never made available, but its class-defining methods are a common
        // way to load a payload without a class loader, so those calls are honoured.
        for (String unsafe : List.of("sun/misc/Unsafe", "jdk/internal/misc/Unsafe")) {
            vm.stubs.staticField(unsafe, "theUnsafe", () -> new PhantomObject(unsafe));
            vm.stubs.on(unsafe, "getUnsafe", ctx -> new PhantomObject(unsafe));
            vm.stubs.on(unsafe, "defineClass", ctx -> define(vm, ctx, "Unsafe.defineClass"));
            vm.stubs.on(unsafe, "defineAnonymousClass", ctx ->
                    vm.defineClass(null, (byte[]) ctx.arg(1), "Unsafe.defineAnonymousClass"));
        }

        // Objects that JDK code would otherwise use to reach the network or load classes on
        // the guest's behalf. Security providers belong here in particular: each is a table of
        // class names that the JDK loads and constructs.
        InertObjectStubs.install(vm);
    }

    /** Creates the guest's {@code URLClassLoader} from any constructor or {@code newInstance} overload. */
    private static SimURLClassLoader urlLoader(VirtualMachine vm, CallContext ctx) {
        boolean named = ctx.argCount() > 0 && ctx.arg(0) instanceof String;
        int urlsAt = named ? 1 : 0;
        String name = named ? ctx.stringArg(0) : null;
        URL[] urls = (URL[]) ctx.arg(urlsAt);
        if (urls == null) {
            throw ctx.raise(new NullPointerException());
        }
        // Without an explicit parent, the JDK uses the application class loader.
        ClassLoader parent = ctx.argCount() <= urlsAt + 1 ? vm.env.appLoader
                : ctx.arg(urlsAt + 1) instanceof ClassLoader given ? given : null;
        List<String> urlTexts = new java.util.ArrayList<>();
        for (URL url : urls) {
            urlTexts.add(url == null ? null : url.toExternalForm());
        }
        vm.events.add("class", "url-loader", "urls", urlTexts);
        return new SimURLClassLoader(name, urls, parent);
    }

    /**
     * The guest's view of the JVM's built-in loaders: the application loader from the guest
     * environment, and its parent (the platform loader from Java 9, the extension loader on
     * Java 8), whose own parent is the bootstrap loader, reported as null.
     *
     * <p>Names, {@code toString()} and {@code hashCode()} follow the Java version the guest
     * environment advertises. The hash codes are fixed, typical values, so every run of a
     * sample sees the same ones.
     */
    private static final class BuiltinLoaders {

        private final VirtualMachine vm;
        private SimPlatformClassLoader platform;

        BuiltinLoaders(VirtualMachine vm) {
            this.vm = vm;
        }

        /**
         * True when the guest environment advertises Java 8 or older. Read on each call: the
         * properties are filled in after stubs are installed, and config can change them.
         */
        boolean java8() {
            return vm.env.properties.getProperty("java.specification.version", "1.8").startsWith("1.");
        }

        /** The application loader's parent; created on first use, then always the same object. */
        SimPlatformClassLoader platform() {
            if (platform == null) {
                platform = new SimPlatformClassLoader(java8() ? "sun.misc.Launcher$ExtClassLoader@7852e922"
                        : "jdk.internal.loader.ClassLoaders$PlatformClassLoader@4e25154f");
            }
            return platform;
        }

        /** True for the application loader and its parent. */
        boolean isBuiltin(Object loader) {
            return loader == vm.env.appLoader || (loader != null && loader == platform);
        }

        /** {@code toString()} of a built-in loader. */
        String describe(Object loader) {
            if (loader == vm.env.appLoader) {
                return java8() ? "sun.misc.Launcher$AppClassLoader@18b4aac2"
                        : "jdk.internal.loader.ClassLoaders$AppClassLoader@1b6d3586";
            }
            return platform().toString();
        }

        /** {@code hashCode()} of a built-in loader: the value its {@code toString()} shows. */
        int hash(Object loader) {
            String text = describe(loader);
            return Integer.parseUnsignedInt(text.substring(text.indexOf('@') + 1), 16);
        }

        /**
         * {@code getName()} of a built-in loader: "app" or "platform". Java 8 has no loader
         * names, so null is the nearest answer for a sample that calls it anyway.
         */
        String name(Object loader) {
            if (java8()) {
                return null;
            }
            return loader == vm.env.appLoader ? "app" : "platform";
        }
    }

    private static Object loaderOperation(VirtualMachine vm, CallContext ctx, Set<String> mountedArchives,
            BuiltinLoaders builtins) throws Exception {
        switch (ctx.name) {
            case "<init>":
                return ctx.proceed();
            case "defineClass":
                return define(vm, ctx, "ClassLoader.defineClass");
            case "loadClass", "findClass", "findSystemClass", "findLoadedClass":
                return load(vm, ctx, mountedArchives);
            case "getSystemClassLoader":
                return vm.env.appLoader;
            case "getPlatformClassLoader":
                // Java 9 and later only; on a Java 8 profile it answers with the extension loader.
                return builtins.platform();
            case "getParent":
                if (ctx.receiver == vm.env.appLoader) {
                    return builtins.platform();
                }
                if (builtins.isBuiltin(ctx.receiver)) {
                    return null;
                }
                return ctx.receiver instanceof SimURLClassLoader urlLoader ? urlLoader.guestParent() : vm.env.appLoader;
            case "getName":
                if (builtins.isBuiltin(ctx.receiver)) {
                    return builtins.name(ctx.receiver);
                }
                // The name given to the constructor, held in a field; reading it is harmless.
                return ctx.receiver instanceof ClassLoader loader ? loader.getName() : null;
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
            case "addURL":
                // Samples reach this protected method by reflection to extend a loader's path.
                if (ctx.receiver instanceof SimURLClassLoader urlLoader && ctx.arg(0) instanceof URL url) {
                    urlLoader.addGuestURL(url);
                    vm.events.add("class", "url-loader-add", "url", url.toExternalForm());
                }
                return null;
            case "registerAsParallelCapable":
                return 1;
            case "getClassLoadingLock":
                return ctx.receiver;
            case "getPackages", "getDefinedPackages":
                return new Package[0];
            case "hashCode":
                return builtins.isBuiltin(ctx.receiver) ? builtins.hash(ctx.receiver) : System.identityHashCode(ctx.receiver);
            case "equals":
                return ctx.receiver == ctx.arg(0) ? 1 : 0;
            case "toString":
                if (builtins.isBuiltin(ctx.receiver)) {
                    return builtins.describe(ctx.receiver);
                }
                return ctx.receiver instanceof SimURLClassLoader ? ctx.receiver.toString()
                        : visibleClassName(vm, ctx.receiver) + '@' + Integer.toHexString(System.identityHashCode(ctx.receiver));
            case "resolveClass", "close", "setDefaultAssertionStatus", "setPackageAssertionStatus",
                    "setClassAssertionStatus", "clearAssertionStatus", "definePackage", "getPackage",
                    "getDefinedPackage":
                // Bookkeeping with no effect on what the guest can load.
                return Values.zero(Type.getReturnType(ctx.desc));
            default:
                return vm.router.gap("ClassLoader." + ctx.name + ctx.desc, ctx.desc);
        }
    }

    /**
     * The class name the guest would see for a loader. The emulator's own loaders are named
     * after the JDK class they extend, as {@code getClass()} reports them.
     */
    private static String visibleClassName(VirtualMachine vm, Object loader) {
        if (!HostBridge.isSimulated(loader)) {
            return vm.types.nameOf(loader);
        }
        Class<?> cls = loader.getClass();
        while (cls.getName().startsWith("dev.javabyte.")) {
            cls = cls.getSuperclass();
        }
        return cls.getName();
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
        // Only a real loader object can be asked for further classes later.
        Object loader = ctx.receiver instanceof dev.javabyte.model.PhantomObject ? null : ctx.receiver;
        return vm.defineClass(requestedName, bytes, via, loader);
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
                    archive = vm.env.files.read(FileStubs.pathFromFileUrl(url.getPath(), vm.env.windows));
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

package dev.javabyte.stubs;

import dev.javabyte.env.SimLoaderSupport;
import dev.javabyte.env.SimPlatformClassLoader;
import dev.javabyte.env.SimSecureClassLoader;
import dev.javabyte.env.SimURLClassLoader;
import dev.javabyte.env.SimulatedLoader;
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
 * guest class repository, and every way of creating a class from bytes at run time
 * ({@code ClassLoader.defineClass} and {@code Unsafe.defineClass}/{@code defineAnonymousClass}
 * here, and {@code Lookup.defineClass}/{@code defineHiddenClass}/{@code defineHiddenClassWithClassData}
 * in {@link InvokeStubs}) ends in {@link VirtualMachine#defineClass}, which records the class
 * and makes it available to the interpreter. This is how packed and staged payloads are both
 * captured and followed.
 *
 * <p>The guest never holds a loader that fetches anything by itself. {@code new URLClassLoader}
 * yields a {@link SimURLClassLoader}, which only remembers its URLs; the archives they name are
 * read here, from the virtual disk or the network simulator. A sample's own subclass of
 * {@code URLClassLoader}, {@code SecureClassLoader} or {@code ClassLoader} is built on a
 * simulated loader too (see {@link dev.javabyte.shell.ShellFactory}), whose real parent is the
 * bootstrap loader, so that JDK code calling it directly cannot reach the emulator's own class
 * path. The application loader and its parent are simulated as well, and look like those of
 * the Java version the guest environment advertises: on Java 8 both are
 * {@code URLClassLoader}s, and {@code getURLs} lists the sample's class path and the usual
 * extension jars.
 *
 * <p>Objects the JDK would make inert at creation, such as security providers, have their
 * own stubs in {@link InertObjectStubs}.
 */
final class ClassLoaderStubs {

    private ClassLoaderStubs() {}

    /**
     * Installs the class-loading stubs, and makes the application loader's own text follow the
     * advertised Java version and its URLs follow the guest's class path.
     */
    static void install(VirtualMachine vm) {
        vm.env.appLoader.followJavaVersion(() -> SystemStubs.legacyJava(vm.env));
        vm.env.appLoader.followClassPath(() -> classPathUrls(vm));
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
        // SecureClassLoader, unlike ClassLoader, is not abstract, and policy lists its
        // constructors so that a sample's subclass can be built on the simulated base. Bytecode
        // that constructs one directly gets the same simulated loader, never a real one, whose
        // parent would be the emulator's own application loader.
        vm.stubs.on("java/security/SecureClassLoader", "<init>", ctx -> secureLoader(ctx));

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

    /**
     * The guest's {@code java.class.path} as the URLs a Java 8 application loader reports: one
     * {@code file:} URL per entry, with a trailing slash for a directory. They are built on a
     * handler that refuses to open them, as they name paths on the virtual disk.
     */
    private static URL[] classPathUrls(VirtualMachine vm) {
        return pathUrls(vm, vm.env.properties.getProperty("java.class.path", ""));
    }

    /** The jars a Java 8 extension directory holds, in the order a directory listing gives them. */
    private static final List<String> WINDOWS_EXTENSION_JARS = List.of("access-bridge-64.jar", "cldrdata.jar",
            "dnsns.jar", "jaccess.jar", "jfxrt.jar", "localedata.jar", "nashorn.jar", "sunec.jar",
            "sunjce_provider.jar", "sunmscapi.jar", "sunpkcs11.jar", "zipfs.jar");
    private static final List<String> LINUX_EXTENSION_JARS = List.of("cldrdata.jar", "dnsns.jar",
            "icedtea-sound.jar", "jaccess.jar", "localedata.jar", "nashorn.jar", "sunec.jar",
            "sunjce_provider.jar", "sunpkcs11.jar", "zipfs.jar");

    /**
     * The URLs of Java 8's extension loader: the usual jars of the first extension directory
     * (the others normally do not exist). The jars are not on the virtual disk, just as the
     * boot class path's jars are only names.
     */
    private static URL[] extensionUrls(VirtualMachine vm) {
        String separator = vm.env.properties.getProperty("path.separator", vm.env.windows ? ";" : ":");
        String first = vm.env.properties.getProperty("java.ext.dirs", "").split(java.util.regex.Pattern.quote(separator))[0];
        if (first.isEmpty()) {
            return new URL[0];
        }
        StringBuilder jars = new StringBuilder();
        for (String jar : vm.env.windows ? WINDOWS_EXTENSION_JARS : LINUX_EXTENSION_JARS) {
            jars.append(jars.length() == 0 ? "" : separator).append(vm.env.files.join(first, jar));
        }
        return pathUrls(vm, jars.toString());
    }

    /** One {@code file:} URL per entry of a search path, a directory's with a trailing slash. */
    private static URL[] pathUrls(VirtualMachine vm, String path) {
        String separator = vm.env.properties.getProperty("path.separator", vm.env.windows ? ";" : ":");
        List<URL> urls = new java.util.ArrayList<>();
        for (String entry : path.split(java.util.regex.Pattern.quote(separator))) {
            if (entry.isEmpty()) {
                continue;
            }
            try {
                URL url = SimLoaderSupport.fileUrl(vm, entry);
                if (vm.env.files.isDirectory(entry) && !url.getPath().endsWith("/")) {
                    url = new URL(url, url.getPath() + '/');
                }
                urls.add(url);
            } catch (java.net.MalformedURLException unrepresentable) {
                // A real loader skips an entry it cannot turn into a URL.
            }
        }
        return urls.toArray(new URL[0]);
    }

    /**
     * A {@code SecureClassLoader} constructed directly, from {@code ()}, {@code (parent)} or
     * {@code (name, parent)}: the simulated loader that a sample's own subclass extends.
     */
    private static SimSecureClassLoader secureLoader(CallContext ctx) {
        return switch (ctx.argCount()) {
            case 0 -> new SimSecureClassLoader();
            case 1 -> new SimSecureClassLoader(ctx.arg(0) instanceof ClassLoader parent ? parent : null);
            default -> new SimSecureClassLoader(ctx.stringArg(0), ctx.arg(1) instanceof ClassLoader parent ? parent : null);
        };
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

        /** True when the guest environment advertises Java 8 or older; see {@link SystemStubs#legacyJava}. */
        boolean java8() {
            return SystemStubs.legacyJava(vm.env);
        }

        /** The application loader's parent; created on first use, then always the same object. */
        SimPlatformClassLoader platform() {
            if (platform == null) {
                platform = new SimPlatformClassLoader(java8() ? "sun.misc.Launcher$ExtClassLoader@7852e922"
                        : "jdk.internal.loader.ClassLoaders$PlatformClassLoader@4e25154f",
                        () -> java8() ? extensionUrls(vm) : new URL[0]);
            }
            return platform;
        }

        /** True for the application loader and its parent. */
        boolean isBuiltin(Object loader) {
            return loader == vm.env.appLoader || (loader != null && loader == platform);
        }

        /**
         * {@code toString()} of a built-in loader. The application loader's text is its own, so
         * guest calls that bypass the stubs see the same.
         */
        String describe(Object loader) {
            return loader == vm.env.appLoader ? vm.env.appLoader.toString() : platform().toString();
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

    /**
     * Answers every {@code ClassLoader} method the guest calls, on any loader it holds: the
     * built-in ones, {@code URLClassLoader}s and the sample's own loaders.
     */
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
                // The parent the sample passed to its loader's constructor; the real parent of
                // every simulated loader is the bootstrap loader.
                return ctx.receiver instanceof SimulatedLoader loader && !loader.hasDefaultParent()
                        ? loader.guestParent() : vm.env.appLoader;
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
                // Samples reach this protected method by reflection to extend a loader's path,
                // classically the Java 8 application loader's. The URL is only remembered.
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
                // A real identity hash would differ between runs; see TypeSystem.identityHash.
                return builtins.isBuiltin(ctx.receiver) ? builtins.hash(ctx.receiver) : vm.types.identityHash(ctx.receiver);
            case "equals":
                return ctx.receiver == ctx.arg(0) ? 1 : 0;
            case "toString":
                if (builtins.isBuiltin(ctx.receiver)) {
                    return builtins.describe(ctx.receiver);
                }
                // Object.toString's form, with the class the guest sees: a sample's own loader by
                // its own name, the emulator's loaders by the JDK class they stand for.
                return vm.types.nameOf(ctx.receiver) + '@' + Integer.toHexString(vm.types.identityHash(ctx.receiver));
            case "resolveClass", "close", "setDefaultAssertionStatus", "setPackageAssertionStatus",
                    "setClassAssertionStatus", "clearAssertionStatus", "definePackage", "getPackage",
                    "getDefinedPackage":
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
        // Only a real loader object can be asked for further classes later.
        Object loader = ctx.receiver instanceof dev.javabyte.model.PhantomObject ? null : ctx.receiver;
        return vm.defineClass(requestedName, bytes, via, loader);
    }

    /**
     * Implements the loading methods with the JVM's delegation order: classes already known,
     * then, for {@code loadClass}, the JDK (which every parent chain reaches first) and the
     * loader's own {@code findClass} if the sample overrides it, then any archives a
     * {@code URLClassLoader} points at, then the JDK.
     */
    private static Object load(VirtualMachine vm, CallContext ctx, Set<String> mountedArchives) throws Exception {
        String name = ctx.stringArg(0);
        String internalName = name.replace('.', '/');
        GuestClass guest = vm.classes.find(internalName);
        if (guest == null && ctx.name.equals("loadClass")) {
            Class<?> jdk = vm.host.findClass(internalName);
            if (jdk != null) {
                // Parent first: a sample's findClass is never asked for a JDK class.
                return jdk;
            }
            InstanceState state = vm.stateOf(ctx.receiver);
            GuestMethod finder = state == null ? null
                    : state.type.findVirtual("findClass", "(Ljava/lang/String;)Ljava/lang/Class;");
            if (finder != null) {
                // A custom loader: let the sample's own findClass decrypt and define the class.
                return vm.router.callGuest(finder, ctx.receiver, new Object[] {name});
            }
        }
        if (guest == null && ctx.receiver instanceof SimURLClassLoader urlLoader) {
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

    /**
     * Reads the jars a {@code URLClassLoader} was given or had added, from the virtual disk or
     * simulated network. The built-in loaders' own path is not read: it is the sample itself,
     * already loaded, and extension jars that exist only as names.
     */
    private static void mountArchives(VirtualMachine vm, SimURLClassLoader loader, Set<String> mounted) {
        for (URL url : loader.guestURLs()) {
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

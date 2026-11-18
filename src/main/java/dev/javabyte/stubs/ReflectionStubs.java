package dev.javabyte.stubs;

import dev.javabyte.env.SimFile;
import dev.javabyte.env.SimPackage;
import dev.javabyte.host.CallContext;
import dev.javabyte.host.StubRegistry;
import dev.javabyte.model.FieldRef;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestMethod;
import dev.javabyte.model.InstanceState;
import dev.javabyte.model.Mirrors.ConstructorMirror;
import dev.javabyte.model.Mirrors.FieldMirror;
import dev.javabyte.model.Mirrors.MethodMirror;
import dev.javabyte.vm.GuestThrow;
import dev.javabyte.vm.Values;
import dev.javabyte.vm.VirtualMachine;
import java.io.ByteArrayInputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldNode;

/**
 * {@code java.lang.Class} and {@code java.lang.reflect}.
 *
 * <p>Two things happen here. First, interpreted classes get working reflection: their
 * {@link GuestClass} acts as the class object and mirrors act as methods, fields and
 * constructors. Second, reflection on real JDK classes is made safe: looking members up is
 * allowed, but invoking, constructing or reading through them is routed back into the
 * {@link dev.javabyte.vm.InvocationRouter}. A reflective {@code Runtime.exec} therefore meets
 * the same stub as a direct one.
 */
final class ReflectionStubs {

    private ReflectionStubs() {}

    /** {@code Class} methods that only describe a JDK class and can run for real. */
    private static final Set<String> HARMLESS_CLASS_METHODS = Set.of(
            "getName", "getSimpleName", "getTypeName", "getCanonicalName", "getPackageName", "getPackage",
            "isArray", "isInterface", "isPrimitive", "isEnum", "isAnnotation", "isSynthetic", "isAnonymousClass",
            "isLocalClass", "isMemberClass", "isRecord", "isHidden", "getComponentType", "componentType", "arrayType",
            "getSuperclass", "getGenericSuperclass", "getInterfaces", "getGenericInterfaces", "getModifiers",
            "getEnumConstants", "desiredAssertionStatus", "hashCode", "equals", "toString", "getDeclaringClass",
            "getEnclosingClass", "getAnnotation", "getAnnotations", "getDeclaredAnnotations", "isAnnotationPresent",
            "getSigners", "getTypeParameters", "asSubclass", "descriptorString", "getNestHost", "getClasses",
            "getDeclaredClasses", "getMethod", "getDeclaredMethod", "getMethods", "getDeclaredMethods", "getField",
            "getDeclaredField", "getFields", "getDeclaredFields", "getConstructor", "getDeclaredConstructor",
            "getConstructors", "getDeclaredConstructors", "getRecordComponents", "getEnclosingMethod");

    /** The {@code AccessibleObject} methods that ask for, or about, access to a member. */
    private static final Set<String> ACCESS_CALLS = Set.of("setAccessible", "trySetAccessible", "isAccessible", "canAccess");

    /**
     * True if the call is one of {@link #ACCESS_CALLS}, which {@link #accessAnswer} answers
     * whatever the receiver is.
     */
    private static boolean isAccessCall(String name) {
        return ACCESS_CALLS.contains(name);
    }

    /**
     * The one answer to every access call, for real members and interpreted ones alike.
     *
     * <p>setAccessible is never performed: the emulator does not open JDK internals for a sample,
     * so reading a private JDK field stays blocked. It is still reported as successful, and the
     * queries say the member is accessible, because samples that probe for a sandbox treat a
     * refusal as a sign of one. Access checks do not apply to interpreted members in the first
     * place. Keeping this in one place means no path can drift and grant real access.
     *
     * @return null for {@code setAccessible} (it returns void), else 1 (boolean true)
     */
    private static Object accessAnswer(String name) {
        return name.equals("setAccessible") ? null : (Object) 1;
    }

    /**
     * The JDK frames that Java 8's {@code Class.newInstance} puts between the caller and the
     * constructor, innermost first: those of {@code Constructor.newInstance}, which it calls,
     * and its own.
     */
    static final List<StackTraceElement> CLASS_NEW_INSTANCE_FRAMES;

    static {
        List<StackTraceElement> frames = new ArrayList<>(VirtualMachine.CONSTRUCTOR_NEW_INSTANCE_FRAMES);
        frames.add(new StackTraceElement("java.lang.Class", "newInstance", "Class.java", 442));
        CLASS_NEW_INSTANCE_FRAMES = List.copyOf(frames);
    }

    /** Installs the reflection stubs, with one {@code Package} object per guest package for the run. */
    static void install(VirtualMachine vm) {
        StubRegistry stubs = vm.stubs;
        Map<String, SimPackage> packages = new HashMap<>();
        stubs.onClass("java/lang/Class", ctx -> classOperation(vm, ctx, packages));
        stubs.onClass("java/lang/reflect/Method", ctx -> methodOperation(vm, ctx));
        stubs.onClass("java/lang/reflect/Field", ctx -> fieldOperation(vm, ctx));
        stubs.onClass("java/lang/reflect/Constructor", ctx -> constructorOperation(vm, ctx));
        for (String name : ACCESS_CALLS) {
            stubs.on("java/lang/reflect/AccessibleObject", name, ctx -> accessAnswer(ctx.name));
        }

        stubs.on("java/lang/reflect/Array", "newInstance", ctx -> {
            Object component = ctx.arg(0);
            Class<?> element = component instanceof Class<?> cls && cls.isPrimitive() ? cls
                    : vm.types.hostClassOrObject(component instanceof Class<?> cls && cls.isArray()
                            ? Type.getDescriptor(cls) : vm.types.internalNameOf(component));
            return ctx.arg(1) instanceof int[] dimensions
                    ? java.lang.reflect.Array.newInstance(element, dimensions)
                    : java.lang.reflect.Array.newInstance(element, ctx.intArg(1));
        });

        stubs.on("java/lang/reflect/Proxy", "newProxyInstance", ctx -> {
            List<Class<?>> interfaces = new ArrayList<>();
            for (Object iface : (Object[]) ctx.arg(1)) {
                if (!(iface instanceof Class<?> cls)) {
                    // The JDK cannot proxy an interface it has no class for; the interpreter can.
                    vm.events.add("reflect", "proxy", "interfaces", java.util.Arrays.toString((Object[]) ctx.arg(1)));
                    return new dev.javabyte.model.GuestProxy(List.of((Object[]) ctx.arg(1)), ctx.arg(2), vm.router::invokeProxy);
                }
                interfaces.add(cls);
            }
            if (!(ctx.arg(2) instanceof InvocationHandler handler)) {
                return vm.router.gap("Proxy handler is not a JDK-visible object", ctx.desc);
            }
            vm.events.add("reflect", "proxy", "interfaces", interfaces.toString());
            return Proxy.newProxyInstance(vm.shells.loader(), interfaces.toArray(Class<?>[]::new), handler);
        });

        // Proxies for interpreted interfaces are not JDK proxies, so the JDK's own answers
        // about them would be wrong.
        stubs.on("java/lang/reflect/Proxy", "isProxyClass",
                ctx -> ctx.arg(0) == Proxy.class || (ctx.arg(0) instanceof Class<?> cls && Proxy.isProxyClass(cls)) ? 1 : 0);
        stubs.on("java/lang/reflect/Proxy", "getInvocationHandler",
                ctx -> ctx.arg(0) instanceof dev.javabyte.model.GuestProxy proxy ? proxy.handler() : ctx.proceed());

        // Resource bundles are read from the sample's own archive, never from the class path.
        stubs.on("java/util/ResourceBundle", "getBundle", ctx -> {
            String path = ctx.stringArg(0).replace('.', '/') + ".properties";
            byte[] data = vm.classes.resource(path);
            vm.events.addOnce("bundle:" + path, "resource", "read", "name", path, "found", data != null);
            if (data == null) {
                throw ctx.raise(new java.util.MissingResourceException(
                        "Can't find bundle for base name " + ctx.arg(0), ctx.stringArg(0), ""));
            }
            return new java.util.PropertyResourceBundle(new ByteArrayInputStream(data));
        });

        // For a guest enum the real method would return the generated shell class.
        stubs.on("java/lang/Enum", "getDeclaringClass", ctx -> {
            dev.javabyte.model.InstanceState state = vm.stateOf(ctx.receiver);
            if (state == null) {
                return ctx.proceed();
            }
            // A constant with a body is an anonymous subclass; its declaring class is the enum.
            GuestClass type = state.type;
            return type.superClass() != null && !"java/lang/Enum".equals(type.node.superName) ? type.superClass() : type;
        });
        stubs.on("java/lang/Enum", "valueOf", ctx -> {
            if (!(ctx.arg(0) instanceof GuestClass guest)) {
                return ctx.proceed();
            }
            for (Object constant : enumConstants(vm, guest)) {
                if (constant instanceof Enum<?> value && value.name().equals(ctx.stringArg(1))) {
                    return constant;
                }
            }
            throw ctx.raise(new IllegalArgumentException("No enum constant " + guest.dottedName() + '.' + ctx.arg(1)));
        });
    }

    // ------------------------------------------------------------------ java.lang.Class

    /**
     * Answers a {@code java.lang.Class} method, on a guest class mirror or a real JDK class.
     *
     * @param packages the run's {@code Package} objects for guest packages, by package name
     */
    private static Object classOperation(VirtualMachine vm, CallContext ctx, Map<String, SimPackage> packages)
            throws Exception {
        if (ctx.name.equals("forName")) {
            // forName(String) initialises; forName(String, boolean, ClassLoader) says whether to.
            boolean initialise = ctx.argCount() < 2 || !(ctx.arg(1) instanceof Integer flag) || flag != 0;
            String name = ctx.arg(0) instanceof String s ? s : ctx.stringArg(1); // forName(Module, String)
            return forName(vm, name, initialise);
        }
        if (ctx.receiver instanceof GuestClass guest) {
            return guestClassOperation(vm, guest, ctx, packages);
        }
        Class<?> cls = (Class<?>) ctx.receiver;
        String internalName = cls.isArray() || cls.isPrimitive() ? null : Type.getInternalName(cls);
        switch (ctx.name) {
            case "newInstance":
                // Class.newInstance passes the constructor's exceptions on unwrapped.
                return vm.callThroughJdk(CLASS_NEW_INSTANCE_FRAMES, () -> vm.instantiate(internalName, "()V", new Object[0]));
            case "getResourceAsStream":
                return resourceStream(vm, resolveResourceName(internalName, ctx.stringArg(0)));
            case "getResource":
                return resourceUrl(vm, resolveResourceName(internalName, ctx.stringArg(0)));
            case "getClassLoader":
                return null; // JDK classes belong to the bootstrap loader, which is reported as null
            case "getProtectionDomain":
                return new ProtectionDomain(null, null);
            case "isInstance":
                return ctx.arg(0) != null && internalName != null && vm.types.isInstance(ctx.arg(0), internalName) ? 1
                        : ctx.arg(0) != null && internalName == null && cls.isInstance(ctx.arg(0)) ? 1 : 0;
            case "isAssignableFrom":
                if (ctx.arg(0) instanceof GuestClass other) {
                    Class<?> hostSuper = vm.host.findClass(other.hostSuperName());
                    boolean assignable = cls == Object.class || (internalName != null && other.isSubtypeOf(internalName))
                            || (hostSuper != null && cls.isAssignableFrom(hostSuper));
                    return assignable ? 1 : 0;
                }
                return ctx.proceed();
            case "cast":
                if (ctx.arg(0) != null && internalName != null && !vm.types.isInstance(ctx.arg(0), internalName)) {
                    throw ctx.raise(new ClassCastException("Cannot cast " + vm.types.nameOf(ctx.arg(0)) + " to " + cls.getName()));
                }
                return ctx.arg(0);
            default:
                break;
        }
        if (HARMLESS_CLASS_METHODS.contains(ctx.name)) {
            if (ctx.name.startsWith("get") && ctx.argCount() > 0 && ctx.arg(0) instanceof String member) {
                vm.events.addOnce(cls.getName() + member, "reflect", "lookup", "class", cls.getName(), "member", member);
            }
            return ctx.proceed();
        }
        return vm.router.blocked(ctx.owner, ctx.name, ctx.desc, "method");
    }

    /** Implements {@code Class.forName}; the names requested are often decrypted at run time, so each is recorded. */
    static Object forName(VirtualMachine vm, String name, boolean initialise) {
        String internalName = name.replace('.', '/');
        Object mirror = internalName.startsWith("[")
                ? vm.types.classMirror(Type.getType(internalName))
                : vm.types.classMirror(Type.getObjectType(internalName));
        vm.events.addOnce("forName:" + name, "reflect", "forName", "name", name, "found", mirror != null);
        if (mirror == null) {
            // Truthful failure matters: samples probe for classes to detect their environment.
            throw vm.raise(new ClassNotFoundException(name));
        }
        if (initialise && mirror instanceof GuestClass guest) {
            vm.ensureInitialised(guest);
        }
        return mirror;
    }

    /** The class's own entry in its InnerClasses attribute, or null for a top-level class. */
    private static org.objectweb.asm.tree.InnerClassNode nestingOf(GuestClass cls) {
        for (org.objectweb.asm.tree.InnerClassNode nested : cls.node.innerClasses) {
            if (nested.name.equals(cls.name)) {
                return nested;
            }
        }
        return null;
    }

    /**
     * Answers a {@code java.lang.Class} method on the mirror of an interpreted class.
     *
     * @param packages the run's {@code Package} objects for guest packages, by package name;
     *                 a real JVM has one per package, so {@code getPackage()} on two classes
     *                 of the same package gives the same object
     */
    private static Object guestClassOperation(VirtualMachine vm, GuestClass cls, CallContext ctx,
            Map<String, SimPackage> packages) throws Exception {
        switch (ctx.name) {
            case "getName", "getTypeName", "getCanonicalName":
                return cls.dottedName();
            case "getSimpleName": {
                org.objectweb.asm.tree.InnerClassNode nested = nestingOf(cls);
                if (nested != null) {
                    return nested.innerName == null ? "" : nested.innerName; // anonymous classes have no simple name
                }
                String dotted = cls.dottedName();
                return dotted.substring(dotted.lastIndexOf('.') + 1);
            }
            case "getPackageName", "getPackage": {
                String dotted = cls.dottedName();
                String packageName = dotted.contains(".") ? dotted.substring(0, dotted.lastIndexOf('.')) : "";
                return ctx.name.equals("getPackage") ? packages.computeIfAbsent(packageName, SimPackage::new) : packageName;
            }
            case "newInstance":
                // Class.newInstance passes the constructor's exceptions on unwrapped.
                return vm.callThroughJdk(CLASS_NEW_INSTANCE_FRAMES, () -> vm.instantiate(cls.name, "()V", new Object[0]));
            case "getMethod", "getDeclaredMethod":
                return findMethod(vm, cls, ctx.stringArg(0), (Object[]) ctx.arg(1), ctx.name.equals("getDeclaredMethod"));
            case "getMethods", "getDeclaredMethods":
                return listMethods(cls, ctx.name.equals("getDeclaredMethods"), false);
            case "getConstructor", "getDeclaredConstructor": {
                GuestMethod constructor = cls.declared("<init>", '(' + parameterDescriptor(vm, (Object[]) ctx.arg(0)) + ")V");
                if (constructor == null) {
                    throw ctx.raise(new NoSuchMethodException(cls.dottedName() + ".<init>"));
                }
                return new ConstructorMirror(constructor);
            }
            case "getConstructors", "getDeclaredConstructors":
                return listMethods(cls, true, true);
            case "getField", "getDeclaredField":
                return findField(vm, cls, ctx.stringArg(0), ctx.name.equals("getDeclaredField"));
            case "getFields", "getDeclaredFields":
                return listFields(cls, ctx.name.equals("getDeclaredFields"));
            case "getResourceAsStream":
                return resourceStream(vm, resolveResourceName(cls.name, ctx.stringArg(0)));
            case "getResource":
                return resourceUrl(vm, resolveResourceName(cls.name, ctx.stringArg(0)));
            case "getClassLoader":
                // A modelled JDK class belongs to the bootstrap loader, reported as null.
                return cls.isAbsentJdkClass() ? null : vm.env.appLoader;
            case "getProtectionDomain":
                return cls.isAbsentJdkClass() ? new ProtectionDomain(null, null) : sampleDomain(vm);
            case "getSuperclass", "getGenericSuperclass":
                if (cls.isInterface()) {
                    return null;
                }
                return cls.superClass() != null ? cls.superClass() : vm.host.findClass(cls.hostSuperName());
            case "getInterfaces", "getGenericInterfaces": {
                List<Object> interfaces = new ArrayList<>();
                for (String iface : cls.node.interfaces) {
                    interfaces.add(vm.types.classMirror(Type.getObjectType(iface)));
                }
                return interfaces.toArray();
            }
            case "isInterface":
                return cls.isInterface() ? 1 : 0;
            case "isEnum":
                return cls.isEnum() ? 1 : 0;
            case "isAnnotation":
                return (cls.node.access & Opcodes.ACC_ANNOTATION) != 0 ? 1 : 0;
            case "isSynthetic":
                return (cls.node.access & Opcodes.ACC_SYNTHETIC) != 0 ? 1 : 0;
            case "isArray", "isPrimitive", "isRecord", "isHidden", "desiredAssertionStatus", "isAnnotationPresent":
                return 0;
            case "isMemberClass": {
                org.objectweb.asm.tree.InnerClassNode nested = nestingOf(cls);
                return nested != null && nested.outerName != null ? 1 : 0;
            }
            case "isAnonymousClass": {
                org.objectweb.asm.tree.InnerClassNode nested = nestingOf(cls);
                return nested != null && nested.innerName == null ? 1 : 0;
            }
            case "isLocalClass": {
                org.objectweb.asm.tree.InnerClassNode nested = nestingOf(cls);
                return nested != null && nested.innerName != null && nested.outerName == null ? 1 : 0;
            }
            case "getModifiers": {
                // For a nested class the real modifiers (static, private, ...) are recorded in
                // the InnerClasses attribute; the class file's own flags cannot express them.
                org.objectweb.asm.tree.InnerClassNode nested = nestingOf(cls);
                return (nested != null ? nested.access : cls.node.access) & (Modifier.classModifiers() | Opcodes.ACC_INTERFACE);
            }
            case "getDeclaringClass", "getEnclosingClass": {
                org.objectweb.asm.tree.InnerClassNode nested = nestingOf(cls);
                String outer = nested != null && nested.outerName != null ? nested.outerName
                        // Local and anonymous classes are enclosed, but not declared, by a class.
                        : ctx.name.equals("getEnclosingClass") ? cls.node.outerClass : null;
                return outer == null ? null : vm.types.classMirror(Type.getObjectType(outer));
            }
            case "getClasses", "getDeclaredClasses": {
                java.util.List<Object> members = new java.util.ArrayList<>();
                for (org.objectweb.asm.tree.InnerClassNode nested : cls.node.innerClasses) {
                    boolean visible = ctx.name.equals("getDeclaredClasses") || (nested.access & Opcodes.ACC_PUBLIC) != 0;
                    if (cls.name.equals(nested.outerName) && visible) {
                        members.add(vm.types.classMirror(Type.getObjectType(nested.name)));
                    }
                }
                return members.toArray();
            }
            case "isInstance":
                return ctx.arg(0) != null && vm.types.isInstance(ctx.arg(0), cls.name) ? 1 : 0;
            case "isAssignableFrom":
                return ctx.arg(0) instanceof GuestClass other && other.isSubtypeOf(cls.name) ? 1 : 0;
            case "cast":
                if (ctx.arg(0) != null && !vm.types.isInstance(ctx.arg(0), cls.name)) {
                    throw ctx.raise(new ClassCastException("Cannot cast " + vm.types.nameOf(ctx.arg(0)) + " to " + cls.dottedName()));
                }
                return ctx.arg(0);
            case "getEnumConstants":
                return cls.isEnum() ? enumConstants(vm, cls) : null;
            case "asSubclass", "getNestHost":
                return cls;
            case "hashCode":
                return System.identityHashCode(cls);
            case "equals":
                return cls == ctx.arg(0) ? 1 : 0;
            case "toString":
                return cls.toString();
            case "getAnnotations", "getDeclaredAnnotations":
                return new Annotation[0];
            case "getComponentType", "componentType", "getAnnotation", "getSigners", "getEnclosingMethod":
                return null;
            default:
                return vm.router.gap("Class." + ctx.name + ctx.desc + " on interpreted class", ctx.desc);
        }
    }

    private static Object[] enumConstants(VirtualMachine vm, GuestClass cls) {
        return (Object[]) vm.router.invokeStatic(cls.name, "values", "()[L" + cls.name + ';', new Object[0]);
    }

    /** Concatenated descriptors of an array of class mirrors; null or empty gives "". */
    private static String parameterDescriptor(VirtualMachine vm, Object[] mirrors) {
        StringBuilder descriptor = new StringBuilder();
        if (mirrors != null) {
            for (Object mirror : mirrors) {
                descriptor.append(vm.types.descriptorOf(mirror));
            }
        }
        return descriptor.toString();
    }

    private static Object findMethod(VirtualMachine vm, GuestClass cls, String name, Object[] parameterTypes, boolean declaredOnly)
            throws NoSuchMethodException {
        String parameters = '(' + parameterDescriptor(vm, parameterTypes) + ')';
        vm.events.addOnce(cls.name + name + parameters, "reflect", "lookup", "class", cls.dottedName(), "member", name + parameters);
        for (GuestClass current = cls; current != null; current = declaredOnly ? null : current.superClass()) {
            for (GuestMethod method : current.declaredMethods()) {
                if (method.name.equals(name) && method.desc.startsWith(parameters)) {
                    return new MethodMirror(method);
                }
            }
        }
        if (!declaredOnly) {
            // Inherited from the JDK, e.g. getMethod("start") on a Thread subclass. Returning the
            // real Method is safe: invoking it goes back through the router.
            Class<?> hostSuper = vm.host.findClass(cls.hostSuperName());
            if (hostSuper != null) {
                for (Method method : hostSuper.getMethods()) {
                    if (method.getName().equals(name) && Type.getMethodDescriptor(method).startsWith(parameters)) {
                        return method;
                    }
                }
            }
        }
        throw new NoSuchMethodException(cls.dottedName() + '.' + name + parameters);
    }

    private static Object[] listMethods(GuestClass cls, boolean declaredOnly, boolean constructors) {
        List<Object> mirrors = new ArrayList<>();
        for (GuestClass current = cls; current != null; current = declaredOnly ? null : current.superClass()) {
            for (GuestMethod method : current.declaredMethods()) {
                boolean visible = declaredOnly || (method.node.access & Opcodes.ACC_PUBLIC) != 0;
                if (method.name.equals("<clinit>") || !visible || method.isConstructor() != constructors) {
                    continue;
                }
                mirrors.add(constructors ? new ConstructorMirror(method) : new MethodMirror(method));
            }
        }
        return mirrors.toArray();
    }

    private static Object findField(VirtualMachine vm, GuestClass cls, String name, boolean declaredOnly) throws NoSuchFieldException {
        vm.events.addOnce(cls.name + '#' + name, "reflect", "lookup", "class", cls.dottedName(), "member", name);
        for (GuestClass current = cls; current != null; current = declaredOnly ? null : current.superClass()) {
            for (FieldNode field : current.node.fields) {
                if (field.name.equals(name)) {
                    return new FieldMirror(current, field);
                }
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object[] listFields(GuestClass cls, boolean declaredOnly) {
        List<Object> mirrors = new ArrayList<>();
        for (GuestClass current = cls; current != null; current = declaredOnly ? null : current.superClass()) {
            for (FieldNode field : current.node.fields) {
                if (declaredOnly || (field.access & Opcodes.ACC_PUBLIC) != 0) {
                    mirrors.add(new FieldMirror(current, field));
                }
            }
        }
        return mirrors.toArray();
    }

    // ------------------------------------------------------------------ resources

    /** {@code Class.getResource} resolves relative names against the class's package. */
    private static String resolveResourceName(String classInternalName, String name) {
        if (name == null) {
            throw VirtualMachine.current().raise(new NullPointerException("resource name"));
        }
        if (name.startsWith("/")) {
            return name.substring(1);
        }
        int slash = classInternalName == null ? -1 : classInternalName.lastIndexOf('/');
        return slash < 0 ? name : classInternalName.substring(0, slash + 1) + name;
    }

    /** Serves a resource from the sample's own archive. Droppers keep their payloads there. */
    static Object resourceStream(VirtualMachine vm, String path) {
        byte[] data = vm.classes.resource(path);
        vm.events.addOnce("resource:" + path, "resource", "read", "name", path,
                "found", data != null, "size", data == null ? null : data.length);
        return data == null ? null : new ByteArrayInputStream(data);
    }

    static Object resourceUrl(VirtualMachine vm, String path) throws java.net.MalformedURLException {
        boolean found = vm.classes.resource(path) != null;
        vm.events.addOnce("resource-url:" + path, "resource", "locate", "name", path, "found", found);
        return found ? new URL("jar:" + sampleUrl(vm) + "!/" + path) : null;
    }

    private static URL sampleUrl(VirtualMachine vm) throws java.net.MalformedURLException {
        return new SimFile(vm.env.files, vm.env.samplePath()).toURI().toURL();
    }

    /** The protection domain samples use to find the path of their own jar. */
    static ProtectionDomain sampleDomain(VirtualMachine vm) throws java.net.MalformedURLException {
        return new ProtectionDomain(new CodeSource(sampleUrl(vm), (Certificate[]) null), null);
    }

    // ------------------------------------------------------------------ Method

    /**
     * Answers a {@code java.lang.reflect.Method} method. {@code invoke} runs through the router
     * as an ordinary call would, so stubs and policy apply, with Java 8's reflection frames on
     * the stack while it runs.
     */
    private static Object methodOperation(VirtualMachine vm, CallContext ctx) {
        if (isAccessCall(ctx.name)) {
            return accessAnswer(ctx.name);
        }
        if (ctx.receiver instanceof Method real) {
            switch (ctx.name) {
                case "invoke": {
                    String owner = Type.getInternalName(real.getDeclaringClass());
                    String desc = Type.getMethodDescriptor(real);
                    if (!Modifier.isStatic(real.getModifiers()) && ctx.arg(0) != null && !vm.types.isInstance(ctx.arg(0), owner)) {
                        // As on a real JVM, which checks the receiver before calling. This is
                        // how URLClassLoader.addURL, invoked by reflection on the application
                        // loader, fails on Java 9 and later, where that loader is no URLClassLoader.
                        throw ctx.raise(new IllegalArgumentException("object is not an instance of declaring class"));
                    }
                    Object[] args = unboxAll((Object[]) ctx.arg(1), Type.getArgumentTypes(desc));
                    vm.events.addOnce("invoke:" + owner + real.getName() + desc, "reflect", "invoke",
                            "method", owner + '.' + real.getName() + desc);
                    return invokeReflectively(vm, VirtualMachine.METHOD_INVOKE_FRAMES, () -> Modifier.isStatic(real.getModifiers())
                            ? vm.router.invokeStatic(owner, real.getName(), desc, args)
                            : vm.router.invokeVirtual(owner, real.getName(), desc, ctx.arg(0), args), Type.getReturnType(desc));
                }
                default:
                    return ctx.proceed();
            }
        }
        GuestMethod method = ((MethodMirror) ctx.receiver).method();
        switch (ctx.name) {
            case "invoke": {
                Object[] args = unboxAll((Object[]) ctx.arg(1), method.argTypes);
                Object target = ctx.arg(0);
                vm.events.addOnce("invoke:" + method.id, "reflect", "invoke", "method", method.id);
                return invokeReflectively(vm, VirtualMachine.METHOD_INVOKE_FRAMES, () -> {
                    if (method.isStatic) {
                        vm.ensureInitialised(method.owner);
                        return vm.router.callGuest(method, null, args);
                    }
                    if (target == null) {
                        throw vm.raise(new NullPointerException("Cannot invoke " + method.id + " on null"));
                    }
                    // Method.invoke dispatches virtually, like an ordinary call would.
                    InstanceState state = vm.stateOf(target);
                    GuestMethod override = state == null ? null : state.type.findVirtual(method.name, method.desc);
                    return vm.router.callGuest(override != null ? override : method, target, args);
                }, method.returnType);
            }
            case "getName":
                return method.name;
            case "getParameterTypes", "getGenericParameterTypes":
                return mirrorsOf(vm, method.argTypes);
            case "getParameterCount":
                return method.argTypes.length;
            case "getReturnType", "getGenericReturnType":
                return vm.types.classMirror(method.returnType);
            case "getModifiers":
                return method.node.access & Modifier.methodModifiers();
            case "getDeclaringClass":
                return method.owner;
            default:
                return memberOperation(vm, ctx, method.node.access, method.id);
        }
    }

    /**
     * Runs a reflective call, applying {@code Method.invoke}'s result boxing and exception
     * wrapping. Stack traces taken during the call show the JDK frames that reflection adds on
     * a real Java 8, which stack-walking string decryptors count on.
     *
     * @param jdkFrames {@link VirtualMachine#METHOD_INVOKE_FRAMES} or
     *                  {@link VirtualMachine#CONSTRUCTOR_NEW_INSTANCE_FRAMES}
     */
    private static Object invokeReflectively(VirtualMachine vm, List<StackTraceElement> jdkFrames,
            java.util.function.Supplier<Object> call, Type returnType) {
        try {
            return Values.box(vm.callThroughJdk(jdkFrames, call), returnType);
        } catch (GuestThrow thrown) {
            // Reflection reports the callee's exception wrapped, and samples catch exactly that.
            throw vm.raise(new InvocationTargetException(thrown.asThrowable()));
        }
    }

    private static Object[] unboxAll(Object[] boxed, Type[] types) {
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length && boxed != null && i < boxed.length; i++) {
            args[i] = Values.unbox(boxed[i], types[i]);
        }
        return args;
    }

    private static Object[] mirrorsOf(VirtualMachine vm, Type[] types) {
        Object[] mirrors = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            mirrors[i] = vm.types.classMirror(types[i]);
        }
        return mirrors;
    }

    /** Operations shared by method, field and constructor mirrors. */
    private static Object memberOperation(VirtualMachine vm, CallContext ctx, int access, String description) {
        switch (ctx.name) {
            case "isSynthetic":
                return (access & Opcodes.ACC_SYNTHETIC) != 0 ? 1 : 0;
            case "isBridge":
                return (access & Opcodes.ACC_BRIDGE) != 0 ? 1 : 0;
            case "isVarArgs":
                return (access & Opcodes.ACC_VARARGS) != 0 ? 1 : 0;
            case "isDefault", "isAnnotationPresent", "isEnumConstant":
                return 0;
            case "getAnnotation":
                return null;
            case "getAnnotations", "getDeclaredAnnotations":
                return new Annotation[0];
            case "getExceptionTypes", "getGenericExceptionTypes":
                return new Object[0];
            case "hashCode":
                return System.identityHashCode(ctx.receiver);
            case "equals":
                return ctx.receiver.equals(ctx.arg(0)) ? 1 : 0;
            case "toString", "toGenericString":
                return description;
            default:
                return vm.router.gap("reflection: " + ctx.name + ctx.desc + " on interpreted member", ctx.desc);
        }
    }

    // ------------------------------------------------------------------ Field

    private static Object fieldOperation(VirtualMachine vm, CallContext ctx) {
        if (isAccessCall(ctx.name)) {
            return accessAnswer(ctx.name);
        }
        boolean isGetter = ctx.name.startsWith("get") && ctx.argCount() == 1 && ctx.desc.startsWith("(Ljava/lang/Object;)");
        boolean isSetter = ctx.name.startsWith("set") && ctx.argCount() == 2 && ctx.desc.startsWith("(Ljava/lang/Object;");
        // get(Object) and set(Object, Object) deal in boxed values; getInt and friends do not.
        boolean boxed = ctx.name.equals("get") || ctx.name.equals("set");

        if (ctx.receiver instanceof Field real) {
            String owner = Type.getInternalName(real.getDeclaringClass());
            Type type = Type.getType(real.getType());
            if ((isGetter || isSetter) && !Modifier.isPublic(real.getModifiers())) {
                // Reading JDK internals would need setAccessible, which is never honoured.
                return vm.router.blocked(owner, real.getName(), "()" + type.getDescriptor(), "field");
            }
            if (isGetter) {
                Object value = Modifier.isStatic(real.getModifiers())
                        ? vm.fields.getHostStatic(owner, real.getName(), type.getDescriptor())
                        : vm.host.getField(ctx.arg(0), owner, real.getName());
                return boxed ? Values.box(value, type) : value;
            }
            if (isSetter) {
                Object value = boxed ? Values.unbox(ctx.arg(1), type) : ctx.arg(1);
                if (Modifier.isStatic(real.getModifiers())) {
                    vm.fields.putHostStatic(owner, real.getName(), type.getDescriptor(), value);
                } else {
                    vm.host.putField(ctx.arg(0), owner, real.getName(), value);
                }
                return null;
            }
            return ctx.proceed();
        }

        FieldMirror mirror = (FieldMirror) ctx.receiver;
        FieldNode field = mirror.field();
        Type type = Type.getType(field.desc);
        FieldRef ref = mirror.declaring().declaredField(field.name, field.desc);
        if (isGetter || isSetter) {
            Object[] storage;
            if (ref.isStatic()) {
                vm.ensureInitialised(ref.declaring());
                storage = ref.declaring().statics();
            } else {
                InstanceState state = vm.stateOf(ctx.arg(0));
                if (state == null) {
                    throw ctx.raise(ctx.arg(0) == null ? new NullPointerException("field " + field.name + " on null")
                            : new IllegalArgumentException("object is not an instance of " + mirror.declaring().dottedName()));
                }
                storage = state.fields;
            }
            if (isGetter) {
                return boxed ? Values.box(storage[ref.slot()], type) : storage[ref.slot()];
            }
            storage[ref.slot()] = boxed ? Values.unbox(ctx.arg(1), type) : ctx.arg(1);
            return null;
        }
        switch (ctx.name) {
            case "getName":
                return field.name;
            case "getType", "getGenericType":
                return vm.types.classMirror(type);
            case "getModifiers":
                return field.access & Modifier.fieldModifiers();
            case "getDeclaringClass":
                return mirror.declaring();
            default:
                return memberOperation(vm, ctx, field.access, mirror.toString());
        }
    }

    // ------------------------------------------------------------------ Constructor

    /**
     * Answers a {@code java.lang.reflect.Constructor} method. {@code newInstance} constructs
     * through the VM, so stubs and policy apply, with Java 8's reflection frames on the stack
     * while the constructor runs.
     */
    private static Object constructorOperation(VirtualMachine vm, CallContext ctx) {
        if (isAccessCall(ctx.name)) {
            return accessAnswer(ctx.name);
        }
        if (ctx.receiver instanceof Constructor<?> real) {
            if (ctx.name.equals("newInstance")) {
                String owner = Type.getInternalName(real.getDeclaringClass());
                String desc = Type.getConstructorDescriptor(real);
                Object[] args = unboxAll((Object[]) ctx.arg(0), Type.getArgumentTypes(desc));
                vm.events.addOnce("new:" + owner + desc, "reflect", "newInstance", "class", owner, "descriptor", desc);
                // Going through instantiate() means stubs and policy apply to reflective construction.
                return invokeReflectively(vm, VirtualMachine.CONSTRUCTOR_NEW_INSTANCE_FRAMES,
                        () -> vm.instantiate(owner, desc, args), Type.getObjectType(owner));
            }
            return ctx.proceed();
        }
        GuestMethod constructor = ((ConstructorMirror) ctx.receiver).method();
        switch (ctx.name) {
            case "newInstance": {
                Object[] args = unboxAll((Object[]) ctx.arg(0), constructor.argTypes);
                vm.events.addOnce("new:" + constructor.id, "reflect", "newInstance", "class", constructor.owner.dottedName());
                return invokeReflectively(vm, VirtualMachine.CONSTRUCTOR_NEW_INSTANCE_FRAMES,
                        () -> vm.instantiate(constructor.owner.name, constructor.desc, args),
                        Type.getObjectType(constructor.owner.name));
            }
            case "getName":
                return constructor.owner.dottedName();
            case "getParameterTypes", "getGenericParameterTypes":
                return mirrorsOf(vm, constructor.argTypes);
            case "getParameterCount":
                return constructor.argTypes.length;
            case "getModifiers":
                return constructor.node.access & Modifier.constructorModifiers();
            case "getDeclaringClass":
                return constructor.owner;
            default:
                return memberOperation(vm, ctx, constructor.node.access, constructor.id);
        }
    }
}

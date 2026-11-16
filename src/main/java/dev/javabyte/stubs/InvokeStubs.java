package dev.javabyte.stubs;

import dev.javabyte.host.CallContext;
import dev.javabyte.host.StubRegistry;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestMethod;
import dev.javabyte.model.Mirrors.ConstructorMirror;
import dev.javabyte.model.Mirrors.FieldMirror;
import dev.javabyte.model.Mirrors.LookupMirror;
import dev.javabyte.model.Mirrors.MethodMirror;
import dev.javabyte.vm.EmulationAbort;
import dev.javabyte.vm.GuestThrow;
import dev.javabyte.vm.VirtualMachine;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * {@code java.lang.invoke}: lookups, method handles and method types.
 *
 * <p>Guest code never receives a handle that points straight at a JDK method. Every lookup
 * produces a handle built by {@link dev.javabyte.vm.IndyLinker#handleFor}, whose invocation
 * re-enters the router. Combinators such as {@code bindTo} or {@code asType} run for real on
 * top of those, which is safe because the leaves are always the emulator's own.
 */
final class InvokeStubs {

    private InvokeStubs() {}

    static void install(VirtualMachine vm) {
        StubRegistry stubs = vm.stubs;
        String handles = "java/lang/invoke/MethodHandles";
        stubs.on(handles, "lookup", ctx -> new LookupMirror(vm.currentMethod().owner.name));
        stubs.on(handles, "publicLookup", ctx -> new LookupMirror("java/lang/Object"));
        stubs.on(handles, "privateLookupIn", ctx -> new LookupMirror(vm.types.internalNameOf(ctx.arg(0))));
        stubs.onClass("java/lang/invoke/MethodHandles$Lookup", ctx -> lookupOperation(vm, ctx));

        // invoke and invokeExact are signature-polymorphic: the arguments arrive exactly as the
        // call site pushed them.
        stubs.on("java/lang/invoke/MethodHandle", "invoke", ctx -> vm.indy.invokeMethodHandle((MethodHandle) ctx.receiver, ctx.args));
        stubs.on("java/lang/invoke/MethodHandle", "invokeExact", ctx -> vm.indy.invokeMethodHandle((MethodHandle) ctx.receiver, ctx.args));
        stubs.on("java/lang/invoke/MethodHandle", "invokeWithArguments", ctx -> {
            Object[] boxed = ctx.arg(0) instanceof List<?> list ? list.toArray() : (Object[]) ctx.arg(0);
            try {
                return ((MethodHandle) ctx.receiver).invokeWithArguments(boxed);
            } catch (GuestThrow | EmulationAbort passThrough) {
                throw passThrough;
            } catch (Throwable thrown) {
                throw vm.raiseKeepingTrace(thrown);
            }
        });

        stubs.on("java/lang/invoke/MethodType", "methodType", ctx -> {
            List<Class<?>> parameters = new ArrayList<>();
            for (int i = 1; i < ctx.argCount(); i++) {
                Object arg = ctx.arg(i);
                if (arg instanceof Object[] many) {
                    for (Object each : many) {
                        parameters.add(vm.types.erase(each));
                    }
                } else if (arg instanceof MethodType type) {
                    parameters.addAll(type.parameterList());
                } else if (arg instanceof List<?> list) {
                    list.forEach(each -> parameters.add(vm.types.erase(each)));
                } else {
                    parameters.add(vm.types.erase(arg));
                }
            }
            return MethodType.methodType(vm.types.erase(ctx.arg(0)), parameters);
        });
        stubs.on("java/lang/invoke/MethodType", "fromMethodDescriptorString", ctx -> vm.indy.methodType(ctx.stringArg(0)));
    }

    private static Object lookupOperation(VirtualMachine vm, CallContext ctx) throws Exception {
        LookupMirror lookup = ctx.receiver instanceof LookupMirror mirror ? mirror : new LookupMirror("java/lang/Object");
        switch (ctx.name) {
            case "findStatic":
                return methodHandle(vm, Opcodes.H_INVOKESTATIC, ctx.arg(0), ctx.stringArg(1), (MethodType) ctx.arg(2));
            case "findVirtual":
                return methodHandle(vm, Opcodes.H_INVOKEVIRTUAL, ctx.arg(0), ctx.stringArg(1), (MethodType) ctx.arg(2));
            case "findSpecial":
                return methodHandle(vm, Opcodes.H_INVOKESPECIAL, ctx.arg(0), ctx.stringArg(1), (MethodType) ctx.arg(2));
            case "findConstructor":
                return methodHandle(vm, Opcodes.H_NEWINVOKESPECIAL, ctx.arg(0), "<init>", (MethodType) ctx.arg(1));
            case "findGetter":
                return fieldHandle(vm, Opcodes.H_GETFIELD, ctx.arg(0), ctx.stringArg(1), ctx.arg(2));
            case "findStaticGetter":
                return fieldHandle(vm, Opcodes.H_GETSTATIC, ctx.arg(0), ctx.stringArg(1), ctx.arg(2));
            case "findSetter":
                return fieldHandle(vm, Opcodes.H_PUTFIELD, ctx.arg(0), ctx.stringArg(1), ctx.arg(2));
            case "findStaticSetter":
                return fieldHandle(vm, Opcodes.H_PUTSTATIC, ctx.arg(0), ctx.stringArg(1), ctx.arg(2));
            case "unreflect", "unreflectSpecial":
                return unreflect(vm, ctx.arg(0));
            case "unreflectConstructor":
                return unreflect(vm, ctx.arg(0));
            case "unreflectGetter":
                return unreflectField(vm, ctx.arg(0), false);
            case "unreflectSetter":
                return unreflectField(vm, ctx.arg(0), true);
            case "lookupClass":
                return vm.types.classMirror(Type.getObjectType(lookup.lookupClass()));
            case "in":
                return new LookupMirror(vm.types.internalNameOf(ctx.arg(0)));
            case "dropLookupMode":
                return lookup;
            case "findClass":
                return ReflectionStubs.forName(vm, ctx.stringArg(0), false);
            case "defineClass":
                return vm.defineClass(null, (byte[]) ctx.arg(0), "Lookup.defineClass");
            case "defineHiddenClass", "defineHiddenClassWithClassData":
                return new LookupMirror(vm.defineClass(null, (byte[]) ctx.arg(0), "Lookup.defineHiddenClass").name);
            case "lookupModes":
                return 31;
            case "hasPrivateAccess", "hasFullPrivilegeAccess":
                return 1;
            case "hashCode":
                return System.identityHashCode(lookup);
            case "equals":
                return lookup == ctx.arg(0) ? 1 : 0;
            case "toString":
                return lookup.lookupClass().replace('/', '.');
            default:
                return vm.router.gap("MethodHandles.Lookup." + ctx.name + ctx.desc, ctx.desc);
        }
    }

    /**
     * Builds a handle for a method named at run time.
     *
     * <p>A {@link MethodType} cannot name interpreted classes (they are erased to Object), so a
     * guest method is found by name and erased signature. Obfuscators that resolve call targets
     * this way pass names they have just decrypted, hence the event.
     */
    private static Object methodHandle(VirtualMachine vm, int kind, Object classMirror, String name, MethodType type)
            throws NoSuchMethodException {
        String owner = vm.types.internalNameOf(classMirror);
        String desc = type.toMethodDescriptorString();
        if (kind == Opcodes.H_NEWINVOKESPECIAL) {
            desc = desc.substring(0, desc.indexOf(')') + 1) + 'V';
        }
        if (classMirror instanceof GuestClass guest) {
            String wanted = desc;
            desc = null;
            search:
            for (GuestClass current = guest; current != null; current = current.superClass()) {
                for (GuestMethod method : current.declaredMethods()) {
                    boolean staticMatches = method.isStatic == (kind == Opcodes.H_INVOKESTATIC);
                    if (method.name.equals(name) && staticMatches && sameErasure(vm, method.desc, wanted, kind)) {
                        desc = method.desc;
                        break search;
                    }
                }
            }
            if (desc == null) {
                // Not declared by interpreted code: it may be inherited from the JDK superclass.
                desc = wanted;
                if (vm.host.findClass(guest.hostSuperName()) == null) {
                    throw new NoSuchMethodException(guest.dottedName() + '.' + name + wanted);
                }
            }
        }
        vm.events.addOnce("mh:" + owner + name + desc, "reflect", "method-handle", "target", owner + '.' + name + desc);
        return vm.indy.handleFor(new Handle(kind, owner, name, desc, false));
    }

    private static boolean sameErasure(VirtualMachine vm, String methodDesc, String wanted, int kind) {
        String erased = vm.indy.erasedDescriptor(methodDesc);
        if (kind == Opcodes.H_NEWINVOKESPECIAL) {
            return erased.substring(0, erased.indexOf(')') + 1).equals(wanted.substring(0, wanted.indexOf(')') + 1));
        }
        return erased.equals(wanted);
    }

    private static Object fieldHandle(VirtualMachine vm, int kind, Object classMirror, String name, Object typeMirror) {
        String owner = vm.types.internalNameOf(classMirror);
        return vm.indy.handleFor(new Handle(kind, owner, name, vm.types.descriptorOf(typeMirror), false));
    }

    private static Object unreflect(VirtualMachine vm, Object member) {
        if (member instanceof MethodMirror mirror) {
            GuestMethod method = mirror.method();
            return vm.indy.handleFor(new Handle(method.isStatic ? Opcodes.H_INVOKESTATIC : Opcodes.H_INVOKEVIRTUAL,
                    method.owner.name, method.name, method.desc, false));
        }
        if (member instanceof ConstructorMirror mirror) {
            return vm.indy.handleFor(new Handle(Opcodes.H_NEWINVOKESPECIAL, mirror.method().owner.name, "<init>",
                    mirror.method().desc, false));
        }
        if (member instanceof Constructor<?> constructor) {
            return vm.indy.handleFor(new Handle(Opcodes.H_NEWINVOKESPECIAL,
                    Type.getInternalName(constructor.getDeclaringClass()), "<init>", Type.getConstructorDescriptor(constructor), false));
        }
        Method method = (Method) member;
        return vm.indy.handleFor(new Handle(Modifier.isStatic(method.getModifiers()) ? Opcodes.H_INVOKESTATIC : Opcodes.H_INVOKEVIRTUAL,
                Type.getInternalName(method.getDeclaringClass()), method.getName(), Type.getMethodDescriptor(method), false));
    }

    private static Object unreflectField(VirtualMachine vm, Object member, boolean setter) {
        String owner;
        String name;
        String desc;
        boolean isStatic;
        if (member instanceof FieldMirror mirror) {
            owner = mirror.declaring().name;
            name = mirror.field().name;
            desc = mirror.field().desc;
            isStatic = (mirror.field().access & Opcodes.ACC_STATIC) != 0;
        } else {
            Field field = (Field) member;
            owner = Type.getInternalName(field.getDeclaringClass());
            name = field.getName();
            desc = Type.getDescriptor(field.getType());
            isStatic = Modifier.isStatic(field.getModifiers());
        }
        int kind = setter ? (isStatic ? Opcodes.H_PUTSTATIC : Opcodes.H_PUTFIELD) : (isStatic ? Opcodes.H_GETSTATIC : Opcodes.H_GETFIELD);
        return vm.indy.handleFor(new Handle(kind, owner, name, desc, false));
    }
}

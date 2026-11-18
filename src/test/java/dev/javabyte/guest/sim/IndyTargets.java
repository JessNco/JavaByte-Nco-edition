package dev.javabyte.guest.sim;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

/**
 * Bootstrap methods and hidden targets for call sites that the tests generate with ASM.
 * Together they imitate obfuscators that replace every call with {@code invokedynamic} and
 * resolve the real target at run time from an encrypted name.
 *
 * <p>Unlike the rest of this package this class is harmless and is also run on the real JVM,
 * so the emulator's results can be compared with the genuine ones.
 */
public final class IndyTargets {

    private IndyTargets() {}

    private static String decrypt(String name) {
        char[] chars = name.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] ^= 3;
        }
        return new String(chars);
    }

    /** For tests: produces the encrypted form of a method name. */
    public static String encrypt(String name) {
        return decrypt(name); // XOR is its own inverse
    }

    /** Resolves a static method of this class whose name is passed encrypted. */
    public static CallSite hiddenStatic(MethodHandles.Lookup lookup, String ignored, MethodType type, String encryptedName)
            throws ReflectiveOperationException {
        return new ConstantCallSite(lookup.findStatic(IndyTargets.class, decrypt(encryptedName), type));
    }

    /** Resolves a virtual method on the first argument's declared type. */
    public static CallSite hiddenVirtual(MethodHandles.Lookup lookup, String ignored, MethodType type, String encryptedName)
            throws ReflectiveOperationException {
        MethodHandle target = lookup.findVirtual(type.parameterType(0), decrypt(encryptedName), type.dropParameterTypes(0, 1));
        return new ConstantCallSite(target);
    }

    /** A call site whose target is swapped after linking; callers must see the new target. */
    public static CallSite retargeted(MethodHandles.Lookup lookup, String ignored, MethodType type)
            throws ReflectiveOperationException {
        MutableCallSite site = new MutableCallSite(lookup.findStatic(IndyTargets.class, "first", type));
        site.setTarget(lookup.findStatic(IndyTargets.class, "second", type));
        return site;
    }

    /**
     * A varargs bootstrap for a dynamic constant, as obfuscators use to rebuild strings: the
     * JVM collects any number of static arguments into {@code parts}.
     */
    public static String joined(MethodHandles.Lookup lookup, String name, Class<?> type, Object... parts) {
        StringBuilder out = new StringBuilder(name);
        for (Object part : parts) {
            out.append(':').append(part);
        }
        return out.toString();
    }

    /** A varargs invokedynamic bootstrap: links to the static method named by the last argument. */
    public static CallSite lastNamed(MethodHandles.Lookup lookup, String ignored, MethodType type, String... names)
            throws ReflectiveOperationException {
        return new ConstantCallSite(lookup.findStatic(IndyTargets.class, names[names.length - 1], type));
    }

    public static int add(int a, int b) {
        return a + b;
    }

    public static String shout(String text, long times) {
        return (text.toUpperCase() + "!").repeat((int) times);
    }

    public static String first() {
        return "first";
    }

    public static String second() {
        return "second";
    }
}

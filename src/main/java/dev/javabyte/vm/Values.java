package dev.javabyte.vm;

import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestInstance;
import dev.javabyte.model.PendingInstance;
import dev.javabyte.model.PlainObject;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.objectweb.asm.Type;

/**
 * Conversions between the interpreter's value representation and the host JVM's.
 *
 * <p>Inside the interpreter every {@code boolean}, {@code byte}, {@code char}, {@code short} and
 * {@code int} is an {@link Integer}, exactly as the JVM treats them all as {@code int} on its
 * operand stack. Real JDK methods are called through reflection, which insists on the precise
 * primitive type, so values are narrowed on the way out and widened on the way back.
 */
public final class Values {

    private Values() {}

    /** Converts an interpreter value into what reflection expects for a parameter of {@code target} type. */
    public static Object toHost(Object value, Class<?> target) {
        if (value instanceof PendingInstance pending && pending.resolved != null) {
            value = pending.resolved;
        }
        if (target.isPrimitive()) {
            if (value instanceof Integer i) {
                if (target == boolean.class) {
                    return i != 0;
                }
                if (target == char.class) {
                    return (char) i.intValue();
                }
                if (target == byte.class) {
                    return (byte) i.intValue();
                }
                if (target == short.class) {
                    return (short) i.intValue();
                }
            }
            return value;
        }
        if (value instanceof Object[] array && target.isArray() && !target.isInstance(value)) {
            // Arrays of guest types are plain Object[]; a JDK method declared with, say,
            // Runnable[] needs a correctly typed copy.
            try {
                Object typed = Array.newInstance(target.getComponentType(), array.length);
                System.arraycopy(array, 0, typed, 0, array.length);
                return typed;
            } catch (ArrayStoreException e) {
                // An element has no real counterpart (a class mirror, for example). Pass the
                // array through; the call then fails cleanly and is reported as a gap.
                return value;
            }
        }
        return value;
    }

    /** Converts a reflective result of declared type {@code declared} into the interpreter representation. */
    public static Object fromHost(Object value, Class<?> declared) {
        if (!declared.isPrimitive() || value == null) {
            // Boxed objects such as Boolean stay as they are: to the guest they are references.
            return value;
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof Character c) {
            return (int) c;
        }
        if (value instanceof Byte || value instanceof Short) {
            return ((Number) value).intValue();
        }
        return value;
    }

    /**
     * Converts a boxed value (as passed to {@code Method.invoke} or a proxy) into the interpreter
     * representation for a parameter of the given JVM type.
     */
    public static Object unbox(Object boxed, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                return boxed instanceof Boolean b ? (Object) (b ? 1 : 0) : boxed;
            case Type.CHAR:
                return boxed instanceof Character c ? (Object) (int) c : boxed;
            case Type.BYTE, Type.SHORT, Type.INT:
                return boxed instanceof Number n ? (Object) n.intValue() : boxed;
            case Type.LONG:
                return boxed instanceof Number n ? (Object) n.longValue() : boxed;
            case Type.FLOAT:
                return boxed instanceof Number n ? (Object) n.floatValue() : boxed;
            case Type.DOUBLE:
                return boxed instanceof Number n ? (Object) n.doubleValue() : boxed;
            default:
                return boxed;
        }
    }

    /** The reverse of {@link #unbox}: boxes an interpreter value as reflection would return it. */
    public static Object box(Object value, Type type) {
        if (!(value instanceof Integer i)) {
            return type.getSort() == Type.VOID ? null : value;
        }
        return switch (type.getSort()) {
            case Type.BOOLEAN -> i != 0;
            case Type.CHAR -> (char) i.intValue();
            case Type.BYTE -> (byte) i.intValue();
            case Type.SHORT -> (short) i.intValue();
            default -> value;
        };
    }

    /** The JVM zero value for a type; null for references and void. */
    public static Object zero(Type type) {
        return type.getSort() == Type.VOID ? null : GuestClass.defaultValue(type.getDescriptor());
    }

    /** True for values that take two operand-stack slots in the class-file model. */
    static boolean isWide(Object value) {
        return value instanceof Long || value instanceof Double;
    }

    /**
     * Short, safe rendering of any value for logs and reports.
     *
     * <p>It must never call {@code toString()} on a guest object, because that would run guest
     * code as a side effect of logging.
     */
    public static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return '"' + truncate(s, 200) + '"';
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value.toString();
        }
        if (value instanceof PlainObject plain) {
            return plain.identityString();
        }
        if (value instanceof GuestInstance guest) {
            return guest.javabyte$state().type.dottedName() + '@'
                    + Integer.toHexString(System.identityHashCode(value));
        }
        if (value instanceof byte[] bytes) {
            return "byte[" + bytes.length + "]{" + HexFormat.of().formatHex(bytes, 0, Math.min(bytes.length, 24))
                    + (bytes.length > 24 ? "..." : "") + '}';
        }
        if (value instanceof char[] chars) {
            return "char[" + chars.length + "]\"" + truncate(new String(chars), 80) + '"';
        }
        if (value.getClass().isArray()) {
            return value.getClass().getComponentType().getSimpleName() + '[' + Array.getLength(value) + ']';
        }
        if (value instanceof GuestClass || value instanceof Class<?> || value instanceof PendingInstance) {
            return value.toString();
        }
        return value.getClass().getName() + '@' + Integer.toHexString(System.identityHashCode(value));
    }

    public static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...(" + text.length() + " chars)";
    }

    /** Renders bytes as text when they are printable, otherwise as hex. Used for payload previews. */
    public static String preview(byte[] data, int max) {
        int length = Math.min(data.length, max);
        boolean printable = true;
        for (int i = 0; i < length && printable; i++) {
            int b = data[i] & 0xFF;
            printable = b == '\n' || b == '\r' || b == '\t' || (b >= 0x20 && b < 0x7F);
        }
        String body = printable
                ? new String(data, 0, length, StandardCharsets.US_ASCII)
                : "hex:" + HexFormat.of().formatHex(data, 0, length);
        return data.length > max ? body + "...(" + data.length + " bytes)" : body;
    }
}

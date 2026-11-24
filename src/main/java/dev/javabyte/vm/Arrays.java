package dev.javabyte.vm;

import java.lang.reflect.Array;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Array instructions.
 *
 * <p>Guest arrays are real Java arrays, so they can be passed straight to JDK methods such as
 * {@code System.arraycopy} or {@code Cipher.doFinal} with no copying. Arrays whose element type
 * is a guest class are {@code Object[]}, because no host class exists for the element type.
 *
 * <p>Bounds and null checks are explicit rather than relying on the host JVM's own exceptions.
 * An ArrayIndexOutOfBoundsException caught generically could equally come from an emulator bug,
 * and that must never be presented to the sample as its own exception.
 */
final class Arrays {

    private Arrays() {}

    /**
     * The {@code xALOAD} instructions: an element in interpreter form (int-like values as {@code
     * Integer}).
     */
    static Object load(VirtualMachine vm, Object array, int index) {
        checkAccess(vm, array, index);
        if (array instanceof byte[] bytes) {
            return (int) bytes[index];
        }
        if (array instanceof int[] ints) {
            return ints[index];
        }
        if (array instanceof Object[] objects) {
            return objects[index];
        }
        if (array instanceof char[] chars) {
            return (int) chars[index];
        }
        if (array instanceof long[] longs) {
            return longs[index];
        }
        if (array instanceof boolean[] booleans) {
            return booleans[index] ? 1 : 0;
        }
        if (array instanceof short[] shorts) {
            return (int) shorts[index];
        }
        if (array instanceof float[] floats) {
            return floats[index];
        }
        return ((double[]) array)[index];
    }

    /**
     * The {@code xASTORE} instructions: narrows an interpreter value to the array's element type
     * and stores it.
     */
    static void store(VirtualMachine vm, Object array, int index, Object value) {
        checkAccess(vm, array, index);
        if (array instanceof byte[] bytes) {
            bytes[index] = (byte) (int) (Integer) value;
        } else if (array instanceof int[] ints) {
            ints[index] = (Integer) value;
        } else if (array instanceof Object[] objects) {
            try {
                objects[index] = value;
            } catch (ArrayStoreException e) {
                throw vm.raise(new ArrayStoreException(vm.types.nameOf(value)));
            }
        } else if (array instanceof char[] chars) {
            chars[index] = (char) (int) (Integer) value;
        } else if (array instanceof long[] longs) {
            longs[index] = (Long) value;
        } else if (array instanceof boolean[] booleans) {
            // The JVM stores only the low bit of the int into a boolean array.
            booleans[index] = ((Integer) value & 1) != 0;
        } else if (array instanceof short[] shorts) {
            shorts[index] = (short) (int) (Integer) value;
        } else if (array instanceof float[] floats) {
            floats[index] = (Float) value;
        } else {
            ((double[]) array)[index] = (Double) value;
        }
    }

    /** {@code ARRAYLENGTH}, with the guest's {@code NullPointerException} for null. */
    static int length(VirtualMachine vm, Object array) {
        if (array == null) {
            throw vm.implicitExceptions.nullPointer("Cannot read the array length because value is null");
        }
        return Array.getLength(array);
    }

    private static void checkAccess(VirtualMachine vm, Object array, int index) {
        if (array == null) {
            throw vm.implicitExceptions.nullPointer("Cannot access array because value is null");
        }
        int length = Array.getLength(array);
        if (index < 0 || index >= length) {
            throw vm.raise(new ArrayIndexOutOfBoundsException(
                    "Index " + index + " out of bounds for length " + length));
        }
    }

    /** {@code NEWARRAY}: a real primitive array for the JVM's type code. */
    static Object newPrimitive(VirtualMachine vm, int typeCode, int length) {
        checkLength(vm, length);
        switch (typeCode) {
            case Opcodes.T_BOOLEAN: return new boolean[length];
            case Opcodes.T_CHAR: return new char[length];
            case Opcodes.T_FLOAT: return new float[length];
            case Opcodes.T_DOUBLE: return new double[length];
            case Opcodes.T_BYTE: return new byte[length];
            case Opcodes.T_SHORT: return new short[length];
            case Opcodes.T_INT: return new int[length];
            case Opcodes.T_LONG: return new long[length];
            default: throw new IllegalStateException("Bad newarray type " + typeCode);
        }
    }

    /** @param elementType internal class name, or an array descriptor for nested arrays */
    static Object newReference(VirtualMachine vm, String elementType, int length) {
        checkLength(vm, length);
        return Array.newInstance(vm.types.hostClassOrObject(elementType), length);
    }

    /** {@code MULTIANEWARRAY}: nested arrays for the leading dimensions that were given sizes. */
    static Object newMulti(VirtualMachine vm, String desc, int[] dimensions) {
        for (int dimension : dimensions) {
            checkLength(vm, dimension);
        }
        // Only the first dimensions.length levels are allocated; the element type of the
        // innermost allocated arrays is whatever remains of the descriptor.
        Type remaining = Type.getType(desc.substring(dimensions.length));
        Class<?> element = remaining.getSort() == Type.OBJECT || remaining.getSort() == Type.ARRAY
                ? vm.types.hostClassOrObject(remaining.getSort() == Type.OBJECT
                        ? remaining.getInternalName() : remaining.getDescriptor())
                : vm.types.primitiveClass(remaining);
        return Array.newInstance(element, dimensions);
    }

    private static void checkLength(VirtualMachine vm, int length) {
        if (length < 0) {
            throw vm.raise(new NegativeArraySizeException(String.valueOf(length)));
        }
    }
}

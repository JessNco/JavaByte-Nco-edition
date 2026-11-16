package dev.javabyte.vm;

import org.objectweb.asm.Type;

/**
 * A method reference from an invoke instruction, parsed once and cached on the instruction.
 *
 * @param argCount number of declared arguments, excluding the receiver
 */
record CallSite(String owner, String name, String desc, int argCount, boolean returnsVoid, boolean isConstructor) {

    static CallSite of(String owner, String name, String desc) {
        return new CallSite(owner, name, desc, Type.getArgumentTypes(desc).length,
                Type.getReturnType(desc).getSort() == Type.VOID, "<init>".equals(name));
    }
}

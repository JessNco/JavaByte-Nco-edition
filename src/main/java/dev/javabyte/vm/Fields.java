package dev.javabyte.vm;

import dev.javabyte.model.FieldRef;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.InstanceState;
import dev.javabyte.model.PhantomObject;
import java.util.function.Supplier;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;

/** Field reads and writes, for both interpreted fields and fields of real JDK objects. */
public final class Fields {

    private final VirtualMachine vm;

    Fields(VirtualMachine vm) {
        this.vm = vm;
    }

    public Object getStatic(FieldRef ref) {
        vm.ensureInitialised(ref.declaring());
        return ref.declaring().statics()[ref.slot()];
    }

    public void putStatic(FieldRef ref, Object value) {
        vm.ensureInitialised(ref.declaring());
        ref.declaring().statics()[ref.slot()] = value;
    }

    /** Reads a static field that no interpreted class declares. */
    public Object getHostStatic(String owner, String name, String desc) {
        String hostOwner = hostOwner(owner);
        Supplier<Object> stubbed = vm.stubs.staticField(hostOwner, name);
        if (stubbed != null) {
            return stubbed.get();
        }
        if (vm.host.findClass(hostOwner) != null && vm.policy.allows(hostOwner, name, null)) {
            return vm.host.getStatic(hostOwner, name);
        }
        return vm.router.blocked(hostOwner, name, "()" + desc, "field");
    }

    public void putHostStatic(String owner, String name, String desc, Object value) {
        // Writing a JDK static would change the analysis JVM itself, so it is never performed.
        vm.events.addOnce(owner + name, "policy", "blocked-field-write", "field", hostOwner(owner) + '.' + name,
                "value", Values.describe(value));
    }

    /**
     * Reads an instance field.
     *
     * @param ref  the resolved guest field, or null if the field belongs to a JDK class
     * @param insn the instruction, for the names when {@code ref} is null
     */
    public Object get(Object target, FieldRef ref, FieldInsnNode insn) {
        if (target == null) {
            throw vm.raise(new NullPointerException("Cannot read field \"" + insn.name + "\" because value is null"));
        }
        if (ref != null) {
            InstanceState state = vm.stateOf(target);
            if (state != null && ref.slot() < state.fields.length) {
                return state.fields[ref.slot()];
            }
            return Values.zero(Type.getType(insn.desc));
        }
        if (target instanceof PhantomObject) {
            return Values.zero(Type.getType(insn.desc));
        }
        return vm.host.getField(target, hostOwner(insn.owner), insn.name);
    }

    public void put(Object target, FieldRef ref, FieldInsnNode insn, Object value) {
        if (target == null) {
            throw vm.raise(new NullPointerException("Cannot assign field \"" + insn.name + "\" because value is null"));
        }
        if (ref != null) {
            InstanceState state = vm.stateOf(target);
            if (state != null && ref.slot() < state.fields.length) {
                state.fields[ref.slot()] = value;
            }
            return;
        }
        if (!(target instanceof PhantomObject)) {
            vm.host.putField(target, hostOwner(insn.owner), insn.name, value);
        }
    }

    /** Maps a guest class name to its nearest JDK ancestor; JDK names are returned unchanged. */
    private String hostOwner(String owner) {
        GuestClass guest = vm.classes.find(owner);
        return guest == null ? owner : guest.hostSuperName();
    }
}

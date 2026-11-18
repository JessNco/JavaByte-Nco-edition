package dev.javabyte.vm;

import dev.javabyte.model.FieldRef;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.InstanceState;
import dev.javabyte.model.PhantomObject;
import java.util.function.Supplier;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;

/**
 * Field reads and writes, for both interpreted fields and fields of real JDK objects.
 *
 * <p>Alongside {@link InvocationRouter}, this is the only place where guest code reaches the JDK.
 * Static reads of JDK fields are policy-checked here, and static writes are never performed.
 * Instance fields of JDK objects are read and written without a policy check; see
 * {@link #get} for why that is safe.
 */
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
     * <p>A JDK object's instance field needs no policy check. The guest can only hold host
     * objects that policy-checked calls, constructors or stubs gave it, and the host bridge
     * reaches only public fields ({@code Class.getField}) or the accessors generated for a
     * shell's superclass. A public field of an object the guest already holds can do nothing
     * that its public methods could not. The simulated classes in {@code dev.javabyte.env}
     * declare no public fields, which keeps emulator state out of reach; keep it that way.
     *
     * @param ref  the resolved guest field, or null if the field belongs to a JDK class
     * @param insn the instruction, for the names when {@code ref} is null
     */
    public Object get(Object target, FieldRef ref, FieldInsnNode insn) {
        if (target == null) {
            throw vm.implicitExceptions.nullPointer("Cannot read field \"" + insn.name + "\" because value is null");
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

    /**
     * Writes an instance field. A JDK object's field is written without a policy check, for the
     * reasons given on {@link #get}.
     *
     * @param ref  the resolved guest field, or null if the field belongs to a JDK class
     * @param insn the instruction, for the names when {@code ref} is null
     */
    public void put(Object target, FieldRef ref, FieldInsnNode insn, Object value) {
        if (target == null) {
            throw vm.implicitExceptions.nullPointer("Cannot assign field \"" + insn.name + "\" because value is null");
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

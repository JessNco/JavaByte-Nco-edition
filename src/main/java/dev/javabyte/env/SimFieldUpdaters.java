package dev.javabyte.env;

import dev.javabyte.model.FieldRef;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.InstanceState;
import dev.javabyte.vm.VirtualMachine;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Predicate;

/**
 * The atomic field updaters ({@code AtomicReferenceFieldUpdater}, {@code AtomicIntegerFieldUpdater}
 * and {@code AtomicLongFieldUpdater}) for fields of interpreted classes.
 *
 * <p>The JDK's own updaters cannot work here: they find the field by reflection on a real class
 * and then read and write it at a memory offset, while a guest object keeps its fields in an
 * {@link InstanceState}. These updaters do the same work on the guest's fields. The checks
 * that decide whether an updater may be made, and the exceptions for each misuse, are in
 * {@code AtomicStubs}; what happens here is the reading and writing.
 *
 * <p>Each operation runs while holding a lock on the object's state, so a compare-and-set is
 * atomic even if two guest threads ever ran at once. Only the JDK's primitive operations are
 * implemented; the others the JDK provides ({@code getAndIncrement}, {@code getAndUpdate} and
 * so on) are written in terms of them, so they need nothing here.
 *
 * <p>The guest never sees these class names: {@code AtomicStubs} reports each as the JDK's own
 * implementation class, and {@code toString()} prints that name with a fixed identity hash.
 */
public final class SimFieldUpdaters {

    private SimFieldUpdaters() {}

    /**
     * Where an updater's field lives and which objects it may be used on.
     *
     * @param vm         the machine, for raising exceptions to the guest
     * @param type       the class named to {@code newUpdater}
     * @param field      the field: its slot in each object's state
     * @param restricted for a protected field reached from a subclass in another package, the
     *                   caller's class, which every object must also be an instance of; else null
     * @param jdkName    the JDK implementation class this updater imitates, for {@code toString()}
     */
    public record Target(VirtualMachine vm, GuestClass type, FieldRef field, GuestClass restricted, String jdkName) {

        /**
         * The state of {@code object}, after the check the JDK makes on every operation: the
         * object must be an instance of the updater's class (and of the caller's, when that
         * applies). A null or foreign object is a {@code ClassCastException}, as on a real JVM.
         */
        InstanceState stateOf(Object object) {
            InstanceState state = vm.stateOf(object);
            if (state == null || !state.type.isSubtypeOf(type.name)
                    || (restricted != null && !state.type.isSubtypeOf(restricted.name))) {
                throw vm.raise(new ClassCastException());
            }
            return state;
        }

        /** The text {@code Object.toString()} gives on a real JVM, with a stable hash. */
        String text(Object updater) {
            return jdkName.replace('/', '.') + '@' + Integer.toHexString(vm.types.identityHash(updater));
        }
    }

    /** {@code AtomicReferenceFieldUpdater} for a guest field of reference type. */
    public static final class Reference extends AtomicReferenceFieldUpdater<Object, Object> {

        private final Target target;
        /** Whether a value may be stored: null, or an instance of the updater's value class. */
        private final Predicate<Object> accepts;

        public Reference(Target target, Predicate<Object> accepts) {
            this.target = target;
            this.accepts = accepts;
        }

        private Object checked(Object value) {
            if (value != null && !accepts.test(value)) {
                throw target.vm().raise(new ClassCastException());
            }
            return value;
        }

        @Override
        public boolean compareAndSet(Object object, Object expect, Object update) {
            InstanceState state = target.stateOf(object);
            checked(update);
            synchronized (state) {
                if (state.fields[target.field().slot()] != expect) {
                    return false;
                }
                state.fields[target.field().slot()] = update;
                return true;
            }
        }

        @Override
        public boolean weakCompareAndSet(Object object, Object expect, Object update) {
            return compareAndSet(object, expect, update);
        }

        @Override
        public void set(Object object, Object value) {
            InstanceState state = target.stateOf(object);
            checked(value);
            synchronized (state) {
                state.fields[target.field().slot()] = value;
            }
        }

        @Override
        public void lazySet(Object object, Object value) {
            set(object, value);
        }

        @Override
        public Object get(Object object) {
            InstanceState state = target.stateOf(object);
            synchronized (state) {
                return state.fields[target.field().slot()];
            }
        }

        @Override
        public Object getAndSet(Object object, Object value) {
            InstanceState state = target.stateOf(object);
            checked(value);
            synchronized (state) {
                Object previous = state.fields[target.field().slot()];
                state.fields[target.field().slot()] = value;
                return previous;
            }
        }

        @Override
        public String toString() {
            return target.text(this);
        }
    }

    /** {@code AtomicIntegerFieldUpdater} for a guest {@code int} field. */
    public static final class Int extends AtomicIntegerFieldUpdater<Object> {

        private final Target target;

        public Int(Target target) {
            this.target = target;
        }

        /** Reads the field; an int field holds an {@code Integer} in the interpreter's representation. */
        private static int read(InstanceState state, int slot) {
            Object value = state.fields[slot];
            return value == null ? 0 : (Integer) value;
        }

        @Override
        public boolean compareAndSet(Object object, int expect, int update) {
            InstanceState state = target.stateOf(object);
            synchronized (state) {
                if (read(state, target.field().slot()) != expect) {
                    return false;
                }
                state.fields[target.field().slot()] = update;
                return true;
            }
        }

        @Override
        public boolean weakCompareAndSet(Object object, int expect, int update) {
            return compareAndSet(object, expect, update);
        }

        @Override
        public void set(Object object, int value) {
            InstanceState state = target.stateOf(object);
            synchronized (state) {
                state.fields[target.field().slot()] = value;
            }
        }

        @Override
        public void lazySet(Object object, int value) {
            set(object, value);
        }

        @Override
        public int get(Object object) {
            InstanceState state = target.stateOf(object);
            synchronized (state) {
                return read(state, target.field().slot());
            }
        }

        @Override
        public String toString() {
            return target.text(this);
        }
    }

    /** {@code AtomicLongFieldUpdater} for a guest {@code long} field. */
    public static final class Long extends AtomicLongFieldUpdater<Object> {

        private final Target target;

        public Long(Target target) {
            this.target = target;
        }

        /** Reads the field; a long field holds a {@code Long} in the interpreter's representation. */
        private static long read(InstanceState state, int slot) {
            Object value = state.fields[slot];
            return value == null ? 0L : (java.lang.Long) value;
        }

        @Override
        public boolean compareAndSet(Object object, long expect, long update) {
            InstanceState state = target.stateOf(object);
            synchronized (state) {
                if (read(state, target.field().slot()) != expect) {
                    return false;
                }
                state.fields[target.field().slot()] = update;
                return true;
            }
        }

        @Override
        public boolean weakCompareAndSet(Object object, long expect, long update) {
            return compareAndSet(object, expect, update);
        }

        @Override
        public void set(Object object, long value) {
            InstanceState state = target.stateOf(object);
            synchronized (state) {
                state.fields[target.field().slot()] = value;
            }
        }

        @Override
        public void lazySet(Object object, long value) {
            set(object, value);
        }

        @Override
        public long get(Object object) {
            InstanceState state = target.stateOf(object);
            synchronized (state) {
                return read(state, target.field().slot());
            }
        }

        @Override
        public String toString() {
            return target.text(this);
        }
    }
}

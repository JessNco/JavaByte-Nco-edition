package dev.javabyte.stubs;

import dev.javabyte.vm.VirtualMachine;

/**
 * Installs every stub that ships with the emulator. Each group lives in its own class so that
 * the code replacing one area of the JDK can be read and reviewed on its own.
 */
public final class BuiltinStubs {

    private BuiltinStubs() {}

    /** Installs every built-in stub group on the given machine. */
    public static void install(VirtualMachine vm) {
        SystemStubs.install(vm);
        ProcessStubs.install(vm);
        ThreadStubs.install(vm);
        FileStubs.install(vm);
        NetworkStubs.install(vm);
        ReflectionStubs.install(vm);
        ClassLoaderStubs.install(vm);
        // Objects that JDK code would otherwise use to reach the network or load classes on
        // the guest's behalf. Security providers belong here in particular: each is a table of
        // class names that the JDK loads and constructs.
        InertObjectStubs.install(vm);
        InvokeStubs.install(vm);
        CryptoObservers.install(vm);
        RandomStubs.install(vm);
        TimeStubs.install(vm);
    }
}

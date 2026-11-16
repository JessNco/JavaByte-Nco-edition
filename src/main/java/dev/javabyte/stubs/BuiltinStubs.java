package dev.javabyte.stubs;

import dev.javabyte.vm.VirtualMachine;

/**
 * Installs every stub that ships with the emulator. Each group lives in its own class so that
 * the code replacing one area of the JDK can be read and reviewed on its own.
 */
public final class BuiltinStubs {

    private BuiltinStubs() {}

    public static void install(VirtualMachine vm) {
        SystemStubs.install(vm);
        ProcessStubs.install(vm);
        ThreadStubs.install(vm);
        FileStubs.install(vm);
        NetworkStubs.install(vm);
        ReflectionStubs.install(vm);
        ClassLoaderStubs.install(vm);
        InvokeStubs.install(vm);
        CryptoObservers.install(vm);
    }
}

package dev.javabyte.guest;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * Test fixture that tries to launch processes by every indirect route: reflection, a method
 * handle, and ProcessBuilder. Each must be intercepted exactly like a direct call. This class is
 * only ever interpreted.
 */
public final class Reflective {

    public static void main(String[] args) throws Throwable {
        Class<?> runtimeClass = Class.forName(new StringBuilder("emitnuR.gnal.avaj").reverse().toString());
        Object runtime = runtimeClass.getMethod("getRuntime").invoke(null);
        Method exec = runtimeClass.getMethod("exec", String.class);
        exec.invoke(runtime, "via-reflection.exe");

        MethodHandle handle = MethodHandles.lookup().findVirtual(Runtime.class, "exec",
                MethodType.methodType(Process.class, String.class));
        Process viaHandle = (Process) handle.invoke(Runtime.getRuntime(), "via-method-handle.exe");

        Process viaBuilder = new ProcessBuilder("via-builder.exe", "--flag").start();
        System.out.println("exit codes " + viaHandle.waitFor() + viaBuilder.waitFor());

        try {
            Class.forName("com.sandbox.Agent");
            System.out.println("agent present");
        } catch (ClassNotFoundException e) {
            System.out.println("agent absent");
        }
    }
}

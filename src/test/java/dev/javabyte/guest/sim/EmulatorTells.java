package dev.javabyte.guest.sim;

import java.io.File;
import java.net.Socket;

/**
 * Simulated anti-emulation probe: the sample provokes exceptions from JDK objects and the JVM
 * itself, then reads their messages and stack traces for anything a real Java 8 runtime would
 * not produce, such as an emulator's own package name.
 */
public final class EmulatorTells {

    public static void main(String[] args) throws Exception {
        castToString("file", new File("C:\\Users\\Public\\notes.txt"));
        castToString("socket", new Socket());
        castToString("loader", EmulatorTells.class.getClassLoader());
        castToString("process", Runtime.getRuntime().exec("whoami"));
        System.out.println("loader text " + EmulatorTells.class.getClassLoader().toString());
        String missing = null;
        try {
            System.out.println(missing.length());
        } catch (NullPointerException e) {
            System.out.println("npe message " + e.getMessage());
        }
        try {
            Integer.parseInt("zz");
        } catch (NumberFormatException e) {
            StackTraceElement top = e.getStackTrace()[0];
            System.out.println("nfe top " + top.getClassName() + '.' + top.getMethodName() + " module " + top.getModuleName());
        }
    }

    private static void castToString(String label, Object value) {
        try {
            String text = (String) value;
            System.out.println(label + " cast passed " + text);
        } catch (ClassCastException e) {
            System.out.println(label + " cast " + e.getMessage());
        }
    }
}

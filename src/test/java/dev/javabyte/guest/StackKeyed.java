package dev.javabyte.guest;

/**
 * Imitates the string encryption used by several commercial obfuscators, where the decryption
 * key is derived from the name of the calling class and method, read from a stack trace.
 * It only produces correct text if the emulator presents a faithful guest stack.
 */
public final class StackKeyed {

    public static void main(String[] args) {
        System.out.println(first());
        System.out.println(new StackKeyed().second());
        System.out.println(Thread.currentThread().getStackTrace()[1].getMethodName());
    }

    static String first() {
        return decrypt(encrypt("alpha payload", "dev.javabyte.guest.StackKeyedfirst"));
    }

    String second() {
        return decrypt(encrypt("beta payload", "dev.javabyte.guest.StackKeyedsecond"));
    }

    private static String encrypt(String plain, String key) {
        return xor(plain, key);
    }

    private static String decrypt(String cipher) {
        StackTraceElement caller = new Throwable().getStackTrace()[1];
        return xor(cipher, caller.getClassName() + caller.getMethodName());
    }

    private static String xor(String text, String key) {
        char[] out = text.toCharArray();
        for (int i = 0; i < out.length; i++) {
            out[i] ^= key.charAt(i % key.length()) & 0x1F;
        }
        return new String(out);
    }
}

package dev.javabyte.cli;

/** Lets tests outside the package reach the disassembler, which is package-private. */
public final class DisassemblyAccess {
    private DisassemblyAccess() {}

    public static String text(byte[] bytes) {
        return Disassembly.text(bytes);
    }
}

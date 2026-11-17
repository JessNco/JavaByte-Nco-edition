package dev.javabyte.cli;

import dev.javabyte.config.ConfigLoader.ConfigException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

/**
 * A class file as readable text: its fields, methods and their instructions, in ASM's
 * {@code javap}-like form. It only parses; nothing in the class runs, and no other class is
 * loaded. Damaged or deliberately malformed classes give a {@link ConfigException} with the
 * reason, never a crash.
 */
final class Disassembly {

    /** Larger classes are refused: no real class comes near this, and parsing is linear in size. */
    static final int MAX_CLASS_BYTES = 16 << 20;
    private static final int MAGIC = 0xCAFEBABE;

    private Disassembly() {}

    /**
     * Disassembles a class file, or the class {@code className} (dots or slashes) inside a jar or
     * zip.
     */
    static String of(Path file, String className) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new ConfigException(file + " is not a file");
        }
        byte[] bytes;
        if (startsWithMagic(file)) {
            if (className != null) {
                throw new ConfigException(file + " is a single class file; --class is only for jars and zips");
            }
            if (Files.size(file) > MAX_CLASS_BYTES) {
                throw new ConfigException(file + " is too large to be a class file");
            }
            bytes = Files.readAllBytes(file);
        } else {
            bytes = fromArchive(file, className);
        }
        return text(bytes);
    }

    /** The text of a class from its bytes. */
    static String text(byte[] bytes) {
        if (bytes.length < 10 || readInt(bytes) != MAGIC) {
            throw new ConfigException("not a class file (it does not start with the class file signature)");
        }
        StringWriter out = new StringWriter();
        try {
            new ClassReader(bytes).accept(new TraceClassVisitor(null, new Textifier(), new PrintWriter(out)), 0);
        } catch (RuntimeException | StackOverflowError e) {
            // ASM reports damage with whatever exception the bad offset causes.
            throw new ConfigException("the class file is damaged and cannot be read (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
        }
        return out.toString();
    }

    private static byte[] fromArchive(Path file, String className) throws IOException {
        if (className == null) {
            throw new ConfigException(file + " is not a class file; for a jar or zip, name a class with --class");
        }
        String entryName = className.replace('.', '/');
        if (!entryName.endsWith(".class")) {
            entryName += ".class";
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                throw new ConfigException(file + " has no class " + className);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readNBytes(MAX_CLASS_BYTES + 1);
                if (bytes.length > MAX_CLASS_BYTES) {
                    throw new ConfigException(className + " is too large to be a class file");
                }
                return bytes;
            }
        } catch (java.util.zip.ZipException e) {
            throw new ConfigException(file + " is neither a class file nor a readable jar or zip (" + e.getMessage() + ")");
        }
    }

    private static boolean startsWithMagic(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(4);
            return head.length == 4 && readInt(head) == MAGIC;
        }
    }

    private static int readInt(byte[] b) {
        return (b[0] & 0xff) << 24 | (b[1] & 0xff) << 16 | (b[2] & 0xff) << 8 | (b[3] & 0xff);
    }
}

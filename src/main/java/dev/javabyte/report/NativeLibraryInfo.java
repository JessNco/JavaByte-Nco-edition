package dev.javabyte.report;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the facts about a native library that matter to a Java analyst: its format, its
 * architecture and which JNI entry points it exports.
 *
 * <p>JavaByte does not execute native code. What it can do is say precisely where the Java side
 * hands over: which library a {@code native} method lives in, or, when a library exports only
 * {@code JNI_OnLoad}, that it binds its methods at load time (the signature of a native
 * obfuscator) and has to be studied with a native tool.
 *
 * <p>The parsers read only headers and symbol names, and treat the file as hostile: every
 * offset is bounds-checked by the buffer and any inconsistency ends parsing with what was found.
 */
public final class NativeLibraryInfo {

    /** Exported names kept per library; the count is always exact. */
    private static final int MAX_LISTED = 200;

    public final String format;
    public final String architecture;
    /** Exported symbols that follow the JNI naming scheme ({@code Java_pkg_Class_method}). */
    public final List<String> jniExports = new ArrayList<>();
    public int exportCount;
    /** True if the library exports {@code JNI_OnLoad}, where natives can be registered dynamically. */
    public boolean hasJniOnLoad;

    /** The most sections a PE image may have (the Windows loader's own limit). */
    private static final int MAX_PE_SECTIONS = 96;

    /** The most ELF symbols examined in one library; real ones have a few thousand. */
    private static final int MAX_SYMBOLS = 1_000_000;

    private NativeLibraryInfo(String format, String architecture) {
        this.format = format;
        this.architecture = architecture;
    }

    /** @return a description, or null if the bytes are not a recognised native library */
    public static NativeLibraryInfo parse(byte[] data) {
        try {
            if (data.length > 0x40 && data[0] == 'M' && data[1] == 'Z') {
                return parsePe(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));
            }
            if (data.length > 0x40 && data[0] == 0x7F && data[1] == 'E' && data[2] == 'L' && data[3] == 'F') {
                return parseElf(data);
            }
            if (data.length > 4) {
                int magic = ByteBuffer.wrap(data).getInt();
                if (magic == 0xFEEDFACE || magic == 0xFEEDFACF || magic == 0xCEFAEDFE || magic == 0xCFFAEDFE) {
                    return new NativeLibraryInfo("Mach-O", "unknown");
                }
            }
        } catch (RuntimeException malformed) {
            return new NativeLibraryInfo("malformed", "unknown");
        }
        return null;
    }

    private void addExport(String name) {
        exportCount++;
        if (name.equals("JNI_OnLoad") || name.startsWith("JNI_OnLoad@") || name.equals("_JNI_OnLoad@8")) {
            hasJniOnLoad = true;
        }
        // 32-bit Windows decorates stdcall exports as _Java_...@N.
        String plain = name.startsWith("_Java_") ? name.substring(1) : name;
        if (plain.startsWith("Java_") && jniExports.size() < MAX_LISTED) {
            int at = plain.indexOf('@');
            jniExports.add(at < 0 ? plain : plain.substring(0, at));
        }
    }

    // ------------------------------------------------------------------ PE (Windows)

    private static NativeLibraryInfo parsePe(ByteBuffer pe) {
        int header = pe.getInt(0x3C);
        if (pe.getInt(header) != 0x00004550) { // "PE\0\0"
            return new NativeLibraryInfo("MZ (not PE)", "unknown");
        }
        int machine = pe.getShort(header + 4) & 0xFFFF;
        // Windows loads no image with more than 96 sections. The field allows 65535, and every
        // export name is looked up in the section table, so a crafted header could otherwise
        // buy billions of steps with a small file.
        int sectionCount = Math.min(pe.getShort(header + 6) & 0xFFFF, MAX_PE_SECTIONS);
        int optionalSize = pe.getShort(header + 20) & 0xFFFF;
        int optional = header + 24;
        boolean plus = (pe.getShort(optional) & 0xFFFF) == 0x20B;
        NativeLibraryInfo info = new NativeLibraryInfo("PE",
                machine == 0x8664 ? "x86-64" : machine == 0x14C ? "x86" : machine == 0xAA64 ? "arm64" : "machine 0x" + Integer.toHexString(machine));
        int exportRva = pe.getInt(optional + (plus ? 112 : 96));
        if (exportRva == 0) {
            return info;
        }
        int sections = optional + optionalSize;
        int exports = rvaToOffset(pe, sections, sectionCount, exportRva);
        int nameCount = pe.getInt(exports + 24);
        int names = rvaToOffset(pe, sections, sectionCount, pe.getInt(exports + 32));
        for (int i = 0; i < nameCount && i < 100_000; i++) {
            info.addExport(cString(pe, rvaToOffset(pe, sections, sectionCount, pe.getInt(names + 4 * i))));
        }
        return info;
    }

    /** Converts a relative virtual address to a file offset using the section table. */
    private static int rvaToOffset(ByteBuffer pe, int sections, int count, int rva) {
        for (int i = 0; i < count; i++) {
            int entry = sections + 40 * i;
            int virtualSize = pe.getInt(entry + 8);
            int virtualAddress = pe.getInt(entry + 12);
            int rawSize = pe.getInt(entry + 16);
            if (rva >= virtualAddress && rva < virtualAddress + Math.max(virtualSize, rawSize)) {
                return rva - virtualAddress + pe.getInt(entry + 20);
            }
        }
        throw new IllegalArgumentException("RVA outside every section");
    }

    // ------------------------------------------------------------------ ELF (Linux)

    private static NativeLibraryInfo parseElf(byte[] data) {
        boolean wide = data[4] == 2;
        ByteBuffer elf = ByteBuffer.wrap(data).order(data[5] == 2 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        int machine = elf.getShort(18) & 0xFFFF;
        NativeLibraryInfo info = new NativeLibraryInfo("ELF",
                machine == 62 ? "x86-64" : machine == 3 ? "x86" : machine == 183 ? "arm64" : machine == 40 ? "arm" : "machine " + machine);
        long sectionTable = wide ? elf.getLong(0x28) : elf.getInt(0x20) & 0xFFFFFFFFL;
        int entrySize = elf.getShort(wide ? 0x3A : 0x2E) & 0xFFFF;
        int count = elf.getShort(wide ? 0x3C : 0x30) & 0xFFFF;
        // The library belongs to the sample, and its section table can name the same symbol
        // table thousands of times (an entry size of zero makes every entry the first one).
        // The symbols looked at are counted across all of them.
        int symbolsLeft = MAX_SYMBOLS;
        for (int i = 0; i < count && symbolsLeft > 0; i++) {
            int section = (int) sectionTable + i * entrySize;
            if (elf.getInt(section + 4) != 11) { // SHT_DYNSYM: the symbols visible to a loader
                continue;
            }
            long offset = wide ? elf.getLong(section + 24) : elf.getInt(section + 16) & 0xFFFFFFFFL;
            long size = wide ? elf.getLong(section + 32) : elf.getInt(section + 20) & 0xFFFFFFFFL;
            int stringSection = (int) sectionTable + elf.getInt(section + (wide ? 40 : 24)) * entrySize;
            long strings = wide ? elf.getLong(stringSection + 24) : elf.getInt(stringSection + 16) & 0xFFFFFFFFL;
            int symbolSize = wide ? 24 : 16;
            for (long at = offset; at + symbolSize <= offset + size && symbolsLeft > 0; at += symbolSize, symbolsLeft--) {
                int nameIndex = elf.getInt((int) at);
                int definedIn = elf.getShort((int) at + (wide ? 6 : 14)) & 0xFFFF;
                if (nameIndex != 0 && definedIn != 0) { // section 0 means "imported", not exported
                    info.addExport(cString(elf, (int) strings + nameIndex));
                }
            }
        }
        return info;
    }

    private static String cString(ByteBuffer buffer, int offset) {
        int end = offset;
        while (buffer.get(end) != 0 && end - offset < 512) {
            end++;
        }
        byte[] bytes = new byte[end - offset];
        buffer.get(offset, bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /**
     * The exported symbol a JVM would look for to bind a native method, without the overload
     * suffix (JNI specification, "Resolving Native Method Names").
     */
    public static String jniName(String internalClassName, String methodName) {
        return "Java_" + mangle(internalClassName) + '_' + mangle(methodName);
    }

    private static String mangle(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (c == '/') {
                out.append('_');
            } else if (c == '_') {
                out.append("_1");
            } else if (c == ';') {
                out.append("_2");
            } else if (c == '[') {
                out.append("_3");
            } else if (c < 0x80 && Character.isLetterOrDigit(c)) {
                out.append(c);
            } else {
                out.append(String.format("_0%04x", (int) c));
            }
        }
        return out.toString();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("format", format);
        map.put("architecture", architecture);
        map.put("exportCount", exportCount);
        map.put("jniExportCount", jniExports.size());
        map.put("hasJniOnLoad", hasJniOnLoad);
        map.put("jniExports", jniExports);
        return map;
    }

    /** One-line conclusion about how the library binds to Java. */
    public String binding() {
        if (!jniExports.isEmpty()) {
            return jniExports.size() + " JNI functions exported by name";
        }
        return hasJniOnLoad ? "JNI_OnLoad only: natives are registered at load time" : "no JNI exports";
    }
}

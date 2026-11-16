package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.guest.NativeUser;
import dev.javabyte.report.NativeLibraryInfo;
import dev.javabyte.report.Report;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Native methods: default reporting, plugin-supplied behaviour, and library description. */
class NativeTest {

    @TempDir
    Path temp;

    @Test
    void nativeCallIsReportedAndReturnsZeroByDefault() throws Exception {
        Report report = Fixtures.run(Fixtures.config(Fixtures.jar(temp, Map.of(), NativeUser.class)));
        assertEquals(List.of("library missing", "scrambled 0"), Fixtures.stdout(report), Fixtures.outcomes(report));
        assertEquals("dev/javabyte/guest/NativeUser.scramble(I)I",
                Fixtures.events(report, "native", "call").get(0).data().get("method"));
        assertEquals("scrambler", Fixtures.events(report, "native", "load-library").get(0).data().get("library"));
    }

    @Test
    void pluginCanSupplyANativeMethod() throws Exception {
        EmulatorConfig config = Fixtures.config(Fixtures.jar(temp, Map.of(), NativeUser.class));
        config.plugins.put("test-native", Map.of("factor", 6));
        Report report = Fixtures.run(config);
        assertEquals(List.of("library missing", "scrambled 42"), Fixtures.stdout(report), Fixtures.outcomes(report));
        assertTrue(Fixtures.events(report, "native", "call").isEmpty(), "a handled call is not an unemulated one");
    }

    @Test
    void jniSymbolNamesFollowTheSpecification() {
        assertEquals("Java_com_example_Hook_enable", NativeLibraryInfo.jniName("com/example/Hook", "enable"));
        assertEquals("Java_a_b_1c_My_1Class_do_1it", NativeLibraryInfo.jniName("a/b_c/My_Class", "do_it"));
    }

    @Test
    void elfLibraryExportsAreRead() {
        NativeLibraryInfo info = NativeLibraryInfo.parse(minimalElf("Java_demo_Lib_run", "JNI_OnLoad", "helper"));
        assertNotNull(info);
        assertEquals("ELF", info.format);
        assertEquals("x86-64", info.architecture);
        assertEquals(List.of("Java_demo_Lib_run"), info.jniExports);
        assertTrue(info.hasJniOnLoad);
        assertEquals(3, info.exportCount);
    }

    @Test
    void nonLibrariesAndGarbageAreHandled() {
        assertNull(NativeLibraryInfo.parse("just text".getBytes(StandardCharsets.US_ASCII)));
        byte[] truncated = new byte[80];
        truncated[0] = 'M';
        truncated[1] = 'Z';
        truncated[0x3C] = 0x7F; // header offset far beyond the end
        truncated[0x3D] = 0x7F;
        NativeLibraryInfo info = NativeLibraryInfo.parse(truncated);
        assertNotNull(info);
        assertFalse(info.hasJniOnLoad);
        assertEquals("malformed", info.format);
    }

    /** Builds the smallest 64-bit ELF image that has a dynamic symbol table with the given names. */
    private static byte[] minimalElf(String... symbols) {
        StringBuilder names = new StringBuilder("\0");
        int[] nameOffsets = new int[symbols.length];
        for (int i = 0; i < symbols.length; i++) {
            nameOffsets[i] = names.length();
            names.append(symbols[i]).append('\0');
        }
        byte[] strings = names.toString().getBytes(StandardCharsets.US_ASCII);
        int symbolTable = 64;
        int symbolBytes = 24 * (symbols.length + 1);
        int stringTable = symbolTable + symbolBytes;
        int sectionTable = stringTable + strings.length;
        ByteBuffer elf = ByteBuffer.allocate(sectionTable + 3 * 64).order(ByteOrder.LITTLE_ENDIAN);
        elf.put(new byte[] {0x7F, 'E', 'L', 'F', 2, 1, 1});
        elf.putShort(18, (short) 62);           // x86-64
        elf.putLong(0x28, sectionTable);
        elf.putShort(0x3A, (short) 64);
        elf.putShort(0x3C, (short) 3);
        for (int i = 0; i < symbols.length; i++) {
            int at = symbolTable + 24 * (i + 1);
            elf.putInt(at, nameOffsets[i]);
            elf.putShort(at + 6, (short) 1);    // defined in a section, i.e. exported
        }
        elf.put(stringTable, strings);
        int dynsym = sectionTable + 64;         // section 1; section 0 is the null section
        elf.putInt(dynsym + 4, 11);             // SHT_DYNSYM
        elf.putLong(dynsym + 24, symbolTable);
        elf.putLong(dynsym + 32, symbolBytes);
        elf.putInt(dynsym + 40, 2);             // strings are in section 2
        int strtab = sectionTable + 128;
        elf.putInt(strtab + 4, 3);              // SHT_STRTAB
        elf.putLong(strtab + 24, stringTable);
        elf.putLong(strtab + 32, strings.length);
        return elf.array();
    }
}

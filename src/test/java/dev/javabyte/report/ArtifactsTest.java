package dev.javabyte.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactsTest {

    @TempDir Path temp;

    /** Reports are read on other systems, so saved paths use '/' even on Windows. */
    @Test
    void savedPathsUseForwardSlashesOnEverySystem() throws Exception {
        Artifacts artifacts = new Artifacts(temp);
        String relative = artifacts.save("dropped", "C:\\Users\\Public\\stage.bin", new byte[] {'M', 'Z'});
        assertFalse(relative.contains("\\"), relative);
        assertTrue(relative.startsWith("dropped/"), relative);
        assertTrue(relative.endsWith("/stage.bin"), relative);
        assertEquals(java.util.List.of(relative), artifacts.saved());
        assertTrue(Files.isRegularFile(temp.resolve(relative)), "the path leads to the file");
    }

    /**
     * Windows takes {@code aux.class} or {@code NUL} for a device, so a file of that name would
     * not be saved there. Such names are changed, the same way on every system.
     */
    @Test
    void namesWindowsTakesForDevicesAreChanged() {
        assertEquals("a/_aux.class", Artifacts.sanitise("a/aux.class"));
        assertEquals("C_/Users/_NUL", Artifacts.sanitise("C:\\Users\\NUL"));
        assertEquals("_con/x.bin", Artifacts.sanitise("con/x.bin"));
        assertEquals("_COM1.tar.gz", Artifacts.sanitise("COM1.tar.gz"));
        assertEquals("_lpt9 .txt", Artifacts.sanitise("lpt9 .txt"));
        assertEquals("_CONOUT$", Artifacts.sanitise("CONOUT$"));
        assertEquals("prn_", Artifacts.sanitise("prn."));
        assertEquals("x_", Artifacts.sanitise("x."));
        assertEquals("a_/b._", Artifacts.sanitise("a /b.."));
    }

    /** Names that only resemble a device name, and every ordinary name, stay exactly as they were. */
    @Test
    void otherNamesAreKept() {
        for (String name : new String[] {"console.log", "null", "COM10", "com", "LPT", "a.nul", "x.con.jar",
                "auxiliary/Aux1.class", "C_/Users/Public/stage.bin", "payload$1.class", "My Documents/a b.txt", ".hidden"}) {
            assertEquals(name, Artifacts.sanitise(name));
        }
    }

    @Test
    void aDeviceNameIsSavedAsAFile() throws Exception {
        Artifacts artifacts = new Artifacts(temp);
        String relative = artifacts.save("defined-classes", "a/aux.class", new byte[] {1, 2, 3});
        assertEquals("defined-classes/a/_aux.class", relative);
        assertEquals(3, Files.size(temp.resolve(relative)));
    }
}

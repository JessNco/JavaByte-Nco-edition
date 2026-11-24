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
     * The sample chooses the names. None may leave the output folder, name a Windows device,
     * or be a file the Windows shell reads by itself when the folder is opened.
     */
    @Test
    void namesAreMadeInertBeforeAnythingIsWritten() {
        assertEquals("etc/passwd", Artifacts.sanitise("../../etc/passwd"));
        assertEquals("_CON", Artifacts.sanitise("CON"));
        assertEquals("C_/_nul.txt", Artifacts.sanitise("C:\\nul.txt"));
        assertEquals("_COM1", Artifacts.sanitise("COM1"));
        assertEquals("console.log", Artifacts.sanitise("console.log"));
        assertEquals("name_", Artifacts.sanitise("name."));
        assertEquals("Startup/desktop.ini.saved", Artifacts.sanitise("Startup/desktop.ini"));
        assertEquals("a/Invoice.URL.saved", Artifacts.sanitise("a/Invoice.URL"));
        assertEquals("a/x.lnk.saved/y.jar", Artifacts.sanitise("a/x.lnk/y.jar"));
        assertEquals("payload.jar", Artifacts.sanitise("payload.jar"));
    }

    /** Two names that cannot coexist (a file, then a folder of the same name) both get saved. */
    @Test
    void aNameThatClashesWithAnEarlierOneIsSavedUnderANeutralName() throws Exception {
        Artifacts artifacts = new Artifacts(temp);
        artifacts.save("dropped", "a", new byte[] {1});
        String second = artifacts.save("dropped", "a/b", new byte[] {2});
        assertEquals(2, artifacts.saved().size());
        assertEquals(1, Files.size(temp.resolve(second)));
        assertTrue(temp.resolve(second).normalize().startsWith(temp.resolve("dropped")), second);
    }

    /** Text from a sample is shown, never obeyed, by a terminal or a YAML reader. */
    @Test
    void sampleTextIsMadeVisible() {
        assertEquals("a\\u001b[2Jb\\u000ac\\u202ed", Text.visible("a\u001b[2Jb\nc\u202ed"));
        assertEquals("line\n\tnext\\u000d", Text.visibleLines("line\n\tnext\r"));
        assertEquals("plain text, caf\u00e9", Text.visible("plain text, caf\u00e9"));
        assertEquals("\"a\\u000ab: \\\"c\\\" # d \\\\ \\u00e9\"", Text.yamlString("a\nb: \"c\" # d \\ \u00e9"));
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

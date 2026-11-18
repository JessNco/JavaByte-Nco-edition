package dev.javabyte.env;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.report.EventLog;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Renaming in the virtual file system behaves as it does on a real disk. */
class VirtualFileSystemTest {

    private final EventLog events = new EventLog(1000, null);

    private VirtualFileSystem windows() {
        return new VirtualFileSystem(true, events, 1 << 20, 0);
    }

    @Test
    void renamingADirectoryMovesEverythingUnderIt() {
        VirtualFileSystem fs = windows();
        fs.write("C:\\Users\\admin\\stage\\a.txt", "alpha".getBytes(StandardCharsets.UTF_8));
        fs.write("C:\\Users\\admin\\stage\\sub\\b.bin", new byte[] {1, 2});

        assertTrue(fs.rename("C:\\Users\\admin\\stage", "C:\\Users\\admin\\AppData\\Roaming\\Startup"));

        assertArrayEquals("alpha".getBytes(StandardCharsets.UTF_8), fs.read("C:\\Users\\admin\\AppData\\Roaming\\Startup\\a.txt"));
        assertArrayEquals(new byte[] {1, 2}, fs.read("C:\\Users\\admin\\AppData\\Roaming\\Startup\\sub\\b.bin"));
        assertEquals(List.of("a.txt", "sub"), fs.list("C:\\Users\\admin\\AppData\\Roaming\\Startup").stream().sorted().toList());
        assertNull(fs.node("C:\\Users\\admin\\stage\\a.txt"), "nothing is left under the old name");
        assertNull(fs.node("C:\\Users\\admin\\stage"));
        // Moved files are the sample's work, so they are saved as dropped files.
        assertTrue(fs.guestFiles().stream().anyMatch(node -> node.path.endsWith("Startup\\sub\\b.bin")));
        // a.txt, the sub directory and the file inside it.
        assertEquals(3, events.byCategory("file").stream().filter(event -> event.action().equals("rename"))
                .findFirst().orElseThrow().data().get("entriesMoved"));
    }

    @Test
    void aDirectoryCannotBeMovedInsideItself() {
        VirtualFileSystem fs = windows();
        fs.write("C:\\stage\\a.txt", new byte[] {1});
        assertFalse(fs.rename("C:\\stage", "C:\\stage\\inner"));
        assertArrayEquals(new byte[] {1}, fs.read("C:\\stage\\a.txt"));
    }

    @Test
    void changingOnlyTheLetterCaseKeepsTheContents() {
        VirtualFileSystem fs = windows();
        fs.write("C:\\stage\\a.txt", new byte[] {1});
        assertTrue(fs.rename("C:\\stage", "C:\\STAGE"));
        assertArrayEquals(new byte[] {1}, fs.read("C:\\STAGE\\a.txt"));
    }

    @Test
    void renamingAFileStillWorks() {
        VirtualFileSystem fs = new VirtualFileSystem(false, events, 1 << 20, 0);
        fs.write("/tmp/x", new byte[] {9});
        assertTrue(fs.rename("/tmp/x", "/home/user/y"));
        assertArrayEquals(new byte[] {9}, fs.read("/home/user/y"));
        assertNull(fs.node("/tmp/x"));
        assertFalse(fs.rename("/tmp/missing", "/tmp/z"));
    }
}

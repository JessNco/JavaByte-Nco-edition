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
}

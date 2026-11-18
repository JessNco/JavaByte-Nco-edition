package dev.javabyte.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.javabyte.Emulator;
import dev.javabyte.config.ConfigLoader.ConfigException;
import dev.javabyte.model.ClassRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code inspect} reads a sample with the same code as {@code run}, so the two agree. */
class InspectionTest {

    @TempDir
    Path temp;

    @Test
    void aFileThatOnlyStartsWithPkIsRefusedAsRunRefusesIt() throws Exception {
        // Two bytes of "PK" are not an archive. inspect used to accept them as one.
        Path stub = Files.write(temp.resolve("stub.jar"), new byte[] {'P', 'K', 3});
        ConfigException fromInspect = assertThrows(ConfigException.class, () -> Inspection.of(stub));
        ConfigException fromRun = assertThrows(ConfigException.class,
                () -> Emulator.loadSample(new ClassRepository(), stub, Files.readAllBytes(stub), "sample"));
        assertEquals(fromRun.getMessage(), fromInspect.getMessage());
    }

    @Test
    void aDirectoryIsDescribedWithItsManifestAndTotalSize() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("classes/meta-inf"));
        byte[] manifest = "Manifest-Version: 1.0\r\nMain-Class: app.Start\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        Files.write(folder.resolve("manifest.mf"), manifest);
        Files.write(temp.resolve("classes/readme.txt"), new byte[] {1, 2, 3, 4, 5});
        Inspection inspection = Inspection.of(temp.resolve("classes"));
        assertEquals("app.Start", inspection.toMap().get("mainClass"));
        assertEquals((long) manifest.length + 5, inspection.toMap().get("size"));
        assertEquals(List.of(), inspection.toMap().get("mainCandidates"));
    }
}

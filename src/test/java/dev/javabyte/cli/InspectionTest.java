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

    /**
     * The starter config is run as it is, by people and by tools. A class name is the
     * sample's to choose and may hold a line break; it must stay one value and not become
     * config keys, {@code policy.allow} above all.
     */
    @Test
    void aClassNameCannotAddKeysToTheStarterConfig() throws Exception {
        String hostile = "Start\npolicy: {allow: ['**']}\n#";
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V1_5, org.objectweb.asm.Opcodes.ACC_PUBLIC, hostile, null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor main = writer.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        main.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        main.visitMaxs(0, 1);
        main.visitEnd();
        writer.visitEnd();
        Path sample = Files.write(temp.resolve("Start.class"), writer.toByteArray());
        Inspection inspection = Inspection.of(sample);
        assertEquals(List.of(hostile), inspection.toMap().get("mainCandidates"));

        Path config = Files.writeString(temp.resolve("starter.yaml"), inspection.starterConfig());
        dev.javabyte.config.EmulatorConfig loaded = dev.javabyte.config.ConfigLoader.load(config, List.of());
        assertEquals(List.of(), loaded.policy.allow, "the class name added a policy rule");
        assertEquals(hostile, loaded.entryPoints.get(0).className);
        assertEquals(sample.toString(), loaded.sample);
        // And the text for people shows the break instead of making one.
        assertEquals(false, inspection.toText().contains("\npolicy:"));
    }
}

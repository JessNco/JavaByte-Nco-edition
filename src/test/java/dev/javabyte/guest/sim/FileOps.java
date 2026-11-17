package dev.javabyte.guest.sim;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/**
 * Exercises the virtual disk through every file API a sample is likely to use: the classic
 * {@code java.io} classes, random access, channels with locks, and {@code java.nio.file}.
 * Each line it prints is a fact the test checks, so a wrong answer from the virtual disk is
 * caught even where the real JVM cannot be used as the reference (these are Windows paths).
 */
public final class FileOps {

    public static void main(String[] args) throws Exception {
        File work = new File(System.getenv("APPDATA"), "ops");
        System.out.println("mkdirs " + work.mkdirs() + " " + work.isDirectory() + " " + work.exists() + " " + work.mkdirs());

        // java.io streams, readers and writers
        File note = new File(work, "note.txt");
        try (FileOutputStream out = new FileOutputStream(note)) {
            out.write("line one\n".getBytes(StandardCharsets.UTF_8));
        }
        try (FileWriter writer = new FileWriter(note, true); BufferedWriter buffered = new BufferedWriter(writer)) {
            buffered.write("line two");
            buffered.newLine();
        }
        try (PrintWriter printer = new PrintWriter(new FileOutputStream(note, true))) {
            printer.print("line three");
        }
        StringBuilder read = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(note))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                read.append('[').append(line).append(']');
            }
        }
        try (FileInputStream in = new FileInputStream(note)) {
            System.out.println("streams " + read + " " + note.length() + " " + in.available() + " " + (char) in.read() + " " + in.skip(4) + " " + (char) in.read());
        }

        // File metadata and directory operations
        File renamed = new File(work, "renamed.txt");
        System.out.println("rename " + note.renameTo(renamed) + " " + note.exists() + " " + renamed.exists() + " " + renamed.getName()
                + " " + renamed.getParentFile().getName() + " " + renamed.isFile() + " " + renamed.canRead() + " " + renamed.isAbsolute());
        new File(work, "sub/deep").mkdirs();
        new File(work, "sub/a.bin").createNewFile();
        String[] names = work.list();
        Arrays.sort(names);
        File[] onlyTxt = work.listFiles((dir, name) -> name.endsWith(".txt"));
        System.out.println("list " + Arrays.toString(names) + " " + onlyTxt.length + " " + new File(work, "sub").listFiles().length
                + " " + new File(work, "sub/a.bin").createNewFile() + " " + new File(work, "missing").list());
        System.out.println("delete " + new File(work, "sub").delete() + " " + new File(work, "sub/a.bin").delete() + " "
                + new File(work, "sub/deep").delete() + " " + new File(work, "sub").delete() + " " + new File(work, "sub").exists());

        // Random access: overwrite in the middle, extend, truncate
        try (RandomAccessFile raf = new RandomAccessFile(new File(work, "data.bin"), "rw")) {
            raf.write(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
            raf.seek(2);
            raf.writeByte(99);
            raf.writeInt(0x01020304);
            raf.seek(0);
            byte[] all = new byte[(int) raf.length()];
            raf.readFully(all);
            raf.seek(3);
            int value = raf.readInt();
            raf.setLength(4);
            System.out.println("random " + Arrays.toString(all) + " " + Integer.toHexString(value) + " " + raf.length() + " " + raf.getFilePointer());
        }

        // Channels: positional writes, reads, size, truncate and the single-instance lock idiom
        File locked = new File(work, "instance.lock");
        try (FileOutputStream out = new FileOutputStream(locked); FileChannel channel = out.getChannel()) {
            FileLock lock = channel.tryLock();
            channel.write(ByteBuffer.wrap("0123456789".getBytes(StandardCharsets.US_ASCII)));
            channel.write(ByteBuffer.wrap("AB".getBytes(StandardCharsets.US_ASCII)), 3);
            System.out.println("channel " + (lock != null) + " " + lock.isValid() + " " + channel.size() + " " + channel.position() + " " + channel.isOpen());
            channel.truncate(8);
            lock.release();
        }
        try (FileInputStream in = new FileInputStream(locked); FileChannel channel = in.getChannel()) {
            ByteBuffer buffer = ByteBuffer.allocate(16);
            int count = channel.read(buffer);
            ByteBuffer tail = ByteBuffer.allocate(2);
            channel.read(tail, 6);
            System.out.println("channel-read " + count + " " + new String(buffer.array(), 0, count, StandardCharsets.US_ASCII)
                    + " " + new String(tail.array(), StandardCharsets.US_ASCII) + " " + channel.read(buffer));
        }

        // java.nio.file
        Path dir = Paths.get(work.getPath(), "nio");
        Files.createDirectories(dir);
        Path a = dir.resolve("a.txt");
        Files.write(a, "alpha\nbeta\n".getBytes(StandardCharsets.UTF_8));
        Files.write(a, List.of("gamma"), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Path b = Files.copy(a, dir.resolve("b.txt"));
        Path c = Files.move(b, dir.resolve("c.txt"), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("nio " + Files.readAllLines(a, StandardCharsets.UTF_8) + " " + Files.size(c) + " " + Files.exists(b) + " " + Files.exists(c)
                + " " + Files.isDirectory(dir) + " " + Files.isRegularFile(a) + " " + new String(Files.readAllBytes(c), StandardCharsets.UTF_8).length()
                + " " + a.getFileName() + " " + dir.relativize(a) + " " + Files.deleteIfExists(c) + " " + Files.deleteIfExists(c) + " " + Files.notExists(c));
        try (var listing = Files.list(dir)) {
            System.out.println("nio-list " + listing.map(path -> path.getFileName().toString()).sorted().toList());
        }
        try {
            Files.readAllBytes(dir.resolve("absent.txt"));
        } catch (java.nio.file.NoSuchFileException e) {
            System.out.println("nio-missing " + e.getClass().getSimpleName());
        }
        try {
            new FileInputStream(new File(work, "absent.bin")).close();
        } catch (java.io.FileNotFoundException e) {
            System.out.println("io-missing FileNotFoundException");
        }

        // Temporary files and a pre-existing file supplied by the analyst's config
        File temp = File.createTempFile("ops", ".tmp");
        temp.deleteOnExit();
        System.out.println("temp " + temp.getName().startsWith("ops") + " " + temp.getName().endsWith(".tmp") + " " + temp.exists()
                + " " + temp.getParent().equals(new File(System.getProperty("java.io.tmpdir")).getPath()));
        File planted = new File("C:\\Users\\admin\\Documents\\wallet.dat");
        System.out.println("planted " + planted.exists() + " " + planted.length() + " " + new String(Files.readAllBytes(planted.toPath()), StandardCharsets.UTF_8));
    }
}

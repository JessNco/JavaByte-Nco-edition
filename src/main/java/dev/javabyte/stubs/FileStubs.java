package dev.javabyte.stubs;

import dev.javabyte.env.SimFile;
import dev.javabyte.env.SimFileInputStream;
import dev.javabyte.env.SimFileOutputStream;
import dev.javabyte.env.SimPath;
import dev.javabyte.env.SimRandomAccessFile;
import dev.javabyte.env.VirtualFileSystem;
import dev.javabyte.host.CallContext;
import dev.javabyte.host.StubRegistry;
import dev.javabyte.vm.VirtualMachine;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * File access. Every constructor or static method that would open, create, list or delete
 * something on disk is redirected to the {@link VirtualFileSystem}.
 *
 * <p>The approach is to intercept the points where a path becomes a handle (constructors and
 * {@code Files} statics) and return simulated objects from the {@code env} package. From then
 * on ordinary virtual dispatch keeps the guest inside the simulation, so the instance methods
 * need no stubs of their own.
 */
final class FileStubs {

    private FileStubs() {}

    /** Installs this class's stubs on the machine; called once per run from {@code BuiltinStubs}. */
    static void install(VirtualMachine vm) {
        StubRegistry stubs = vm.stubs;
        VirtualFileSystem fs = vm.env.files;

        stubs.on("java/io/File", "<init>", ctx -> new SimFile(fs, filePath(fs, ctx.args)));
        stubs.on("java/io/File", "createTempFile", ctx -> {
            String directory = ctx.argCount() > 2 && ctx.arg(2) != null
                    ? ctx.arg(2).toString() : vm.env.properties.getProperty("java.io.tmpdir");
            String suffix = ctx.arg(1) == null ? ".tmp" : ctx.stringArg(1);
            String path = fs.join(directory, ctx.stringArg(0) + Long.toString(vm.env.clock.nanoTime(), 36) + suffix);
            fs.createFile(path);
            return new SimFile(fs, path);
        });
        stubs.on("java/io/File", "listRoots", ctx -> new File[] {new SimFile(fs, fs.isWindows() ? "C:\\" : "/")});
        stubs.staticField("java/io/File", "separator", () -> String.valueOf(fs.separator()));
        stubs.staticField("java/io/File", "separatorChar", () -> (int) fs.separator());
        stubs.staticField("java/io/File", "pathSeparator", () -> fs.isWindows() ? ";" : ":");
        stubs.staticField("java/io/File", "pathSeparatorChar", () -> (int) (fs.isWindows() ? ';' : ':'));

        stubs.on("java/io/FileInputStream", "<init>", ctx -> openInput(fs, path(ctx.arg(0))));
        stubs.on("java/io/FileOutputStream", "<init>", ctx ->
                openOutput(fs, path(ctx.arg(0)), ctx.argCount() > 1 && ctx.boolArg(1)));
        stubs.on("java/io/FileReader", "<init>", ctx ->
                new InputStreamReader(openInput(fs, path(ctx.arg(0))), charsetArg(ctx, 1)));
        stubs.on("java/io/FileWriter", "<init>", ctx -> {
            boolean append = false;
            for (Object arg : ctx.args) {
                append |= arg instanceof Integer flag && flag != 0;
            }
            return new OutputStreamWriter(openOutput(fs, path(ctx.arg(0)), append), charsetArg(ctx, 1));
        });
        stubs.on("java/io/RandomAccessFile", "<init>", ctx -> {
            String path = path(ctx.arg(0));
            byte[] existing = fs.isFile(path) ? fs.read(path) : null;
            if (existing == null && !ctx.stringArg(1).contains("w")) {
                throw notFound(fs, path);
            }
            return new SimRandomAccessFile(fs, path, existing == null ? new byte[0] : existing.clone());
        });

        // Only the overloads whose first argument names a file are redirected.
        stubs.on("java/io/PrintStream", "<init>", ctx -> isPathArg(ctx.arg(0))
                ? new PrintStream(openOutput(fs, path(ctx.arg(0)), false), false, charsetArg(ctx, 1)) : ctx.proceed());
        stubs.on("java/io/PrintWriter", "<init>", ctx -> isPathArg(ctx.arg(0))
                ? new PrintWriter(new OutputStreamWriter(openOutput(fs, path(ctx.arg(0)), false), charsetArg(ctx, 1)))
                : ctx.proceed());
        stubs.on("java/util/Formatter", "<init>", ctx -> ctx.argCount() > 0 && isPathArg(ctx.arg(0))
                ? new java.util.Formatter(new PrintStream(openOutput(fs, path(ctx.arg(0)), false), false, charsetArg(ctx, 1)))
                : ctx.proceed());
        stubs.on("java/util/Scanner", "<init>", ctx -> ctx.arg(0) instanceof File || ctx.arg(0) instanceof Path
                ? new Scanner(openInput(fs, path(ctx.arg(0))), charsetArg(ctx, 1)) : ctx.proceed());

        // Archive classes need a real file. A private temporary copy of the virtual file's bytes
        // is used; it holds only data the emulator already has in memory.
        // The copy's path is the analysis machine's, so the guest is told the path it asked for.
        java.util.Map<java.util.zip.ZipFile, String> archiveNames =
                java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        stubs.on("java/util/zip/ZipFile", "<init>", ctx -> openArchive(fs, path(ctx.arg(0)), java.util.zip.ZipFile::new, archiveNames));
        stubs.on("java/util/jar/JarFile", "<init>", ctx -> openArchive(fs, path(ctx.arg(0)), java.util.jar.JarFile::new, archiveNames));
        stubs.on("java/util/zip/ZipFile", "getName", ctx -> {
            String name = ctx.receiver instanceof java.util.zip.ZipFile archive ? archiveNames.get(archive) : null;
            return name != null ? name : "archive.zip";
        });

        stubs.on("java/nio/file/Paths", "get", ctx -> newPath(fs, ctx.args));
        stubs.on("java/nio/file/Path", "of", ctx -> newPath(fs, ctx.args));
        stubs.onClass("java/nio/file/Files", ctx -> files(vm, fs, ctx));
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isPathArg(Object arg) {
        return arg instanceof String || arg instanceof File;
    }

    /** Text of a path argument, whether given as a String, File or Path. */
    private static String path(Object arg) {
        if (arg == null) {
            // new File((String) null) is a guest programming error, reported to the guest.
            throw VirtualMachine.current().raise(new NullPointerException());
        }
        return arg instanceof File file ? file.getPath() : arg.toString();
    }

    /**
     * Turns the path of a {@code file:} URL or URI into a path on the virtual disk. Every stub
     * that accepts a file URL uses this, so they all agree on what a URL names.
     *
     * <p>Escapes such as {@code %20} and {@code %3A} are decoded the way {@code URI} decodes
     * them: a {@code +} stays a plus sign (only form encoding turns it into a space), and an
     * incomplete escape is kept as written. On Windows, the slash before a drive letter is then
     * dropped, so {@code /C:/x} and {@code /C%3A/x} both become {@code C:/x}.
     *
     * @param rawPath the path as it appears in the URL, escapes and all
     * @param windows true when the guest's disk uses Windows paths
     */
    static String pathFromFileUrl(String rawPath, boolean windows) {
        String path = decodePercent(rawPath);
        return windows && path.matches("/[A-Za-z]:.*") ? path.substring(1) : path;
    }

    /** Decodes {@code %XX} escapes as UTF-8, leaving everything else, including {@code +}, as it is. */
    private static String decodePercent(String text) {
        if (text.indexOf('%') < 0) {
            return text;
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int high = c == '%' && i + 2 < text.length() ? Character.digit(text.charAt(i + 1), 16) : -1;
            int low = high >= 0 ? Character.digit(text.charAt(i + 2), 16) : -1;
            if (low >= 0) {
                bytes.write(high << 4 | low);
                i += 2;
            } else {
                byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                if (Character.isHighSurrogate(c) && i + 1 < text.length()) {
                    encoded = text.substring(i, i + 2).getBytes(StandardCharsets.UTF_8);
                    i++;
                }
                bytes.write(encoded, 0, encoded.length);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** Resolves the {@code File} constructor overloads to one path. */
    private static String filePath(VirtualFileSystem fs, Object[] args) {
        if (args.length == 1) {
            if (args[0] instanceof URI uri) {
                return pathFromFileUrl(uri.getRawPath(), fs.isWindows());
            }
            return path(args[0]);
        }
        return args[0] == null ? path(args[1]) : fs.join(path(args[0]), path(args[1]));
    }

    private static Path newPath(VirtualFileSystem fs, Object[] args) {
        if (args[0] instanceof URI uri) {
            return new SimPath(fs, filePath(fs, new Object[] {uri}));
        }
        String path = (String) args[0];
        if (args.length > 1 && args[1] instanceof Object[] more) {
            for (Object part : more) {
                path = fs.join(path, String.valueOf(part));
            }
        }
        return new SimPath(fs, path);
    }

    private static Charset charsetArg(CallContext ctx, int index) {
        if (ctx.argCount() > index) {
            if (ctx.arg(index) instanceof Charset charset) {
                return charset;
            }
            if (ctx.arg(index) instanceof String name) {
                return Charset.forName(name);
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static FileNotFoundException notFound(VirtualFileSystem fs, String path) {
        return new FileNotFoundException(path + (fs.isWindows()
                ? " (The system cannot find the file specified)" : " (No such file or directory)"));
    }

    private static SimFileInputStream openInput(VirtualFileSystem fs, String path) throws FileNotFoundException {
        byte[] data = fs.read(path);
        if (data == null) {
            throw notFound(fs, path);
        }
        return new SimFileInputStream(data);
    }

    private static SimFileOutputStream openOutput(VirtualFileSystem fs, String path, boolean append) throws FileNotFoundException {
        if (fs.isDirectory(path)) {
            throw new FileNotFoundException(path + " (Is a directory)");
        }
        return new SimFileOutputStream(fs, path, append);
    }

    private static File materialise(VirtualFileSystem fs, String path) throws IOException {
        byte[] data = fs.read(path);
        if (data == null) {
            throw notFound(fs, path);
        }
        File copy = File.createTempFile("javabyte-archive", ".zip");
        copy.deleteOnExit();
        java.nio.file.Files.write(copy.toPath(), data);
        return copy;
    }

    /** How {@code ZipFile} and {@code JarFile} are opened from a real file. */
    @FunctionalInterface
    private interface ArchiveOpener<T extends java.util.zip.ZipFile> {
        T open(File file) throws IOException;
    }

    /**
     * Opens the archive at a virtual path through a temporary real copy, removes the copy, and
     * remembers the path the guest asked for, which is what {@code getName()} answers.
     */
    private static <T extends java.util.zip.ZipFile> T openArchive(VirtualFileSystem fs, String guestPath,
            ArchiveOpener<T> opener, java.util.Map<java.util.zip.ZipFile, String> names) throws IOException {
        File copy = materialise(fs, guestPath);
        try {
            T archive = opener.open(copy);
            names.put(archive, guestPath);
            return archive;
        } finally {
            discard(copy);
        }
    }

    /**
     * Removes a materialised archive as soon as it has been opened. Where the system allows an
     * open file to be deleted (everywhere but Windows) the sample's bytes then no longer sit
     * in the temporary folder under any name; on Windows the delete fails and the file goes
     * when the tool exits.
     */
    private static void discard(File copy) {
        try {
            java.nio.file.Files.deleteIfExists(copy.toPath());
        } catch (IOException | RuntimeException stillOpen) {
            // deleteOnExit() is already registered.
        }
    }

    // ------------------------------------------------------------------ java.nio.file.Files

    private static boolean hasOption(CallContext ctx, Object option) {
        for (Object arg : ctx.args) {
            if (arg instanceof Object[] options && List.of(options).contains(option)) {
                return true;
            }
        }
        return false;
    }

    private static Object files(VirtualMachine vm, VirtualFileSystem fs, CallContext ctx) throws IOException {
        String first = ctx.argCount() > 0 && ctx.arg(0) instanceof Path p ? p.toString() : null;
        boolean pathFree = ctx.name.startsWith("createTemp") || ctx.name.equals("copy");
        if (first == null && !pathFree) {
            // Every other Files method takes a path first; null (or a path object the emulator
            // could not provide) fails the way the real method would.
            throw ctx.raise(new NullPointerException("path"));
        }
        switch (ctx.name) {
            case "exists", "isReadable", "isExecutable":
                return fs.exists(first) ? 1 : 0;
            case "notExists":
                return fs.exists(first) ? 0 : 1;
            case "isDirectory":
                return fs.isDirectory(first) ? 1 : 0;
            case "isRegularFile":
                return fs.isFile(first) ? 1 : 0;
            case "isWritable":
                return 1;
            case "isHidden", "isSymbolicLink":
                return 0;
            case "isSameFile":
                return fs.absolute(first).equalsIgnoreCase(fs.absolute(ctx.arg(1).toString())) ? 1 : 0;
            case "size":
                return (long) required(fs, first).length;
            case "readAllBytes":
                return required(fs, first).clone();
            case "readString":
                return new String(required(fs, first), charsetArg(ctx, 1));
            case "readAllLines":
                return new ArrayList<>(new String(required(fs, first), charsetArg(ctx, 1)).lines().toList());
            case "lines":
                return new String(required(fs, first), charsetArg(ctx, 1)).lines();
            case "newInputStream":
                return new SimFileInputStream(required(fs, first));
            case "newOutputStream":
                return openOutput(fs, first, hasOption(ctx, StandardOpenOption.APPEND));
            case "newBufferedReader":
                return new BufferedReader(new InputStreamReader(new SimFileInputStream(required(fs, first)), charsetArg(ctx, 1)));
            case "newBufferedWriter":
                return new BufferedWriter(new OutputStreamWriter(
                        openOutput(fs, first, hasOption(ctx, StandardOpenOption.APPEND)), charsetArg(ctx, 1)));
            case "write", "writeString": {
                byte[] data;
                if (ctx.arg(1) instanceof byte[] bytes) {
                    data = bytes;
                } else if (ctx.arg(1) instanceof CharSequence text) {
                    data = text.toString().getBytes(charsetArg(ctx, 2));
                } else {
                    StringBuilder lines = new StringBuilder();
                    for (Object line : (Iterable<?>) ctx.arg(1)) {
                        lines.append(line).append(vm.env.lineSeparator());
                    }
                    data = lines.toString().getBytes(charsetArg(ctx, 2));
                }
                try (OutputStream out = openOutput(fs, first, hasOption(ctx, StandardOpenOption.APPEND))) {
                    out.write(data);
                }
                return ctx.arg(0);
            }
            case "copy":
                return copy(fs, ctx, first);
            case "move": {
                String target = ctx.arg(1).toString();
                if (fs.exists(target) && !hasOption(ctx, StandardCopyOption.REPLACE_EXISTING)) {
                    throw new FileAlreadyExistsException(target);
                }
                required(fs, first);
                fs.rename(first, target);
                return ctx.arg(1);
            }
            case "delete":
                if (!fs.delete(first)) {
                    throw new NoSuchFileException(first);
                }
                return null;
            case "deleteIfExists":
                return fs.delete(first) ? 1 : 0;
            case "createFile":
                if (!fs.createFile(first)) {
                    throw new FileAlreadyExistsException(first);
                }
                return ctx.arg(0);
            case "createDirectory", "createDirectories":
                fs.mkdirs(first);
                return ctx.arg(0);
            case "createTempFile", "createTempDirectory": {
                // Overloads: ([dir,] prefix[, suffix], attrs...). A leading Path is the directory.
                int offset = first == null ? 0 : 1;
                String directory = first != null ? first : vm.env.properties.getProperty("java.io.tmpdir");
                String prefix = ctx.arg(offset) == null ? "" : ctx.stringArg(offset);
                boolean isFile = ctx.name.equals("createTempFile");
                String suffix = isFile && ctx.arg(offset + 1) instanceof String s ? s : isFile ? ".tmp" : "";
                String path = fs.join(directory, prefix + Long.toString(vm.env.clock.nanoTime(), 36) + suffix);
                if (isFile) {
                    fs.createFile(path);
                } else {
                    fs.mkdirs(path);
                }
                return new SimPath(fs, path);
            }
            case "list": {
                List<String> names = fs.list(first);
                if (names == null) {
                    throw new NoSuchFileException(first);
                }
                return names.stream().<Path>map(name -> new SimPath(fs, fs.join(first, name)));
            }
            case "walk": {
                List<Path> found = new ArrayList<>();
                walk(fs, first, found);
                return found.stream();
            }
            case "probeContentType":
                return null;
            case "setAttribute", "setPosixFilePermissions", "setLastModifiedTime", "setOwner":
                return ctx.arg(0);
            default:
                return vm.router.gap("java.nio.file.Files." + ctx.name + ctx.desc, ctx.desc);
        }
    }

    private static byte[] required(VirtualFileSystem fs, String path) throws NoSuchFileException {
        byte[] data = fs.read(path);
        if (data == null) {
            throw new NoSuchFileException(path);
        }
        return data;
    }

    /** {@code Files.copy} in its three shapes: path to path, stream to path, path to stream. */
    private static Object copy(VirtualFileSystem fs, CallContext ctx, String first) throws IOException {
        if (ctx.arg(0) instanceof InputStream in) {
            String target = ctx.arg(1).toString();
            if (fs.exists(target) && !hasOption(ctx, StandardCopyOption.REPLACE_EXISTING)) {
                throw new FileAlreadyExistsException(target);
            }
            byte[] data = in.readAllBytes();
            fs.write(target, data);
            return (long) data.length;
        }
        byte[] data = required(fs, first);
        if (ctx.arg(1) instanceof OutputStream out) {
            out.write(data);
            return (long) data.length;
        }
        String target = ctx.arg(1).toString();
        if (fs.exists(target) && !hasOption(ctx, StandardCopyOption.REPLACE_EXISTING)) {
            throw new FileAlreadyExistsException(target);
        }
        fs.write(target, data.clone());
        return ctx.arg(1);
    }

    private static void walk(VirtualFileSystem fs, String path, List<Path> found) {
        found.add(new SimPath(fs, path));
        List<String> children = fs.list(path);
        if (children != null) {
            for (String child : children) {
                walk(fs, fs.join(path, child), found);
            }
        }
    }
}

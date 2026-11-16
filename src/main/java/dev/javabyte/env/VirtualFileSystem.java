package dev.javabyte.env;

import dev.javabyte.report.EventLog;
import dev.javabyte.vm.VirtualMachine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An in-memory file system that the guest program sees instead of the real disk.
 *
 * <p>It speaks the path syntax of the emulated operating system (Windows by default, because
 * that is what most Java malware targets) regardless of the system the emulator runs on. Path
 * handling is therefore implemented here rather than delegated to {@code java.io.File}, whose
 * behaviour follows the host.
 *
 * <p>Reads of files that do not exist fail truthfully and are reported, which tells the analyst
 * exactly which fixtures a sample is looking for.
 */
public final class VirtualFileSystem {

    /** One file or directory. */
    public static final class Node {
        public final String path;
        public final boolean directory;
        public byte[] data;
        public long modified;
        /** True if the guest created or changed it; such files are saved as dropped artifacts. */
        public boolean touchedByGuest;

        Node(String path, boolean directory, byte[] data, long modified) {
            this.path = path;
            this.directory = directory;
            this.data = data;
            this.modified = modified;
        }
    }

    private final boolean windows;
    private final char separator;
    private final EventLog events;
    private final long maxWrittenBytes;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<SimFileOutputStream> openOutputs = new ArrayList<>();
    private String workingDirectory;
    private long writtenBytes;
    private long now;

    public VirtualFileSystem(boolean windows, EventLog events, long maxWrittenBytes, long now) {
        this.windows = windows;
        this.separator = windows ? '\\' : '/';
        this.events = events;
        this.maxWrittenBytes = maxWrittenBytes;
        this.now = now;
        this.workingDirectory = windows ? "C:\\" : "/";
    }

    // ------------------------------------------------------------------ path syntax

    public char separator() {
        return separator;
    }

    public boolean isWindows() {
        return windows;
    }

    public void setWorkingDirectory(String directory) {
        this.workingDirectory = normalise(directory);
    }

    /** Applies the emulated OS's separator and removes duplicate and trailing separators. */
    public String normalise(String path) {
        String p = windows ? path.replace('/', '\\') : path;
        if (windows && p.length() >= 3 && p.charAt(0) == separator && p.charAt(2) == ':' && Character.isLetter(p.charAt(1))) {
            // "/C:/dir", the form a file: URL's path takes, is accepted by Windows as "C:\dir".
            p = p.substring(1);
        }
        StringBuilder out = new StringBuilder(p.length());
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            // Keep a leading double separator: on Windows it starts a UNC path.
            boolean duplicate = c == separator && out.length() > (windows ? 1 : 0)
                    && out.charAt(out.length() - 1) == separator;
            if (!duplicate) {
                out.append(c);
            }
        }
        int rootLength = rootLength(out.toString());
        while (out.length() > Math.max(rootLength, 1) && out.charAt(out.length() - 1) == separator) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /** Length of the root prefix: {@code C:\} is 3, {@code /} is 1, a relative path is 0. */
    private int rootLength(String path) {
        if (windows) {
            if (path.length() >= 2 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0))) {
                return path.length() >= 3 && path.charAt(2) == separator ? 3 : 2;
            }
            return path.startsWith("\\\\") ? 2 : path.startsWith("\\") ? 1 : 0;
        }
        return path.startsWith("/") ? 1 : 0;
    }

    public boolean isAbsolute(String path) {
        String p = normalise(path);
        return windows ? rootLength(p) >= 3 || p.startsWith("\\\\") : p.startsWith("/");
    }

    /** Absolute form with {@code .} and {@code ..} resolved; this is the canonical path too. */
    public String absolute(String path) {
        String p = normalise(path);
        if (!isAbsolute(p)) {
            if (windows && p.startsWith("\\")) {
                p = workingDirectory.substring(0, 2) + p; // rooted on the current drive
            } else {
                p = p.isEmpty() ? workingDirectory : join(workingDirectory, p);
            }
        }
        int rootLength = rootLength(p);
        String root = p.substring(0, rootLength);
        List<String> parts = new ArrayList<>();
        for (String part : p.substring(rootLength).split(windows ? "\\\\" : "/")) {
            if (part.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
            } else if (!part.isEmpty() && !part.equals(".")) {
                parts.add(part);
            }
        }
        return root + String.join(String.valueOf(separator), parts);
    }

    public String join(String parent, String child) {
        if (parent.isEmpty()) {
            return normalise(child);
        }
        String p = normalise(parent);
        String c = normalise(child);
        while (c.startsWith(String.valueOf(separator))) {
            c = c.substring(1);
        }
        return p.endsWith(String.valueOf(separator)) ? p + c : p + separator + c;
    }

    /** Last path element, as {@code File.getName()} defines it. */
    public String name(String path) {
        String p = normalise(path);
        int cut = Math.max(p.lastIndexOf(separator), rootLength(p) - 1);
        return p.substring(cut + 1);
    }

    /** Parent path, or null if there is none, as {@code File.getParent()} defines it. */
    public String parent(String path) {
        String p = normalise(path);
        int rootLength = rootLength(p);
        int cut = p.lastIndexOf(separator);
        if (cut < rootLength) {
            return rootLength > 0 && p.length() > rootLength ? p.substring(0, rootLength) : null;
        }
        return p.substring(0, cut);
    }

    private String key(String path) {
        String absolute = absolute(path);
        // Windows file names are case-insensitive; samples rely on that (e.g. %APPDATA% casing).
        return windows ? absolute.toLowerCase(Locale.ROOT) : absolute;
    }

    // ------------------------------------------------------------------ fixtures (no events)

    /** Adds a pre-existing file, creating parent directories. */
    public void addFile(String path, byte[] data) {
        addDirectory(parent(absolute(path)));
        nodes.put(key(path), new Node(absolute(path), false, data, now));
    }

    public void addDirectory(String path) {
        for (String current = path == null ? null : absolute(path); current != null; current = parent(current)) {
            nodes.putIfAbsent(key(current), new Node(current, true, null, now));
        }
    }

    // ------------------------------------------------------------------ guest operations

    public Node node(String path) {
        return nodes.get(key(path));
    }

    public boolean exists(String path) {
        boolean exists = nodes.containsKey(key(path));
        events.addOnce("exists:" + key(path), "file", "check", "path", absolute(path), "exists", exists);
        return exists;
    }

    public boolean isDirectory(String path) {
        Node node = node(path);
        return node != null && node.directory;
    }

    public boolean isFile(String path) {
        Node node = node(path);
        return node != null && !node.directory;
    }

    /** @return the file's bytes, or null if it does not exist or is a directory */
    public byte[] read(String path) {
        Node node = node(path);
        if (node == null || node.directory) {
            events.addOnce("miss:" + key(path), "file", "read-missing", "path", absolute(path));
            return null;
        }
        events.addOnce("read:" + key(path), "file", "read", "path", node.path, "size", node.data.length);
        return node.data;
    }

    /** Creates or replaces a file. Parent directories are created implicitly. */
    public void write(String path, byte[] data) {
        writtenBytes += data.length;
        if (writtenBytes > maxWrittenBytes) {
            throw new dev.javabyte.vm.EmulationAbort(dev.javabyte.vm.EmulationAbort.Reason.POLICY,
                    "sample wrote more than limits.maxWrittenBytes to the virtual file system");
        }
        addDirectory(parent(absolute(path)));
        Node node = new Node(absolute(path), false, data, now);
        node.touchedByGuest = true;
        nodes.put(key(path), node);
        events.add("file", "write", "path", node.path, "size", data.length,
                "sha256", VirtualMachine.sha256(data), "preview", dev.javabyte.vm.Values.preview(data, 96));
    }

    public boolean createFile(String path) {
        if (nodes.containsKey(key(path))) {
            return false;
        }
        write(path, new byte[0]);
        return true;
    }

    public boolean mkdirs(String path) {
        if (nodes.containsKey(key(path))) {
            return false;
        }
        addDirectory(absolute(path));
        nodes.get(key(path)).touchedByGuest = true;
        events.add("file", "mkdir", "path", absolute(path));
        return true;
    }

    public boolean delete(String path) {
        Node removed = nodes.remove(key(path));
        events.add("file", "delete", "path", absolute(path), "existed", removed != null);
        return removed != null;
    }

    public boolean rename(String from, String to) {
        Node source = nodes.remove(key(from));
        if (source == null) {
            return false;
        }
        Node moved = new Node(absolute(to), source.directory, source.data, now);
        moved.touchedByGuest = true;
        addDirectory(parent(absolute(to)));
        nodes.put(key(to), moved);
        events.add("file", "rename", "from", source.path, "to", moved.path);
        return true;
    }

    /** Names of the direct children of a directory, or null if it is not a directory. */
    public List<String> list(String path) {
        if (!isDirectory(path)) {
            return null;
        }
        String prefix = key(path);
        if (!prefix.endsWith(String.valueOf(separator))) {
            prefix += separator;
        }
        List<String> children = new ArrayList<>();
        for (Map.Entry<String, Node> entry : nodes.entrySet()) {
            String candidate = entry.getKey();
            if (candidate.startsWith(prefix) && candidate.length() > prefix.length()
                    && candidate.indexOf(separator, prefix.length()) < 0) {
                children.add(name(entry.getValue().path));
            }
        }
        events.addOnce("list:" + prefix, "file", "list", "path", absolute(path), "entries", children.size());
        return children;
    }

    // ------------------------------------------------------------------ bookkeeping

    void track(SimFileOutputStream stream) {
        openOutputs.add(stream);
    }

    /** Commits streams the guest never closed, so their content still appears in the report. */
    public void commitOpenStreams() {
        new ArrayList<>(openOutputs).forEach(SimFileOutputStream::commit);
        openOutputs.clear();
    }

    void untrack(SimFileOutputStream stream) {
        openOutputs.remove(stream);
    }

    /** Files the guest created or modified. */
    public List<Node> guestFiles() {
        return nodes.values().stream().filter(node -> node.touchedByGuest && !node.directory).toList();
    }

    public void setNow(long now) {
        this.now = now;
    }
}

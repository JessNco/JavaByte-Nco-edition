package dev.javabyte.env;

import java.io.File;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@code java.nio.file.Path} over the {@link VirtualFileSystem}, with the emulated OS's syntax.
 *
 * <p>Only the path-manipulation half of the interface is meaningful. Operations that would need
 * a real file-system provider are unsupported; guest calls to {@code Files} never reach a
 * provider because they are answered by stubs.
 */
public final class SimPath implements Path {

    private final VirtualFileSystem fs;
    private final String path;

    public SimPath(VirtualFileSystem fs, String path) {
        this.fs = fs;
        this.path = fs.normalise(path);
    }

    private SimPath of(String other) {
        return other == null ? null : new SimPath(fs, other);
    }

    private List<String> names() {
        List<String> names = new ArrayList<>();
        String root = rootString();
        for (String part : path.substring(root.length()).split(fs.isWindows() ? "\\\\" : "/")) {
            if (!part.isEmpty()) {
                names.add(part);
            }
        }
        return names;
    }

    private String rootString() {
        String current = path;
        for (String parent = fs.parent(current); parent != null; parent = fs.parent(current)) {
            current = parent;
        }
        return fs.isAbsolute(current) ? current : "";
    }

    @Override public FileSystem getFileSystem() { throw new UnsupportedOperationException("virtual path"); }
    @Override public boolean isAbsolute() { return fs.isAbsolute(path); }
    @Override public Path getRoot() { return rootString().isEmpty() ? null : of(rootString()); }
    @Override public Path getFileName() { return path.isEmpty() || path.equals(rootString()) ? null : of(fs.name(path)); }
    @Override public Path getParent() { return of(fs.parent(path)); }
    @Override public int getNameCount() { return names().size(); }
    @Override public Path getName(int index) { return of(names().get(index)); }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        return of(String.join(String.valueOf(fs.separator()), names().subList(beginIndex, endIndex)));
    }

    @Override public boolean startsWith(Path other) { return fs.absolute(path).startsWith(fs.absolute(other.toString())); }
    @Override public boolean startsWith(String other) { return startsWith(of(other)); }
    @Override public boolean endsWith(Path other) { return path.endsWith(fs.normalise(other.toString())); }
    @Override public boolean endsWith(String other) { return endsWith(of(other)); }
    @Override public Path normalize() { return fs.isAbsolute(path) ? of(fs.absolute(path)) : this; }

    @Override
    public Path resolve(Path other) {
        String child = other.toString();
        return fs.isAbsolute(child) ? of(child) : child.isEmpty() ? this : of(fs.join(path, child));
    }

    @Override public Path resolve(String other) { return resolve(of(other)); }

    @Override
    public Path resolveSibling(String other) {
        String parent = fs.parent(path);
        return parent == null ? of(other) : of(fs.join(parent, other));
    }

    @Override
    public Path relativize(Path other) {
        String base = fs.absolute(path);
        String target = fs.absolute(other.toString());
        if (target.startsWith(base)) {
            String rest = target.substring(base.length());
            return of(rest.startsWith(String.valueOf(fs.separator())) ? rest.substring(1) : rest);
        }
        return other;
    }

    @Override public URI toUri() { return new SimFile(fs, path).toURI(); }
    @Override public Path toAbsolutePath() { return of(fs.absolute(path)); }
    @Override public Path toRealPath(LinkOption... options) { return of(fs.absolute(path)); }
    @Override public File toFile() { return new SimFile(fs, path); }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) {
        throw new UnsupportedOperationException("virtual path");
    }

    @Override public int compareTo(Path other) { return path.compareTo(other.toString()); }
    @Override public boolean equals(Object other) { return other instanceof SimPath that && that.path.equals(path); }
    @Override public int hashCode() { return path.hashCode(); }
    @Override public String toString() { return path; }
}

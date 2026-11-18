package dev.javabyte.env;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@code java.io.File} whose every operation acts on the {@link VirtualFileSystem}.
 *
 * <p>It subclasses the real class so that guest casts and JDK signatures accept it, but
 * overrides every public method, so nothing ever falls through to the real disk. A unit test
 * enforces that no {@code File} method is left un-overridden when the JDK adds new ones.
 */
public final class SimFile extends File {

    private final transient VirtualFileSystem fs;
    private final String path;

    public SimFile(VirtualFileSystem fs, String path) {
        // The superclass needs some path; it is never used because every method is overridden.
        super("javabyte-virtual");
        this.fs = fs;
        this.path = fs.normalise(path);
    }

    private SimFile sibling(String other) {
        return other == null ? null : new SimFile(fs, other);
    }

    @Override public String getName() { return fs.name(path); }
    @Override public String getParent() { return fs.parent(path); }
    @Override public File getParentFile() { return sibling(fs.parent(path)); }
    @Override public String getPath() { return path; }
    @Override public boolean isAbsolute() { return fs.isAbsolute(path); }
    @Override public String getAbsolutePath() { return fs.isAbsolute(path) ? path : fs.join(fs.absolute(""), path); }
    @Override public File getAbsoluteFile() { return sibling(getAbsolutePath()); }
    @Override public String getCanonicalPath() { return fs.absolute(path); }
    @Override public File getCanonicalFile() { return sibling(fs.absolute(path)); }

    @Override
    @SuppressWarnings("deprecation")
    public URL toURL() throws java.net.MalformedURLException {
        return toURI().toURL();
    }

    @Override
    public URI toURI() {
        String absolute = fs.absolute(path).replace('\\', '/');
        try {
            return new URI("file", null, absolute.startsWith("/") ? absolute : '/' + absolute, null);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override public boolean canRead() { return fs.exists(path); }
    @Override public boolean canWrite() { return true; }
    @Override public boolean canExecute() { return fs.exists(path); }
    @Override public boolean exists() { return fs.exists(path); }
    // Like exists(), these go through fs.exists() first so the probe is reported: which files a
    // sample looks for is evidence, whichever of the three methods it happens to use.
    @Override public boolean isDirectory() { return fs.exists(path) && fs.isDirectory(path); }
    @Override public boolean isFile() { return fs.exists(path) && fs.isFile(path); }
    @Override public boolean isHidden() { return getName().startsWith("."); }

    @Override
    public long lastModified() {
        VirtualFileSystem.Node node = fs.node(path);
        return node == null ? 0L : node.modified;
    }

    @Override
    public long length() {
        VirtualFileSystem.Node node = fs.node(path);
        return node == null || node.data == null ? 0L : node.data.length;
    }

    @Override public boolean createNewFile() { return fs.createFile(path); }
    @Override public boolean delete() { return fs.delete(path); }
    @Override public void deleteOnExit() { /* nothing persists past the run anyway */ }
    @Override public boolean mkdir() { return fs.isDirectory(fs.parent(fs.absolute(path))) && fs.mkdirs(path); }
    @Override public boolean mkdirs() { return fs.mkdirs(path); }
    @Override public boolean renameTo(File dest) { return fs.rename(path, dest.getPath()); }

    @Override
    public String[] list() {
        List<String> names = fs.list(path);
        return names == null ? null : names.toArray(String[]::new);
    }

    @Override
    public String[] list(FilenameFilter filter) {
        String[] names = list();
        if (names == null || filter == null) {
            return names;
        }
        List<String> accepted = new ArrayList<>();
        for (String name : names) {
            if (filter.accept(this, name)) {
                accepted.add(name);
            }
        }
        return accepted.toArray(String[]::new);
    }

    @Override
    public File[] listFiles() {
        String[] names = list();
        if (names == null) {
            return null;
        }
        File[] files = new File[names.length];
        for (int i = 0; i < names.length; i++) {
            files[i] = new SimFile(fs, fs.join(path, names[i]));
        }
        return files;
    }

    @Override
    public File[] listFiles(FilenameFilter filter) {
        String[] names = list(filter);
        if (names == null) {
            return null;
        }
        File[] files = new File[names.length];
        for (int i = 0; i < names.length; i++) {
            files[i] = new SimFile(fs, fs.join(path, names[i]));
        }
        return files;
    }

    @Override
    public File[] listFiles(FileFilter filter) {
        File[] files = listFiles();
        if (files == null || filter == null) {
            return files;
        }
        List<File> accepted = new ArrayList<>();
        for (File file : files) {
            if (filter.accept(file)) {
                accepted.add(file);
            }
        }
        return accepted.toArray(File[]::new);
    }

    @Override
    public boolean setLastModified(long time) {
        VirtualFileSystem.Node node = fs.node(path);
        if (node != null) {
            node.modified = time;
        }
        return node != null;
    }

    // Permission and attribute changes are accepted and ignored; the virtual disk has no ACLs.
    @Override public boolean setReadOnly() { return fs.exists(path); }
    @Override public boolean setWritable(boolean writable, boolean ownerOnly) { return fs.exists(path); }
    @Override public boolean setWritable(boolean writable) { return fs.exists(path); }
    @Override public boolean setReadable(boolean readable, boolean ownerOnly) { return fs.exists(path); }
    @Override public boolean setReadable(boolean readable) { return fs.exists(path); }
    @Override public boolean setExecutable(boolean executable, boolean ownerOnly) { return fs.exists(path); }
    @Override public boolean setExecutable(boolean executable) { return fs.exists(path); }

    // Plausible fixed disk figures: 256 GB total, 120 GB free.
    /** The size of the simulated drive, the same figure {@code wmic logicaldisk} reports. */
    @Override public long getTotalSpace() { return MachineProfile.VOLUME_BYTES; }
    /** The free space on the simulated drive. */
    @Override public long getFreeSpace() { return MachineProfile.VOLUME_FREE_BYTES; }
    /** The space this user may use: all the free space, as on a desktop without quotas. */
    @Override public long getUsableSpace() { return MachineProfile.VOLUME_FREE_BYTES; }

    @Override
    public int compareTo(File other) {
        return fs.isWindows() ? path.compareToIgnoreCase(other.getPath()) : path.compareTo(other.getPath());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof File file && compareTo(file) == 0;
    }

    @Override
    public int hashCode() {
        return (fs.isWindows() ? path.toLowerCase(java.util.Locale.ROOT) : path).hashCode() ^ 1234321;
    }

    @Override public String toString() { return path; }
    @Override public Path toPath() { return new SimPath(fs, path); }
}

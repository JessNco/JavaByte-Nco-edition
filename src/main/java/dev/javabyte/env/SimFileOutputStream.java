package dev.javabyte.env;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;

/**
 * A {@code FileOutputStream} that collects what the guest writes and stores it in the
 * {@link VirtualFileSystem} when flushed or closed.
 */
public final class SimFileOutputStream extends FileOutputStream {

    private final VirtualFileSystem fs;
    private final String path;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean dirty = true; // creating the stream creates the file, even if nothing is written

    public SimFileOutputStream(VirtualFileSystem fs, String path, boolean append) {
        super(new FileDescriptor());
        this.fs = fs;
        this.path = path;
        byte[] existing = append && fs.isFile(path) ? fs.node(path).data : null;
        if (existing != null) {
            buffer.writeBytes(existing);
        }
        fs.track(this);
    }

    @Override
    public void write(int b) {
        buffer.write(b);
        dirty = true;
    }

    @Override
    public void write(byte[] b) {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) {
        buffer.write(b, off, len);
        dirty = true;
    }

    /**
     * Flushing does not commit: programs flush after every small write, and committing each
     * time would report one dropped file as hundreds of partial writes.
     */
    @Override
    public void flush() {
    }

    @Override
    public void close() {
        commit();
        fs.untrack(this);
    }

    void commit() {
        if (dirty) {
            fs.write(path, buffer.toByteArray());
            dirty = false;
        }
    }

    @Override
    public FileChannel getChannel() {
        throw new UnsupportedOperationException("virtual file has no channel");
    }
}

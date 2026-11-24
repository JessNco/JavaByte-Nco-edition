package dev.javabyte.env;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;

/**
 * A {@code FileOutputStream} that collects what the guest writes and stores it in the
 * {@link VirtualFileSystem} when the stream is closed, or at the end of the run via
 * {@link VirtualFileSystem#commitOpenStreams()} for streams the guest never closed. Flushing does
 * not commit.
 */
public final class SimFileOutputStream extends FileOutputStream {

    private final VirtualFileSystem fs;
    private final String path;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean dirty = true; // creating the stream creates the file, even if nothing is written

    /**
     * Opens a virtual file for writing; as on a real disk, the file exists (empty, unless
     * appending) from this moment.
     */
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
        fs.checkBuffered(buffer.size() + 1L);
        buffer.write(b);
        dirty = true;
    }

    @Override
    public void write(byte[] b) {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) {
        fs.checkBuffered((long) buffer.size() + len);
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

    /**
     * Writes what has been buffered to the virtual disk, if anything changed. Called when the
     * stream is closed, and for every stream still open when the run ends.
     */
    void commit() {
        if (dirty) {
            fs.write(path, buffer.toByteArray());
            dirty = false;
        }
    }

    @Override
    public FileChannel getChannel() {
        return new SimFileChannel(new SimFileChannel.Storage() {
            @Override
            public byte[] bytes() {
                return buffer.toByteArray();
            }

            @Override
            public void checkSize(long size) {
                fs.checkBuffered(size);
            }

            @Override
            public void replace(byte[] bytes) {
                buffer.reset();
                buffer.writeBytes(bytes);
                dirty = true;
            }
        }, buffer.size());
    }
}

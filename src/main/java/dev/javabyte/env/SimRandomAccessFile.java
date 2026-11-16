package dev.javabyte.env;

import dev.javabyte.host.StandIn;
import java.io.EOFException;
import java.util.Arrays;

/**
 * Imitates {@code java.io.RandomAccessFile} over a virtual file. The real class has no
 * constructor that avoids opening a file, so this is a look-alike rather than a subclass.
 */
public final class SimRandomAccessFile implements StandIn {

    private final VirtualFileSystem fs;
    private final String path;
    private byte[] data;
    private int position;
    private boolean dirty;

    public SimRandomAccessFile(VirtualFileSystem fs, String path, byte[] initial) {
        this.fs = fs;
        this.path = path;
        this.data = initial;
    }

    @Override
    public String standsFor() {
        return "java/io/RandomAccessFile";
    }

    public int read() {
        return position < data.length ? data[position++] & 0xFF : -1;
    }

    public int read(byte[] b) {
        return read(b, 0, b.length);
    }

    public int read(byte[] b, int off, int len) {
        if (position >= data.length) {
            return len == 0 ? 0 : -1;
        }
        int count = Math.min(len, data.length - position);
        System.arraycopy(data, position, b, off, count);
        position += count;
        return count;
    }

    public void readFully(byte[] b) throws EOFException {
        readFully(b, 0, b.length);
    }

    public void readFully(byte[] b, int off, int len) throws EOFException {
        if (data.length - position < len) {
            throw new EOFException();
        }
        read(b, off, len);
    }

    public void write(int b) {
        write(new byte[] {(byte) b}, 0, 1);
    }

    public void write(byte[] b) {
        write(b, 0, b.length);
    }

    public void write(byte[] b, int off, int len) {
        if (position + len > data.length) {
            data = Arrays.copyOf(data, position + len);
        }
        System.arraycopy(b, off, data, position, len);
        position += len;
        dirty = true;
    }

    public void seek(long pos) {
        position = (int) pos;
    }

    public long getFilePointer() {
        return position;
    }

    public long length() {
        return data.length;
    }

    public void setLength(long newLength) {
        data = Arrays.copyOf(data, (int) newLength);
        position = Math.min(position, data.length);
        dirty = true;
    }

    public int skipBytes(int n) {
        int skipped = Math.max(0, Math.min(n, data.length - position));
        position += skipped;
        return skipped;
    }

    public java.nio.channels.FileChannel getChannel() {
        return new SimFileChannel(new SimFileChannel.Storage() {
            @Override
            public byte[] bytes() {
                return data;
            }

            @Override
            public void replace(byte[] bytes) {
                data = bytes;
                dirty = true;
            }
        }, position);
    }

    public void close() {
        if (dirty) {
            fs.write(path, data);
            dirty = false;
        }
    }
}

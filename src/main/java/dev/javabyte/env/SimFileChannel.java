package dev.javabyte.env;

import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;

/**
 * A {@code FileChannel} over a virtual file.
 *
 * <p>Samples mostly reach for a channel to take a file lock, as a "single instance" guard. A
 * lock request always succeeds here: the virtual disk has exactly one user.
 */
public final class SimFileChannel extends FileChannel {

    /** Where the channel's bytes live; implemented by the stream or file that owns the channel. */
    public interface Storage {
        byte[] bytes();

        /** Replaces the whole content. Throws {@link NonWritableChannelException} if read-only. */
        void replace(byte[] bytes);

        /** Called before the file grows, so the owner can refuse a size over the write limit. */
        void checkSize(long size);
    }

    private final Storage storage;
    private long position;

    public SimFileChannel(Storage storage, long initialPosition) {
        this.storage = storage;
        this.position = initialPosition;
    }

    @Override
    public int read(ByteBuffer dst) {
        int count = read(dst, position);
        if (count > 0) {
            position += count;
        }
        return count;
    }

    @Override
    public int read(ByteBuffer dst, long at) {
        byte[] data = storage.bytes();
        if (at >= data.length) {
            return -1;
        }
        int count = (int) Math.min(dst.remaining(), data.length - at);
        dst.put(data, (int) at, count);
        return count;
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) {
        long total = 0;
        for (int i = offset; i < offset + length; i++) {
            int count = read(dsts[i]);
            if (count < 0) {
                return total == 0 ? -1 : total;
            }
            total += count;
        }
        return total;
    }

    @Override
    public int write(ByteBuffer src) {
        int count = write(src, position);
        position += count;
        return count;
    }

    @Override
    public int write(ByteBuffer src, long at) {
        int count = src.remaining();
        byte[] data = storage.bytes();
        storage.checkSize(at + count);
        byte[] grown = at + count > data.length ? Arrays.copyOf(data, (int) at + count) : data.clone();
        src.get(grown, (int) at, count);
        storage.replace(grown);
        return count;
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) {
        long total = 0;
        for (int i = offset; i < offset + length; i++) {
            total += write(srcs[i]);
        }
        return total;
    }

    @Override public long position() { return position; }

    @Override
    public FileChannel position(long newPosition) {
        position = newPosition;
        return this;
    }

    @Override public long size() { return storage.bytes().length; }

    @Override
    public FileChannel truncate(long size) {
        if (size < storage.bytes().length) {
            storage.replace(Arrays.copyOf(storage.bytes(), (int) size));
        }
        position = Math.min(position, size);
        return this;
    }

    @Override public void force(boolean metaData) { }

    @Override
    public long transferTo(long at, long count, WritableByteChannel target) throws java.io.IOException {
        byte[] data = storage.bytes();
        int length = (int) Math.max(0, Math.min(count, data.length - at));
        return length == 0 ? 0 : target.write(ByteBuffer.wrap(data, (int) at, length));
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long at, long count) throws java.io.IOException {
        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(count, 1 << 20));
        int read = src.read(buffer);
        if (read <= 0) {
            return 0;
        }
        buffer.flip();
        return write(buffer, at);
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long at, long size) {
        throw new UnsupportedOperationException("memory-mapping a virtual file");
    }

    @Override public FileLock lock(long at, long size, boolean shared) { return new Lock(this, at, size, shared); }
    @Override public FileLock tryLock(long at, long size, boolean shared) { return new Lock(this, at, size, shared); }
    @Override protected void implCloseChannel() { }

    /** A lock that is always granted and valid until released. */
    private static final class Lock extends FileLock {
        private boolean valid = true;

        Lock(FileChannel channel, long position, long size, boolean shared) {
            super(channel, position, size, shared);
        }

        @Override public boolean isValid() { return valid; }
        @Override public void release() { valid = false; }
    }
}

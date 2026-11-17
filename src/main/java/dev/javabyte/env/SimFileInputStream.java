package dev.javabyte.env;

import java.io.ByteArrayInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;

/**
 * A {@code FileInputStream} that reads a virtual file's bytes from memory.
 *
 * <p>The superclass is constructed on an invalid descriptor, which opens nothing, and every
 * method that would use the descriptor is overridden.
 */
public final class SimFileInputStream extends FileInputStream {

    private final ByteArrayInputStream in;
    private final byte[] data;

    public SimFileInputStream(byte[] data) {
        super(new FileDescriptor());
        this.data = data;
        this.in = new ByteArrayInputStream(data);
    }

    @Override public int read() { return in.read(); }
    @Override public int read(byte[] b) { return in.read(b, 0, b.length); }
    @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
    @Override public byte[] readAllBytes() { return in.readAllBytes(); }
    @Override public byte[] readNBytes(int len) throws IOException { return in.readNBytes(len); }
    @Override public int readNBytes(byte[] b, int off, int len) { return in.readNBytes(b, off, len); }
    @Override public long transferTo(OutputStream out) throws IOException { return in.transferTo(out); }
    @Override public long skip(long n) { return in.skip(n); }
    @Override public int available() { return in.available(); }
    @Override public void close() { /* nothing to release */ }

    @Override
    public FileChannel getChannel() {
        return new SimFileChannel(new SimFileChannel.Storage() {
            @Override
            public byte[] bytes() {
                return data;
            }

            @Override
            public void replace(byte[] bytes) {
                throw new java.nio.channels.NonWritableChannelException();
            }

            @Override
            public void checkSize(long size) {
                throw new java.nio.channels.NonWritableChannelException();
            }
        }, data.length - in.available());
    }
}

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
        if ((long) position + len > data.length) {
            fs.checkBuffered((long) position + len);
            data = Arrays.copyOf(data, position + len);
        }
        System.arraycopy(b, off, data, position, len);
        position += len;
        dirty = true;
    }

    // ------------------------------------------------------------------ DataInput / DataOutput
    //
    // RandomAccessFile also reads and writes Java's binary formats. The JDK's data streams
    // already implement every one of them, so each method here borrows a stream over this
    // file's bytes at the current position instead of repeating the encodings.

    private java.io.DataInputStream dataIn() {
        return new java.io.DataInputStream(new java.io.InputStream() {
            @Override
            public int read() {
                return SimRandomAccessFile.this.read();
            }
        });
    }

    private interface DataWrite {
        void to(java.io.DataOutputStream out) throws java.io.IOException;
    }

    private void data(DataWrite write) throws java.io.IOException {
        java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
        write.to(new java.io.DataOutputStream(encoded));
        write(encoded.toByteArray());
    }

    public boolean readBoolean() throws java.io.IOException { return dataIn().readBoolean(); }
    public byte readByte() throws java.io.IOException { return dataIn().readByte(); }
    public int readUnsignedByte() throws java.io.IOException { return dataIn().readUnsignedByte(); }
    public short readShort() throws java.io.IOException { return dataIn().readShort(); }
    public int readUnsignedShort() throws java.io.IOException { return dataIn().readUnsignedShort(); }
    public char readChar() throws java.io.IOException { return dataIn().readChar(); }
    public int readInt() throws java.io.IOException { return dataIn().readInt(); }
    public long readLong() throws java.io.IOException { return dataIn().readLong(); }
    public float readFloat() throws java.io.IOException { return dataIn().readFloat(); }
    public double readDouble() throws java.io.IOException { return dataIn().readDouble(); }
    public String readUTF() throws java.io.IOException { return dataIn().readUTF(); }

    /** Reads up to a line terminator, treating bytes as Latin-1 characters like the real class. */
    public String readLine() {
        if (position >= data.length) {
            return null;
        }
        StringBuilder line = new StringBuilder();
        while (position < data.length) {
            char c = (char) (data[position++] & 0xFF);
            if (c == '\n') {
                break;
            }
            if (c == '\r') {
                if (position < data.length && data[position] == '\n') {
                    position++;
                }
                break;
            }
            line.append(c);
        }
        return line.toString();
    }

    public void writeBoolean(boolean v) throws java.io.IOException { data(out -> out.writeBoolean(v)); }
    public void writeByte(int v) throws java.io.IOException { data(out -> out.writeByte(v)); }
    public void writeShort(int v) throws java.io.IOException { data(out -> out.writeShort(v)); }
    public void writeChar(int v) throws java.io.IOException { data(out -> out.writeChar(v)); }
    public void writeInt(int v) throws java.io.IOException { data(out -> out.writeInt(v)); }
    public void writeLong(long v) throws java.io.IOException { data(out -> out.writeLong(v)); }
    public void writeFloat(float v) throws java.io.IOException { data(out -> out.writeFloat(v)); }
    public void writeDouble(double v) throws java.io.IOException { data(out -> out.writeDouble(v)); }
    public void writeBytes(String s) throws java.io.IOException { data(out -> out.writeBytes(s)); }
    public void writeChars(String s) throws java.io.IOException { data(out -> out.writeChars(s)); }
    public void writeUTF(String s) throws java.io.IOException { data(out -> out.writeUTF(s)); }

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
        // Samples pre-allocate huge files to fill a disk; the limit turns that into an outcome.
        fs.checkBuffered(newLength);
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
            public void checkSize(long size) {
                fs.checkBuffered(size);
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

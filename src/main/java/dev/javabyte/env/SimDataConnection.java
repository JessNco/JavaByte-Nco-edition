package dev.javabyte.env;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

/** Connection for {@code file:} and {@code jar:} URLs, serving bytes the emulator already holds. */
public final class SimDataConnection extends URLConnection {

    private final byte[] data;

    public SimDataConnection(URL url, byte[] data) {
        super(url);
        this.data = data;
    }

    @Override public void connect() { connected = true; }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(data); }
    @Override public int getContentLength() { return data.length; }
    @Override public long getContentLengthLong() { return data.length; }
}

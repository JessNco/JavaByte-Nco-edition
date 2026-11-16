package dev.javabyte.env;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The result of a process launch that never happened: canned output and an exit code.
 * Whatever the guest writes to the "process" is collected so it can be reported.
 */
public final class SimProcess extends Process {

    private final InputStream stdout;
    private final InputStream stderr;
    private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
    private final int exitCode;

    public SimProcess(String stdout, String stderr, int exitCode) {
        this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
        this.stderr = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
        this.exitCode = exitCode;
    }

    @Override public OutputStream getOutputStream() { return stdin; }
    @Override public InputStream getInputStream() { return stdout; }
    @Override public InputStream getErrorStream() { return stderr; }
    @Override public int waitFor() { return exitCode; }
    @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
    @Override public int exitValue() { return exitCode; }
    @Override public void destroy() { }
    @Override public Process destroyForcibly() { return this; }
    @Override public boolean isAlive() { return false; }
    @Override public long pid() { return 4242L; }
    @Override public boolean supportsNormalTermination() { return true; }

    // The remaining Process methods expose the host's real process table and must not leak it.
    @Override public ProcessHandle toHandle() { throw new UnsupportedOperationException("simulated process"); }
    @Override public ProcessHandle.Info info() { throw new UnsupportedOperationException("simulated process"); }
    @Override public java.util.stream.Stream<ProcessHandle> children() { return java.util.stream.Stream.empty(); }
    @Override public java.util.stream.Stream<ProcessHandle> descendants() { return java.util.stream.Stream.empty(); }
    @Override public java.util.concurrent.CompletableFuture<Process> onExit() {
        return java.util.concurrent.CompletableFuture.completedFuture(this);
    }
}

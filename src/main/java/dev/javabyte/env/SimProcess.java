package dev.javabyte.env;

import dev.javabyte.report.Artifacts;
import dev.javabyte.report.EventLog;
import dev.javabyte.vm.Values;
import dev.javabyte.vm.VirtualMachine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The result of a process launch that never happened: canned output and an exit code.
 *
 * <p>Droppers often start a shell ({@code cmd.exe}, {@code /bin/sh}) and then write the real
 * commands to its standard input. When the process is made with an event log, what the guest
 * writes there is reported as {@code process.stdin} events, with the command line, a preview and
 * the bytes saved as an artifact. A chunk is reported when the guest flushes or closes the
 * stream, or when it outgrows the 8 KiB a real process pipe buffers; {@link #reportInput()}
 * reports whatever is left at the end of the run. Without an event log the input is discarded.
 */
public final class SimProcess extends Process {

    /** What a real {@code Process} buffers before its output stream writes through to the child. */
    static final int PIPE_BUFFER = 8192;

    private final InputStream stdout;
    private final InputStream stderr;
    private final InputSink stdin;
    private final int exitCode;

    /**
     * A process whose standard input is discarded. Kept for callers that have no event log;
     * prefer the reporting constructor.
     */
    public SimProcess(String stdout, String stderr, int exitCode) {
        this(stdout, stderr, exitCode, null, null, null);
    }

    /**
     * A process whose standard input is reported.
     *
     * @param command   the command line, named in each {@code process.stdin} event
     * @param events    where input is reported; null discards it
     * @param artifacts where the input bytes are saved; null saves nothing
     */
    public SimProcess(String stdout, String stderr, int exitCode, String command, EventLog events, Artifacts artifacts) {
        this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
        this.stderr = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
        this.exitCode = exitCode;
        this.stdin = new InputSink(command, events, artifacts);
    }

    /**
     * Reports what the guest wrote to standard input and has not been reported yet. Called at
     * the end of the run for processes whose input was never flushed or closed, the same way
     * {@link NetworkSimulator#reportOpenSockets()} treats sockets.
     */
    public void reportInput() {
        stdin.report();
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

    /**
     * The process's standard input. It collects bytes and reports each chunk once, so a guest
     * that flushes after every line gives one event per line rather than one per byte.
     */
    private static final class InputSink extends OutputStream {
        private final String command;
        private final EventLog events;
        private final Artifacts artifacts;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        InputSink(String command, EventLog events, Artifacts artifacts) {
            this.command = command;
            this.events = events;
            this.artifacts = artifacts;
        }

        @Override
        public synchronized void write(int b) {
            if (events == null) {
                return;
            }
            pending.write(b);
            reportIfFull();
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            if (events == null) {
                return;
            }
            pending.write(b, off, len);
            reportIfFull();
        }

        private void reportIfFull() {
            if (pending.size() >= PIPE_BUFFER) {
                report();
            }
        }

        @Override
        public void flush() {
            report();
        }

        @Override
        public void close() {
            report();
        }

        /** Emits one event for the bytes collected since the last one, if there are any. */
        synchronized void report() {
            if (events == null || pending.size() == 0) {
                return;
            }
            byte[] data = pending.toByteArray();
            pending.reset();
            // Named by content, so the same input gives the same file name in every run.
            String file = artifacts == null ? null
                    : artifacts.save("processes", "stdin-" + VirtualMachine.sha256(data).substring(0, 16) + ".bin", data);
            events.add("process", "stdin", "command", command, "size", data.length,
                    "preview", Values.preview(data, 200), "file", file);
        }
    }
}

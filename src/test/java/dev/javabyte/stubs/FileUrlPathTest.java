package dev.javabyte.stubs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.host.CallContext;
import dev.javabyte.report.Artifacts;
import dev.javabyte.report.EventLog;
import dev.javabyte.vm.VirtualMachine;
import java.net.URL;
import java.net.URLConnection;
import org.junit.jupiter.api.Test;

/** The one conversion from a {@code file:} URL to a virtual path, shared by every stub that takes one. */
class FileUrlPathTest {

    @Test
    void escapesAreDecodedAsUriDoes() {
        assertEquals("C:/a+b.jar", FileStubs.pathFromFileUrl("/C%3A/a+b.jar", true), "a plus sign is not a space in a URL path");
        assertEquals("C:/my files/x.jar", FileStubs.pathFromFileUrl("/C:/my%20files/x.jar", true));
        assertEquals("/home/user/caf\u00e9.jar", FileStubs.pathFromFileUrl("/home/user/caf%C3%A9.jar", false));
        assertEquals("/tmp/100%/x", FileStubs.pathFromFileUrl("/tmp/100%/x", false), "an incomplete escape is kept");
        assertEquals("/tmp/a%zz", FileStubs.pathFromFileUrl("/tmp/a%zz", false));
    }

    @Test
    void theDriveLetterSlashIsDroppedOnlyOnWindows() {
        assertEquals("C:/Temp/x", FileStubs.pathFromFileUrl("/C:/Temp/x", true));
        assertEquals("/C:/Temp/x", FileStubs.pathFromFileUrl("/C:/Temp/x", false));
        assertEquals("/Temp/x", FileStubs.pathFromFileUrl("/Temp/x", true));
    }

    @Test
    void urlOpenReadsTheSameFileOnAWindowsProfile() throws Exception {
        EmulatorConfig config = new EmulatorConfig();
        config.environment.os = "windows";
        VirtualMachine vm = new VirtualMachine(config, new EventLog(100, null), new Artifacts(null));
        BuiltinStubs.install(vm);
        vm.env.files.write("C:\\Users\\a+b dir\\x.txt", new byte[] {7});
        URL url = new URL("file:/C%3A/Users/a+b%20dir/x.txt");
        String desc = "()Ljava/net/URLConnection;";
        URLConnection connection = (URLConnection) vm.stubs.find("java/net/URL", "openConnection", desc, vm.host::findClass)
                .call(new CallContext(vm, "java/net/URL", "openConnection", desc, url, new Object[0], false));
        assertArrayEquals(new byte[] {7}, connection.getInputStream().readAllBytes());
    }
}

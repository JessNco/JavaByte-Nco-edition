package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.env.SimFile;
import dev.javabyte.env.SimSocket;
import dev.javabyte.env.VirtualFileSystem;
import dev.javabyte.host.HostPolicy;
import dev.javabyte.report.EventLog;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

/**
 * Guards on the properties the emulator's safety depends on. These are structural checks, so
 * they also catch regressions introduced by a newer JDK (for example a new {@code File} method
 * that the simulated file does not yet override).
 */
class SafetyTest {

    /** Public instance methods of {@code base} that {@code sim} inherits instead of overriding. */
    private static List<String> notOverridden(Class<?> base, Class<?> sim) {
        List<String> missing = new ArrayList<>();
        for (Method method : base.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                continue;
            }
            try {
                sim.getDeclaredMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException e) {
                missing.add(method.getName() + Type.getMethodDescriptor(method));
            }
        }
        return missing;
    }

    @Test
    void simulatedFileOverridesEveryFileMethod() {
        assertEquals(List.of(), notOverridden(java.io.File.class, SimFile.class),
                "an inherited File method would act on the real disk");
    }

    @Test
    void simulatedSocketOverridesEverySocketMethod() {
        assertEquals(List.of(), notOverridden(java.net.Socket.class, SimSocket.class),
                "an inherited Socket method could open a real connection");
    }

    @Test
    void simulatedTlsSocketOverridesEverySocketMethod() {
        assertEquals(List.of(), notOverridden(java.net.Socket.class, dev.javabyte.env.SimSslSocket.class),
                "an inherited Socket method could open a real connection");
        assertEquals(List.of(), notOverridden(javax.net.ssl.SSLSocket.class, dev.javabyte.env.SimSslSocket.class));
    }

    @Test
    void defaultPolicyKeepsDangerousMembersOut() {
        HostPolicy policy = HostPolicy.defaults();
        assertTrue(policy.allows("java/lang/String", "length", "()I"));
        assertTrue(policy.allows("java/util/ArrayList", "<init>", "()V"));
        assertTrue(policy.allows("java/lang/Thread", "getName", "()Ljava/lang/String;"));
        assertTrue(policy.allows("java/io/PrintStream", "<init>", "(Ljava/io/OutputStream;)V"));

        assertFalse(policy.allows("java/lang/Runtime", "exec", "(Ljava/lang/String;)Ljava/lang/Process;"));
        assertFalse(policy.allows("java/lang/System", "exit", "(I)V"));
        assertFalse(policy.allows("java/lang/Thread", "start", "()V"));
        assertFalse(policy.allows("java/lang/ProcessBuilder", "start", "()Ljava/lang/Process;"));
        assertFalse(policy.allows("java/lang/ClassLoader", "defineClass", "(Ljava/lang/String;[BII)Ljava/lang/Class;"));
        assertFalse(policy.allows("java/io/FileOutputStream", "<init>", "(Ljava/lang/String;)V"));
        assertFalse(policy.allows("java/io/PrintStream", "<init>", "(Ljava/lang/String;)V"));
        assertFalse(policy.allows("java/net/Socket", "<init>", "(Ljava/lang/String;I)V"));
        assertFalse(policy.allows("java/net/URL", "openConnection", "()Ljava/net/URLConnection;"));
        assertFalse(policy.allows("java/util/Timer", "<init>", "()V"));
        assertFalse(policy.allows("java/nio/file/Files", "write", null));
        assertFalse(policy.allows("java/lang/reflect/Method", "invoke", null));
        assertFalse(policy.allows("sun/misc/Unsafe", "allocateMemory", "(J)J"));
        assertFalse(policy.allows("dev/javabyte/vm/VirtualMachine", "cancel", "()V"), "emulator internals are off-limits");
    }

    @Test
    void userPolicyRulesAreApplied() {
        HostPolicy policy = HostPolicy.defaults();
        policy.deny("java/util/Random");
        policy.allow("java/awt/Point");
        assertFalse(policy.allows("java/util/Random", "nextInt", "()I"));
        assertTrue(policy.allows("java/awt/Point", "<init>", "()V"));
    }

    @Test
    void virtualFileSystemUsesWindowsPathRules() {
        VirtualFileSystem fs = new VirtualFileSystem(true, new EventLog(100, null), 1 << 20, 0);
        fs.setWorkingDirectory("C:\\Users\\admin\\Downloads");
        assertEquals("C:\\Users\\admin\\Downloads\\a.txt", fs.absolute("a.txt"));
        assertEquals("C:\\Users\\admin\\b.txt", fs.absolute("..\\b.txt"));
        assertEquals("C:\\Temp\\x", fs.absolute("C:/Temp//x/"));
        assertEquals("C:\\Temp\\x", fs.absolute("/C:/Temp/x"), "URL-style path with a leading slash");
        assertEquals("x.bin", fs.name("C:\\Temp\\x.bin"));
        assertEquals("C:\\Temp", fs.parent("C:\\Temp\\x.bin"));
        assertEquals("C:\\", fs.parent("C:\\Temp"));
        assertNull(fs.parent("C:\\"));
        assertNull(fs.parent("relative.txt"));

        fs.write("C:\\Temp\\Dir\\File.TXT", new byte[] {1, 2, 3});
        assertTrue(fs.isFile("c:\\temp\\dir\\file.txt"), "Windows names are case-insensitive");
        assertTrue(fs.isDirectory("C:\\Temp\\Dir"), "parents are created implicitly");
        assertEquals(List.of("File.TXT"), fs.list("C:\\Temp\\Dir"));
        assertTrue(fs.delete("C:\\Temp\\Dir\\File.TXT"));
        assertNull(fs.read("C:\\Temp\\Dir\\File.TXT"));
    }

    @Test
    void virtualFileSystemUsesUnixPathRules() {
        VirtualFileSystem fs = new VirtualFileSystem(false, new EventLog(100, null), 1 << 20, 0);
        fs.setWorkingDirectory("/home/user");
        assertEquals("/home/user/a", fs.absolute("a"));
        assertEquals("/etc/passwd", fs.absolute("/etc/./x/../passwd"));
        fs.write("/tmp/A", new byte[0]);
        assertFalse(fs.isFile("/tmp/a"), "Unix names are case-sensitive");
    }
}

package dev.javabyte.guest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;

/**
 * Red-team fixture: tries to break out of the emulator by every route we could think of.
 *
 * <p>Arguments: a real path to create (the canary), a real file holding a secret token, and a
 * real TCP port on localhost that the test is listening on. Success for the emulator means the
 * canary is never created, the token is never read, and the port is never contacted.
 *
 * <p>This class is only ever interpreted. Do not run it on a real JVM.
 */
public final class Escape {

    private interface Attempt {
        void run() throws Throwable;
    }

    private static String canary;
    private static String secret;
    private static int port;

    public static void main(String[] args) {
        canary = args[0];
        secret = args[1];
        port = Integer.parseInt(args[2]);

        // ---- writing the canary to the real disk
        // A subclass's super(...) call runs the JDK constructor itself; it must be judged like "new".
        attempt("subclass of PrintStream(String)", () -> new NamedPrintStream(canary).append("x").close());
        attempt("subclass of PrintWriter(File)", () -> new FilePrintWriter(new File(canary)).append("x").close());
        attempt("FileOutputStream", () -> write(new FileOutputStream(canary)));
        attempt("FileOutputStream(File)", () -> write(new FileOutputStream(new File(canary))));
        attempt("FileWriter", () -> new FileWriter(canary).append("x").close());
        attempt("PrintWriter(String)", () -> new PrintWriter(canary).append("x").close());
        attempt("PrintStream(String)", () -> new PrintStream(canary).append("x").close());
        attempt("PrintStream(File)", () -> new PrintStream(new File(canary)).append("x").close());
        attempt("RandomAccessFile", () -> new RandomAccessFile(canary, "rw").write(1));
        attempt("Files.write", () -> Files.write(Paths.get(canary), new byte[] {1}));
        attempt("Files.newOutputStream", () -> write(Files.newOutputStream(Path.of(canary))));
        attempt("Files.createFile", () -> Files.createFile(Path.of(canary)));
        attempt("File.createNewFile", () -> new File(canary).createNewFile());
        attempt("File.mkdirs", () -> new File(canary).mkdirs());
        attempt("Formatter(String)", () -> new java.util.Formatter(canary).format("x").close());
        attempt("Formatter(File)", () -> new java.util.Formatter(new File(canary)).format("x").close());
        attempt("FileChannel.open", () -> java.nio.channels.FileChannel.open(Path.of(canary),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).close());
        attempt("FileSystems", () -> Files.write(java.nio.file.FileSystems.getDefault().getPath(canary), new byte[] {1}));
        attempt("ZipOutputStream", () -> new java.util.zip.ZipOutputStream(new FileOutputStream(canary)).close());
        attempt("ImageIO", () -> javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1, 1), "png", new File(canary)));
        attempt("Preferences", () -> java.util.prefs.Preferences.userRoot().put("javabyte.escape", "x"));
        attempt("FileHandler", () -> new java.util.logging.FileHandler(canary).close());

        // ---- reading the secret from the real disk
        attempt("FileInputStream", () -> leak(new String(new java.io.FileInputStream(secret).readAllBytes())));
        attempt("Files.readAllBytes", () -> leak(new String(Files.readAllBytes(Paths.get(secret)))));
        attempt("Files.readString", () -> leak(Files.readString(Path.of(secret))));
        attempt("Scanner(File)", () -> leak(new Scanner(new File(secret)).nextLine()));
        attempt("Scanner(Path)", () -> leak(new Scanner(Path.of(secret)).nextLine()));
        attempt("FileReader", () -> leak(new java.io.BufferedReader(new java.io.FileReader(secret)).readLine()));
        attempt("URL file:", () -> leak(new String(new URL("file:" + secret).openStream().readAllBytes())));
        attempt("ZipFile", () -> leak(new java.util.zip.ZipFile(secret).getName()));
        attempt("Properties", () -> {
            java.util.Properties properties = new java.util.Properties();
            properties.load(new java.io.FileReader(secret));
            leak(properties.toString());
        });
        attempt("KeyStore(File)", () -> java.security.KeyStore.getInstance(new File(secret), new char[0]));
        attempt("URLClassLoader", () -> new URLClassLoader(new URL[] {new File(secret).toURI().toURL()}).loadClass("x.Y"));
        attempt("real environment", () -> leak(String.valueOf(System.getenv())));
        attempt("ProcessBuilder env", () -> leak(String.valueOf(new ProcessBuilder("x").environment())));

        // ---- launching a process that would create the canary
        attempt("Runtime.exec", () -> Runtime.getRuntime().exec(new String[] {"touch", canary}).waitFor());
        attempt("ProcessBuilder", () -> new ProcessBuilder("touch", canary).start().waitFor());
        attempt("startPipeline", () -> ProcessBuilder.startPipeline(List.of(new ProcessBuilder("touch", canary))));
        attempt("exec via reflection", () -> {
            Method exec = Runtime.class.getMethod("exec", String.class);
            exec.invoke(Runtime.getRuntime(), "touch " + canary);
        });
        attempt("exec via Method.invoke of invoke", () -> {
            Method exec = Runtime.class.getMethod("exec", String.class);
            Method invoke = Method.class.getMethod("invoke", Object.class, Object[].class);
            invoke.invoke(exec, Runtime.getRuntime(), new Object[] {"touch " + canary});
        });
        attempt("exec via method handle", () -> {
            MethodHandle exec = MethodHandles.lookup().findVirtual(Runtime.class, "exec",
                    MethodType.methodType(Process.class, String.class));
            Process unused = (Process) exec.bindTo(Runtime.getRuntime()).invoke("touch " + canary);
        });
        attempt("exec via privateLookupIn", () -> {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Runtime.class, MethodHandles.lookup());
            lookup.findVirtual(Runtime.class, "exec", MethodType.methodType(Process.class, String.class))
                    .invokeWithArguments(Runtime.getRuntime(), "touch " + canary);
        });
        attempt("ProcessHandle", () -> leak(String.valueOf(ProcessHandle.current().info().commandLine())));
        attempt("ScriptEngine", () -> new javax.script.ScriptEngineManager().getEngineByName("js").eval("1"));
        attempt("System.load", () -> System.load("/lib/x86_64-linux-gnu/libc.so.6"));
        attempt("Desktop", () -> java.awt.Desktop.getDesktop().open(new File(canary)));

        // ---- contacting the real listener
        attempt("Socket", () -> new Socket("127.0.0.1", port).getOutputStream().write(1));
        attempt("Socket.connect", () -> {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port));
            socket.getOutputStream().write(1);
        });
        attempt("SocketChannel", () -> SocketChannel.open(new InetSocketAddress("127.0.0.1", port)).close());
        attempt("URL http", () -> new URL("http://127.0.0.1:" + port + "/x").openStream().close());
        attempt("URLConnection", () -> new URL("http://127.0.0.1:" + port + "/x").openConnection().getInputStream().close());
        attempt("HttpClient", () -> java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/")).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding()));
        attempt("SSLSocketFactory", () -> javax.net.ssl.SSLSocketFactory.getDefault().createSocket("127.0.0.1", port).close());
        attempt("DatagramSocket", () -> new java.net.DatagramSocket().send(
                new java.net.DatagramPacket(new byte[1], 1, new InetSocketAddress("127.0.0.1", port))));
        attempt("JNDI", () -> new javax.naming.InitialContext().lookup("ldap://127.0.0.1:" + port + "/x"));
        attempt("RMI", () -> java.rmi.Naming.lookup("rmi://127.0.0.1:" + port + "/x"));
        attempt("URL.equals DNS", () -> new URL("http://javabyte-escape.invalid/").equals(new URL("http://other.invalid/")));
        attempt("InetAddress", () -> java.net.InetAddress.getByName("javabyte-escape.invalid").isReachable(10));
        attempt("deserialise URL gadget", () -> {
            // The classic "URLDNS" chain: a HashMap keyed by a URL resolves the host when read.
            HashMap<Object, Object> map = new HashMap<>();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                map.put(new URL("http://javabyte-gadget.invalid/"), "x");
                out.writeObject(map);
            }
            new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())).readObject();
        });
        // Stores and providers that belong to the machine: the user's certificates and keys.
        attempt("Windows key store", () -> java.security.KeyStore.getInstance("Windows-MY").load(null, null));
        attempt("key pair in a machine provider", () -> java.security.KeyPairGenerator.getInstance("RSA", "SunMSCAPI"));
        attempt("URL in HashSet", () -> new java.util.HashSet<Object>().add(new URL("http://javabyte-set.invalid/")));
        attempt("URL in List.contains", () -> List.of(new URL("http://javabyte-list.invalid/"))
                .contains(new URL("http://javabyte-other.invalid/")));
        attempt("URL.sameFile", () -> new URL("http://javabyte-same1.invalid/").sameFile(new URL("http://javabyte-same2.invalid/")));
        attempt("URI.toURL in HashMap", () -> new HashMap<Object, Object>().put(java.net.URI.create("http://javabyte-uri.invalid/").toURL(), 1));
        attempt("getCanonicalHostName", () -> leakIfNotAddress(java.net.InetAddress.getByAddress(
                new byte[] {(byte) 203, 0, 113, 10}).getCanonicalHostName()));
        attempt("ResourceBundle.Control.newBundle", () -> {
            URLClassLoader remote = new URLClassLoader(new URL[] {new URL("http://127.0.0.1:" + port + "/cp/")});
            java.util.ResourceBundle.Control control = java.util.ResourceBundle.Control.getControl(
                    java.util.ResourceBundle.Control.FORMAT_DEFAULT);
            control.newBundle("beacon.ViaControl", Locale.ROOT, "java.properties", remote, false);
            control.newBundle("beacon.ViaControlClass", Locale.ROOT, "java.class", remote, false);
        });
        attempt("URLClassLoader.getResource", () -> new URLClassLoader(new URL[] {
            new URL("http://127.0.0.1:" + port + "/cp/")}).getResource("beacon.txt"));
        attempt("MethodType with loader", () -> MethodType.fromMethodDescriptorString("(Lbeacon/Remote;)V",
                new URLClassLoader(new URL[] {new URL("http://127.0.0.1:" + port + "/cp/")})));


        // ---- starting real threads
        Runnable touch = () -> {
            try {
                write(new FileOutputStream(canary));
            } catch (Exception ignored) {
                // expected inside the emulator
            }
        };
        attempt("Thread.start", () -> new Thread(touch, "escape-thread").start());
        attempt("virtual thread", () -> Thread.class.getMethod("startVirtualThread", Runnable.class).invoke(null, touch));
        attempt("ForkJoinPool", () -> ForkJoinPool.commonPool().execute(touch));
        attempt("new ForkJoinPool", () -> new ForkJoinPool(2).submit(touch));
        attempt("Executors", () -> Executors.newCachedThreadPool().submit(touch));
        attempt("CompletableFuture", () -> CompletableFuture.runAsync(touch).thenRunAsync(touch));
        attempt("parallel stream", () -> List.of(1, 2, 3, 4).parallelStream().forEach(i -> touch.run()));
        attempt("Timer", () -> new Timer("escape-timer").schedule(new TimerTask() {
            @Override
            public void run() {
                touch.run();
            }
        }, 0));
        attempt("shutdown hook", () -> Runtime.getRuntime().addShutdownHook(new Thread(touch, "escape-hook")));

        // ---- reaching the emulator or changing the analysis JVM
        attempt("emulator class", () -> leak(Class.forName("dev.javabyte.vm.VirtualMachine").getName()));
        // getClass() through an interface type compiles to invokeinterface on the interface, and
        // must hide the emulator's own classes and proxies just as it does on a class type.
        attempt("class of Path", () -> leak(Paths.get("x").getClass().getName()));
        attempt("class of FileSystem", () -> leak(java.nio.file.FileSystems.getDefault().getClass().getName()));
        attempt("class of executor", () -> leak(Executors.newFixedThreadPool(1).getClass().getName()));
        attempt("class of future", () -> leak(Executors.newSingleThreadExecutor().submit(() -> 1).getClass().getName()));
        attempt("class of runtime bean", () -> leak(java.lang.management.ManagementFactory.getRuntimeMXBean().getClass().getName()));
        attempt("class of process handle", () -> leak(ProcessHandle.current().getClass().getName()));
        attempt("class of network interface", () -> leak(java.net.NetworkInterface.getByName("lo").getClass().getName()));
        attempt("emulator library", () -> leak(Class.forName("org.objectweb.asm.ClassWriter").getName()));
        attempt("class loader of JDK object", () -> leak(String.valueOf("x".getClass().getClassLoader())));
        attempt("own loader", () -> leak(Escape.class.getClassLoader().getClass().getName()));
        attempt("stack frames", () -> {
            for (StackTraceElement frame : new Throwable().getStackTrace()) {
                leak(frame.getClassName());
            }
        });
        attempt("real property", () -> System.setProperty("javabyte.escape", "set"));
        attempt("default locale", () -> Locale.setDefault(Locale.forLanguageTag("x-escape")));
        attempt("security provider", () -> java.security.Security.setProperty("javabyte.escape", "set"));
        // A provider entry names a class the JDK loads and constructs: here a real Timer thread.
        attempt("Provider.put", () -> {
            java.security.Security.getProvider("SUN").put("MessageDigest.JBESCAPE1", "java.util.Timer");
            java.security.MessageDigest.getInstance("JBESCAPE1");
        });
        attempt("Map.put on a provider", () -> {
            @SuppressWarnings("unchecked")
            java.util.Map<Object, Object> table = (java.util.Map<Object, Object>) (Object) java.security.Security.getProvider("SUN");
            table.put("MessageDigest.JBESCAPE2", "java.util.Timer");
            java.security.MessageDigest.getInstance("JBESCAPE2");
        });
        attempt("provider of an engine", () -> {
            java.security.Provider provider = java.security.MessageDigest.getInstance("SHA-256").getProvider();
            provider.put("MessageDigest.JBESCAPE3", "java.util.Timer");
            java.security.MessageDigest.getInstance("JBESCAPE3", provider);
        });
        attempt("own provider", () -> {
            java.security.Provider own = new java.security.Provider("JBESCAPE", "1.0", "escape") {
                private static final long serialVersionUID = 1L;
            };
            own.put("MessageDigest.JBESCAPE4", "java.util.Timer");
            java.security.MessageDigest.getInstance("JBESCAPE4", own);
        });
        attempt("setAccessible", () -> {
            java.lang.reflect.Field value = String.class.getDeclaredField("value");
            value.setAccessible(true);
            leak(String.valueOf(value.get("x")));
        });
        attempt("Unsafe", () -> {
            java.lang.reflect.Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            unsafe.getClass().getMethod("putAddress", long.class, long.class).invoke(unsafe, 0L, 0L);
        });
        attempt("System.exit", () -> System.exit(99));
        System.out.println("unreachable: exit should have ended the run");
    }

    private static void write(java.io.OutputStream out) throws java.io.IOException {
        out.write("escaped".getBytes(StandardCharsets.UTF_8));
        out.close();
    }

    /** A reverse lookup that reached a real name server would answer with a name, not the address. */
    private static void leakIfNotAddress(String name) {
        if (!name.equals("203.0.113.10")) {
            leak(name);
        }
    }

    /** Prints whatever a route managed to read; the test searches the report for the token. */
    private static void leak(String text) {
        System.out.println("LEAK " + text);
    }

    private static void attempt(String name, Attempt attempt) {
        try {
            attempt.run();
            System.out.println(name + ": returned");
        } catch (Throwable thrown) {
            System.out.println(name + ": " + thrown.getClass().getSimpleName());
        }
    }

    /** A sample's own stream class, opening a file by name through its superclass. */
    static final class NamedPrintStream extends PrintStream {
        NamedPrintStream(String fileName) throws java.io.IOException {
            super(fileName);
        }
    }

    /** The same through the overload that takes a {@code File}. */
    static final class FilePrintWriter extends PrintWriter {
        FilePrintWriter(File file) throws java.io.IOException {
            super(file);
        }
    }
}

package dev.javabyte.stubs;

import dev.javabyte.env.MachineProfile;
import dev.javabyte.host.CallContext;
import dev.javabyte.host.StandIn;
import dev.javabyte.vm.VirtualMachine;

/**
 * The screen, as {@code java.awt.Toolkit} and {@code GraphicsEnvironment} describe it.
 *
 * <p>"Is the screen smaller than 1024x768?" is a standard sandbox check, and some samples divide
 * by the screen size. Without these stubs the calls are blocked and every dimension reads as 0.
 * Here they describe the configured screen of the {@link MachineProfile}.
 *
 * <p>Nothing of the real AWT is used. Its classes load native libraries and look for a display
 * as soon as they initialise, so the objects handed out are stand-ins with the same fields and
 * methods. Everything else on {@code Toolkit} (the clipboard, printing, images, system events)
 * stays blocked and reported, as do {@code Robot} and {@code Desktop}, which are not touched here.
 */
final class ScreenStubs {

    private ScreenStubs() {}

    /** Height of the Windows taskbar at 100% scaling, which the usable screen area leaves out. */
    private static final int TASKBAR_HEIGHT = 40;

    static void install(VirtualMachine vm) {
        MachineProfile machine = vm.env.machine;
        Screen screen = new Screen(vm, machine);

        vm.stubs.onClass("java/awt/Toolkit", ctx -> switch (ctx.name) {
            case "getDefaultToolkit" -> screen.toolkit;
            case "getScreenSize" -> screen.recordSize(new SimDimension(machine.screenWidth, machine.screenHeight));
            case "getScreenResolution" -> MachineProfile.SCREEN_DPI;
            case "getScreenInsets" -> new SimInsets(0, 0, TASKBAR_HEIGHT, 0);
            case "sync", "beep" -> null;
            case "getClass" -> SystemStubs.classOf(vm, ctx.receiver);
            case "hashCode", "equals", "toString" -> ctx.proceed();
            default -> vm.router.blocked(ctx.owner, ctx.name, ctx.desc, "method");
        });
        vm.stubs.onClass("java/awt/GraphicsEnvironment", ctx -> switch (ctx.name) {
            case "getLocalGraphicsEnvironment" -> screen.environment;
            // A desktop machine has a display; a headless JVM is a server or a sandbox.
            case "isHeadless", "isHeadlessInstance" -> 0;
            case "getScreenDevices" -> new Object[] {screen.device};
            case "getDefaultScreenDevice" -> screen.device;
            case "getMaximumWindowBounds" -> screen.recordSize(new SimRectangle(0, 0, machine.screenWidth,
                    machine.screenHeight - TASKBAR_HEIGHT));
            case "getAvailableFontFamilyNames" -> new String[] {"Arial", "Calibri", "Cambria", "Consolas", "Courier New",
                "Dialog", "DialogInput", "Georgia", "Monospaced", "SansSerif", "Segoe UI", "Serif", "Tahoma",
                "Times New Roman", "Trebuchet MS", "Verdana"};
            default -> objectOrBlocked(vm, ctx);
        });
        vm.stubs.onClass("java/awt/GraphicsDevice", ctx -> switch (ctx.name) {
            case "getIDstring" -> "\\Display0";
            case "getType" -> 0; // TYPE_RASTER_SCREEN
            case "getDisplayMode" -> screen.mode;
            case "getDisplayModes" -> new Object[] {screen.mode};
            case "getDefaultConfiguration" -> screen.configuration;
            case "getConfigurations" -> new Object[] {screen.configuration};
            case "isFullScreenSupported" -> 1;
            case "isDisplayChangeSupported" -> 0;
            case "getFullScreenWindow" -> null;
            default -> objectOrBlocked(vm, ctx);
        });
        vm.stubs.onClass("java/awt/GraphicsConfiguration", ctx -> switch (ctx.name) {
            case "getBounds" -> screen.recordSize(new SimRectangle(0, 0, machine.screenWidth, machine.screenHeight));
            case "getDevice" -> screen.device;
            default -> objectOrBlocked(vm, ctx);
        });
    }

    /** Object methods behave as on any object; everything else on these classes is refused. */
    private static Object objectOrBlocked(VirtualMachine vm, CallContext ctx) throws Exception {
        return switch (ctx.name) {
            case "getClass" -> SystemStubs.classOf(vm, ctx.receiver);
            case "hashCode", "equals", "toString" -> ctx.proceed();
            default -> vm.router.blocked(ctx.owner, ctx.name, ctx.desc, "method");
        };
    }

    /** The one screen of a run, and the objects describing it, handed out every time. */
    private static final class Screen {
        final VirtualMachine vm;
        final SimToolkit toolkit;
        final SimGraphics environment;
        final SimGraphics device;
        final SimGraphics configuration;
        final SimDisplayMode mode;

        Screen(VirtualMachine vm, MachineProfile machine) {
            this.vm = vm;
            boolean windows = machine.windows;
            this.toolkit = new SimToolkit(imitate(vm, windows ? "sun/awt/windows/WToolkit" : "sun/awt/X11/XToolkit", "java/awt/Toolkit"));
            this.environment = new SimGraphics(imitate(vm, windows ? "sun/awt/Win32GraphicsEnvironment" : "sun/awt/X11GraphicsEnvironment",
                    "java/awt/GraphicsEnvironment"), "GraphicsEnvironment");
            this.device = new SimGraphics(imitate(vm, windows ? "sun/awt/Win32GraphicsDevice" : "sun/awt/X11GraphicsDevice",
                    "java/awt/GraphicsDevice"), "Display0");
            this.configuration = new SimGraphics(imitate(vm, windows ? "sun/awt/Win32GraphicsConfig" : "sun/awt/X11GraphicsConfig",
                    "java/awt/GraphicsConfiguration"), "Display0 configuration");
            this.mode = new SimDisplayMode(machine.screenWidth, machine.screenHeight, MachineProfile.SCREEN_BIT_DEPTH,
                    MachineProfile.SCREEN_REFRESH_HZ);
        }

        /** Records the first time the sample reads the screen size, as other machine queries are recorded. */
        <T> T recordSize(T value) {
            vm.events.addOnce("screen-size", "system", "screen-size", "width", vm.env.machine.screenWidth,
                    "height", vm.env.machine.screenHeight);
            return value;
        }
    }

    /**
     * The JDK class a stand-in imitates: the platform's implementation class if the analysis
     * JDK has it, so {@code getClass()} looks real, otherwise the public abstract class.
     */
    private static String imitate(VirtualMachine vm, String implementation, String fallback) {
        return vm.host.findClass(implementation) != null ? implementation : fallback;
    }

    /** Stands in for the platform's {@code Toolkit}. All its methods are answered by stubs. */
    public static final class SimToolkit implements StandIn {
        private final String imitates;

        SimToolkit(String imitates) {
            this.imitates = imitates;
        }

        @Override
        public String standsFor() {
            return imitates;
        }

        @Override
        public String toString() {
            return imitates.replace('/', '.') + "@" + Integer.toHexString(hashCode());
        }
    }

    /** Stands in for a graphics environment, device or configuration; answered by stubs. */
    public static final class SimGraphics implements StandIn {
        private final String imitates;
        private final String label;

        SimGraphics(String imitates, String label) {
            this.imitates = imitates;
            this.label = label;
        }

        @Override
        public String standsFor() {
            return imitates;
        }

        @Override
        public String toString() {
            return imitates.replace('/', '.') + "[" + label + "]";
        }
    }

    /** Imitates {@code java.awt.Dimension}: public fields, as samples read them directly. */
    public static final class SimDimension implements StandIn {
        public int width;
        public int height;

        SimDimension(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public String standsFor() {
            return "java/awt/Dimension";
        }

        public double getWidth() { return width; }
        public double getHeight() { return height; }
        public SimDimension getSize() { return new SimDimension(width, height); }
        @Override public int hashCode() { int sum = width + height; return sum * (sum + 1) / 2 + width; }
        @Override public boolean equals(Object other) {
            return other instanceof SimDimension that && that.width == width && that.height == height;
        }
        @Override public String toString() { return "java.awt.Dimension[width=" + width + ",height=" + height + "]"; }
    }

    /** Imitates {@code java.awt.Rectangle}. */
    public static final class SimRectangle implements StandIn {
        public int x;
        public int y;
        public int width;
        public int height;

        SimRectangle(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        @Override
        public String standsFor() {
            return "java/awt/Rectangle";
        }

        public double getX() { return x; }
        public double getY() { return y; }
        public double getWidth() { return width; }
        public double getHeight() { return height; }
        public double getCenterX() { return x + width / 2.0; }
        public double getCenterY() { return y + height / 2.0; }
        public boolean isEmpty() { return width <= 0 || height <= 0; }
        public boolean contains(int px, int py) { return px >= x && py >= y && px < x + width && py < y + height; }
        public SimRectangle getBounds() { return new SimRectangle(x, y, width, height); }
        public SimDimension getSize() { return new SimDimension(width, height); }
        @Override public int hashCode() { return java.util.Objects.hash(x, y, width, height); }
        @Override public boolean equals(Object other) {
            return other instanceof SimRectangle that && that.x == x && that.y == y && that.width == width && that.height == height;
        }
        @Override public String toString() {
            return "java.awt.Rectangle[x=" + x + ",y=" + y + ",width=" + width + ",height=" + height + "]";
        }
    }

    /** Imitates {@code java.awt.Insets}: the parts of the screen the taskbar takes. */
    public static final class SimInsets implements StandIn {
        public int top;
        public int left;
        public int bottom;
        public int right;

        SimInsets(int top, int left, int bottom, int right) {
            this.top = top;
            this.left = left;
            this.bottom = bottom;
            this.right = right;
        }

        @Override
        public String standsFor() {
            return "java/awt/Insets";
        }

        @Override public String toString() {
            return "java.awt.Insets[top=" + top + ",left=" + left + ",bottom=" + bottom + ",right=" + right + "]";
        }
    }

    /** Imitates {@code java.awt.DisplayMode}. */
    public static final class SimDisplayMode implements StandIn {
        private final int width;
        private final int height;
        private final int bitDepth;
        private final int refreshRate;

        SimDisplayMode(int width, int height, int bitDepth, int refreshRate) {
            this.width = width;
            this.height = height;
            this.bitDepth = bitDepth;
            this.refreshRate = refreshRate;
        }

        @Override
        public String standsFor() {
            return "java/awt/DisplayMode";
        }

        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public int getBitDepth() { return bitDepth; }
        public int getRefreshRate() { return refreshRate; }
        @Override public String toString() { return width + "x" + height + "x" + bitDepth + "bpp@" + refreshRate + "Hz"; }
    }
}

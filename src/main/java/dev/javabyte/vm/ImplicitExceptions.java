package dev.javabyte.vm;

/**
 * Builds the exceptions the JVM itself throws while executing an instruction: a
 * {@code NullPointerException} from a null receiver, array or lock, and a
 * {@code ClassCastException} from {@code checkcast}.
 *
 * <p>Their messages differ between Java versions, and samples read them: a message that the
 * advertised runtime would never produce tells the sample it is not on a real JVM. So every
 * implicit throw site asks this class, and the wording follows the guest's
 * {@code java.version}. On Java 8, the default identity, an implicit {@code NullPointerException}
 * has no message at all and a failed cast reads {@code X cannot be cast to Y}.
 */
final class ImplicitExceptions {

    /** First Java version whose JVM describes the null value in a NullPointerException (JEP 358). */
    private static final int HELPFUL_NULL_POINTER_MESSAGES = 14;
    /** First Java version whose cast message names classes as "class X" and adds module details. */
    private static final int MODULE_CAST_MESSAGES = 11;

    private final VirtualMachine vm;

    ImplicitExceptions(VirtualMachine vm) {
        this.vm = vm;
    }

    /**
     * An implicit null-pointer failure, ready to throw.
     *
     * @param helpfulMessage what a Java 14 or later JVM would say. It is an approximation of
     *                       HotSpot's wording, which names the local or field that held null;
     *                       on Java 8 it is not used, because that JVM gives no message.
     */
    GuestThrow nullPointer(String helpfulMessage) {
        boolean helpful = guestFeatureVersion() >= HELPFUL_NULL_POINTER_MESSAGES;
        return vm.raise(helpful ? new NullPointerException(helpfulMessage) : new NullPointerException());
    }

    /**
     * A failed {@code checkcast}, ready to throw, worded as the guest's Java version words it.
     *
     * @param target internal name or array descriptor of the type cast to
     */
    GuestThrow classCast(Object value, String target) {
        String from = vm.types.nameOf(value);
        // An array descriptor keeps its brackets, as in HotSpot's "[Ljava.lang.String;".
        String to = target.replace('/', '.');
        if (guestFeatureVersion() < MODULE_CAST_MESSAGES) {
            return vm.raise(new ClassCastException(from + " cannot be cast to " + to));
        }
        String fromPlace = placeOf(vm.stateOf(value) != null ? null : vm.types.reportedClass(value));
        String toPlace = placeOf(vm.classes.contains(target) ? null : vm.host.findClass(target));
        String detail = fromPlace.equals(toPlace)
                ? from + " and " + to + " are in " + fromPlace
                : from + " is in " + fromPlace + "; " + to + " is in " + toPlace;
        return vm.raise(new ClassCastException("class " + from + " cannot be cast to class " + to + " (" + detail + ")"));
    }

    /**
     * Where HotSpot says a class lives, for cast messages: its module and loader. A class with
     * no host class is one of the sample's own, which the application loader defines.
     */
    private static String placeOf(Class<?> cls) {
        if (cls == null) {
            return "unnamed module of loader 'app'";
        }
        Module module = cls.getModule();
        String loader = cls.getClassLoader() == null ? "bootstrap" : "platform";
        return (module.isNamed() ? "module " + module.getName() : "unnamed module") + " of loader '" + loader + "'";
    }

    /**
     * The guest's Java feature version (8 for "1.8.0_361", 17 for "17.0.9"), read from the
     * simulated {@code java.version} property so that a config override of it is respected.
     * Defaults to 8, the advertised runtime, before the properties are filled in.
     */
    int guestFeatureVersion() {
        String version = vm.env == null ? null : vm.env.properties.getProperty("java.version");
        if (version == null || version.isEmpty()) {
            return 8;
        }
        if (version.startsWith("1.")) {
            version = version.substring(2);
        }
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) {
            end++;
        }
        try {
            return end == 0 ? 8 : Integer.parseInt(version.substring(0, end));
        } catch (NumberFormatException tooLong) {
            return 8;
        }
    }
}

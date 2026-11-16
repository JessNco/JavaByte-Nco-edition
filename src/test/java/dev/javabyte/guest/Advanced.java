package dev.javabyte.guest;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Second differential fixture: the places where interpreted code and the real JDK meet.
 * Reflection on interpreted classes, classes that extend JDK classes, proxies, method handles,
 * iterators consumed by JDK code, and wide values in argument lists.
 */
public final class Advanced {

    /** Extends a JDK collection, so instances must be usable by JDK code as a List. */
    static class CountingList extends ArrayList<String> {
        int adds;

        @Override
        public boolean add(String value) {
            adds++;
            return super.add(value.toUpperCase());
        }
    }

    /** A guest Iterable consumed by a for-each loop and by JDK stream code. */
    static class Range implements Iterable<Integer> {
        final int end;

        Range(int end) {
            this.end = end;
        }

        @Override
        public Iterator<Integer> iterator() {
            return new Iterator<>() {
                int next;

                @Override
                public boolean hasNext() {
                    return next < end;
                }

                @Override
                public Integer next() {
                    return next++;
                }
            };
        }
    }

    static class ValidationException extends RuntimeException {
        final int field;

        ValidationException(String message, int field) {
            super(message);
            this.field = field;
        }

        @Override
        public String getMessage() {
            return "[" + field + "] " + super.getMessage();
        }
    }

    abstract static class Animal implements Comparable<Animal> {
        protected final String name;
        private int legs = 4;
        static int created;

        Animal(String name) {
            this.name = name;
            created++;
        }

        abstract String sound();

        String describe() {
            return name + " says " + sound() + " on " + legs + " legs";
        }

        @Override
        public int compareTo(Animal other) {
            return name.compareTo(other.name);
        }
    }

    static class Dog extends Animal {
        public boolean goodDog = true;
        public long chipId = 9_000_000_000L;
        public char grade = 'A';

        public Dog(String name) {
            super(name);
        }

        @Override
        String sound() {
            return "woof";
        }

        @Override
        String describe() {
            return super.describe() + "!";
        }

        public static String bark(int times, long pause, double volume, String suffix) {
            return "woof".repeat(times) + pause + volume + suffix;
        }

        private String secret(byte b, short s, char c, boolean flag, float f) {
            return name + b + s + c + flag + f;
        }

        public void fail() {
            throw new ValidationException("bad dog", 3);
        }
    }

    enum Op implements BiFunction<Integer, Integer, Integer> {
        ADD("+") {
            @Override
            public Integer apply(Integer a, Integer b) {
                return a + b;
            }
        },
        MUL("*") {
            @Override
            public Integer apply(Integer a, Integer b) {
                return a * b;
            }
        };

        final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }
    }

    enum Level { LOW, MID, HIGH }

    interface Greeter {
        String greet(String who);

        default String twice(String who) {
            return greet(who) + greet(who);
        }

        static Greeter polite() {
            return who -> "Dear " + who + ". ";
        }
    }

    static class Resource implements AutoCloseable {
        final StringBuilder log;
        final String id;

        Resource(StringBuilder log, String id) {
            this.log = log;
            this.id = id;
            log.append("open:").append(id).append(' ');
        }

        @Override
        public void close() {
            log.append("close:").append(id).append(' ');
        }
    }

    public static void main(String[] args) throws Throwable {
        jdkSubclasses();
        reflection();
        proxiesAndHandles();
        enumsAndInterfaces();
        resourcesAndFinally();
        wideArguments(1, 2L, 3.5, 4, 5L, 6.5f, (byte) 7, 'h', true);
        misc();
    }

    static void jdkSubclasses() {
        CountingList list = new CountingList();
        list.add("b");
        list.add("a");
        list.addAll(List.of("d", "c"));
        java.util.Collections.sort(list);
        List<String> view = list;
        int total = 0;
        for (int value : new Range(5)) {
            total += value;
        }
        String joined = java.util.stream.StreamSupport.stream(new Range(4).spliterator(), false)
                .map(i -> "n" + i).collect(Collectors.joining(","));
        System.out.println(list + " " + list.adds + " " + view.contains("A") + " " + (list instanceof ArrayList) + " "
                + list.size() + " " + total + " " + joined + " " + String.join("|", list));
        try {
            new Dog("Rex").fail();
        } catch (RuntimeException e) {
            System.out.println(e.getMessage() + " " + (e instanceof ValidationException) + " "
                    + e.getClass().getSimpleName() + " " + String.valueOf(e).length());
        }
    }

    static void reflection() throws Exception {
        Class<?> type = Class.forName("dev.javabyte.guest.Advanced$Dog");
        Constructor<?> constructor = type.getConstructor(String.class);
        Object dog = constructor.newInstance("Fido");
        Method describe = type.getDeclaredMethod("describe");
        Method bark = type.getMethod("bark", int.class, long.class, double.class, String.class);
        Method secret = type.getDeclaredMethod("secret", byte.class, short.class, char.class, boolean.class, float.class);
        secret.setAccessible(true);
        Field good = type.getField("goodDog");
        Field chip = type.getField("chipId");
        Field grade = type.getField("grade");
        Field legs = type.getSuperclass().getDeclaredField("legs");
        legs.setAccessible(true);
        legs.setInt(dog, 3);
        good.set(dog, false);
        chip.setLong(dog, chip.getLong(dog) + 1);
        System.out.println(describe.invoke(dog) + " " + bark.invoke(null, 2, 5L, 0.5, "?") + " "
                + secret.invoke(dog, (byte) 1, (short) 2, 'c', true, 1.5f) + " " + good.get(dog) + " " + good.getBoolean(dog)
                + " " + chip.get(dog) + " " + grade.get(dog) + grade.getChar(dog) + " " + legs.get(dog));
        System.out.println(type.getName() + " " + type.getSimpleName() + " " + type.getSuperclass().getSimpleName() + " "
                + Animal.class.isAssignableFrom(type) + " " + type.isInstance(dog) + " " + type.isInterface() + " "
                + java.lang.reflect.Modifier.isAbstract(type.getSuperclass().getModifiers()) + " "
                + bark.getParameterCount() + bark.getName() + bark.getReturnType().getSimpleName()
                + " " + good.getType() + " " + Animal.created);
        try {
            type.getMethod("fail").invoke(dog);
        } catch (InvocationTargetException e) {
            System.out.println("wrapped " + e.getCause().getMessage());
        }
        try {
            type.getMethod("noSuchMethod");
        } catch (NoSuchMethodException e) {
            System.out.println("missing method");
        }
        List<String> names = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            names.add(method.getName());
        }
        java.util.Collections.sort(names);
        System.out.println(names + " " + Arrays.stream(type.getFields()).map(Field::getName).sorted().collect(Collectors.toList()));
    }

    static void proxiesAndHandles() throws Throwable {
        List<String> calls = new ArrayList<>();
        InvocationHandler handler = (proxy, method, methodArgs) -> {
            calls.add(method.getName() + (methodArgs == null ? 0 : methodArgs.length));
            return method.getReturnType() == String.class ? "proxied " + methodArgs[0] : null;
        };
        Greeter greeter = (Greeter) Proxy.newProxyInstance(Advanced.class.getClassLoader(), new Class<?>[] {Greeter.class}, handler);
        Runnable runnable = (Runnable) Proxy.newProxyInstance(Advanced.class.getClassLoader(), new Class<?>[] {Runnable.class}, handler);
        runnable.run();
        MethodHandle bark = MethodHandles.lookup().findStatic(Dog.class, "bark",
                MethodType.methodType(String.class, int.class, long.class, double.class, String.class));
        MethodHandle concat = MethodHandles.lookup().findVirtual(String.class, "concat", MethodType.methodType(String.class, String.class));
        MethodHandle length = MethodHandles.lookup().findVirtual(String.class, "length", MethodType.methodType(int.class));
        Supplier<String> supplier = () -> "supplied";
        System.out.println(greeter.greet("x") + " " + calls + " " + (String) bark.invoke(1, 2L, 3.0, "!") + " "
                + (String) concat.invoke("ab", "cd") + " " + (int) length.invoke("hello") + " "
                + MethodHandles.insertArguments(bark, 0, 3).invokeWithArguments(1L, 1.0, "") + " " + concat.bindTo("x").invoke("y") + " " + supplier.get() + " "
                + Optional.of("v").map(v -> v + v).orElse("none"));
    }

    static void enumsAndInterfaces() {
        StringBuilder out = new StringBuilder();
        for (Op op : Op.values()) {
            out.append(op.symbol).append(op.apply(6, 7)).append(op.name()).append(op.ordinal()).append(' ');
            switch (op) {
                case ADD -> out.append("additive ");
                case MUL -> out.append("multiplicative ");
            }
        }
        Greeter greeter = Greeter.polite();
        TreeMap<Animal, Integer> sorted = new TreeMap<>();
        sorted.put(new Dog("Zed"), 1);
        sorted.put(new Dog("Ace"), 2);
        HashSet<Op> ops = new HashSet<>(List.of(Op.MUL, Op.ADD, Op.MUL));
        java.util.EnumMap<Level, String> byLevel = new java.util.EnumMap<>(Level.class);
        byLevel.put(Level.HIGH, "h");
        byLevel.put(Level.LOW, "l");
        java.util.EnumMap<Op, Integer> byOp = new java.util.EnumMap<>(Op.class);
        byOp.put(Op.MUL, 2);
        byOp.put(Op.ADD, 1);
        java.util.EnumSet<Level> some = java.util.EnumSet.of(Level.HIGH, Level.LOW);
        java.util.EnumSet<Level> rest = java.util.EnumSet.complementOf(some);
        System.out.println(byLevel + " " + byLevel.containsKey(Level.MID) + " " + byOp.keySet() + byOp.get(Op.MUL) + " "
                + some + rest + java.util.EnumSet.allOf(Level.class).size() + java.util.EnumSet.range(Level.LOW, Level.MID)
                + java.util.EnumSet.noneOf(Op.class).isEmpty() + " " + Level.valueOf("MID").compareTo(Level.HIGH)
                + Enum.valueOf(Level.class, "LOW") + Level.HIGH.getDeclaringClass().getSimpleName());
        System.out.println(out + greeter.twice("Al") + sorted.firstKey().name + sorted.values() + " " + ops.size()
                + Op.valueOf("MUL").getDeclaringClass().getSimpleName() + " " + (Op.ADD.compareTo(Op.MUL) < 0)
                + " " + sorted.firstKey().describe());
    }

    static void resourcesAndFinally() {
        StringBuilder log = new StringBuilder();
        try (Resource a = new Resource(log, "a"); Resource b = new Resource(log, "b")) {
            log.append("body ");
            throw new IllegalStateException("boom");
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.append("caught:").append(e.getMessage()).append(' ');
        } finally {
            log.append("finally");
        }
        System.out.println(log + " " + finallyOverrides() + " " + nestedLoops());
    }

    @SuppressWarnings("finally")
    static int finallyOverrides() {
        try {
            return 1;
        } finally {
            return 2;
        }
    }

    static int nestedLoops() {
        int count = 0;
        outer:
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                if (j > i) {
                    continue outer;
                }
                if (i + j > 12) {
                    break outer;
                }
                count += i ^ j;
            }
        }
        return count;
    }

    /** Wide values between narrow ones exercise local-variable slot numbering. */
    static void wideArguments(int a, long b, double c, int d, long e, float f, byte g, char h, boolean i) {
        long sum = a + b + d + e + g;
        double product = c * f;
        long[] matrix = {b, e, sum};
        matrix[2] *= 2;
        System.out.println(sum + " " + product + " " + h + i + " " + Arrays.toString(matrix) + " " + Long.hashCode(e)
                + " " + Double.compare(c, f) + " " + (char) (h + 1) + " " + (i ? b : c));
    }

    static void misc() {
        Integer small1 = 127;
        Integer small2 = 127;
        int[] original = {3, 1, 2};
        int[] copy = original.clone();
        Arrays.sort(copy);
        Object[] objects = {"s", 1, 2.5, 'c', 10L, null, new int[0]};
        StringBuilder kinds = new StringBuilder();
        for (Object object : objects) {
            if (object instanceof String s) {
                kinds.append("S").append(s.length());
            } else if (object instanceof Integer n && n > 0) {
                kinds.append("I");
            } else if (object instanceof Number) {
                kinds.append("N");
            } else if (object == null) {
                kinds.append("0");
            } else {
                kinds.append(object.getClass().isArray() ? "A" : "?");
            }
        }
        String text = """
                multi
                line""";
        String squares = IntStream.rangeClosed(1, 5).map(x -> x * x).boxed().map(String::valueOf).collect(Collectors.joining("+"));
        System.out.println((small1 == small2) + " " + Arrays.toString(original) + Arrays.toString(copy) + " " + kinds + " "
                + text.lines().count() + " " + squares + " " + String.format("%5.2f|%-4s|%04d|%x", Math.E, "ab", 42, 255)
                + " " + "a,b,,c".split(",").length + " " + new StringBuilder("abc").reverse().insert(1, 42).deleteCharAt(0)
                + " " + Character.getNumericValue('7') + Integer.toBinaryString(10) + Long.parseLong("-9") + " "
                + Integer.valueOf(1000).equals(1000) + " " + "Hello".chars().sum());
    }
}

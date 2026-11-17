package dev.javabyte.guest;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reflection, method handles and executors, as obfuscators use them to hide what is called.
 * Harmless, so it also runs on the real JVM and the two outputs are compared.
 */
public final class Introspection {

    interface Greeter {
        String greet(String name);

        default String twice(String name) {
            return greet(name) + greet(name);
        }
    }

    static class Target implements Greeter {
        public static int created;
        private final String prefix;
        private int counter = 5;
        long wide = 1L << 40;
        double ratio = 0.5;
        boolean flag;
        char letter = 'q';
        byte small = 7;
        short medium = 300;
        float single = 1.5f;
        String[] names = {"a", "b"};

        public Target() {
            this("hi ");
        }

        Target(String prefix) {
            this.prefix = prefix;
            created++;
        }

        @Override
        public String greet(String name) {
            return prefix + name;
        }

        private int bump(int by) {
            counter += by;
            return counter;
        }

        static String join(String separator, Object... parts) {
            StringBuilder text = new StringBuilder();
            for (Object part : parts) {
                text.append(text.length() == 0 ? "" : separator).append(part);
            }
            return text.toString();
        }

        static long sum(int a, long b, short c, byte d) {
            return a + b + c + d;
        }

        void fail(String message) {
            throw new IllegalStateException(message);
        }
    }

    public static void main(String[] args) throws Throwable {
        reflection();
        methodHandles();
        proxies();
        executors();
    }

    private static void reflection() throws Exception {
        Class<?> type = Class.forName("dev.javabyte.guest.Introspection$Target");
        System.out.println(type.getSimpleName() + " " + Modifier.isStatic(type.getModifiers()) + " " + type.getSuperclass().getName()
                + " " + Arrays.toString(type.getInterfaces()) + " " + type.isInterface() + " " + Greeter.class.isInterface()
                + " " + Greeter.class.isAssignableFrom(type) + " " + type.getEnclosingClass().getSimpleName());

        Constructor<?> hidden = type.getDeclaredConstructor(String.class);
        hidden.setAccessible(true);
        Object target = hidden.newInstance("yo ");
        Object plain = type.getConstructor().newInstance();
        System.out.println(type.getField("created").getInt(null) + " " + type.isInstance(target) + " " + type.cast(plain).getClass().getName());

        Method greet = type.getMethod("greet", String.class);
        Method bump = type.getDeclaredMethod("bump", int.class);
        bump.setAccessible(true);
        System.out.println(greet.invoke(target, "you") + " " + bump.invoke(target, 3) + " " + bump.invoke(target, 10)
                + " " + greet.getReturnType().getSimpleName() + " " + bump.getParameterTypes()[0] + " " + Modifier.isPrivate(bump.getModifiers())
                + " " + greet.getDeclaringClass().getSimpleName() + " " + greet.getName() + " " + bump.getParameterCount());

        Method join = type.getDeclaredMethod("join", String.class, Object[].class);
        Method sum = type.getDeclaredMethod("sum", int.class, long.class, short.class, byte.class);
        System.out.println(join.invoke(null, "-", new Object[] {1, "two", 3.0}) + " " + join.isVarArgs() + " "
                + sum.invoke(null, 1, 2L, (short) 3, (byte) 4));

        List<String> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            field.setAccessible(true);
            Object value = Modifier.isStatic(field.getModifiers()) ? field.get(null) : field.get(target);
            fields.add(field.getName() + ":" + field.getType().getSimpleName() + "=" + (value instanceof Object[] array ? Arrays.toString(array) : value));
        }
        java.util.Collections.sort(fields);
        System.out.println(fields);

        Field counter = type.getDeclaredField("counter");
        counter.setAccessible(true);
        counter.setInt(target, 99);
        Field wide = type.getDeclaredField("wide");
        wide.setLong(target, 7L);
        Field flag = type.getDeclaredField("flag");
        flag.setBoolean(target, true);
        Field ratio = type.getDeclaredField("ratio");
        ratio.set(target, 2.25);
        System.out.println(counter.getInt(target) + " " + wide.get(target) + " " + flag.getBoolean(target) + " " + ratio.getDouble(target));

        try {
            type.getDeclaredMethod("fail", String.class).invoke(target, "boom");
        } catch (InvocationTargetException e) {
            System.out.println("wrapped " + e.getCause().getClass().getSimpleName() + ":" + e.getCause().getMessage());
        }
        try {
            type.getDeclaredMethod("missing");
        } catch (NoSuchMethodException e) {
            System.out.println("no such method");
        }
        try {
            Class.forName("does.not.Exist");
        } catch (ClassNotFoundException e) {
            System.out.println("no such class " + e.getMessage());
        }

        Object grid = Array.newInstance(int.class, 3);
        Array.setInt(grid, 1, 42);
        Object words = Array.newInstance(String.class, 2);
        Array.set(words, 0, "w");
        System.out.println(Array.getLength(grid) + " " + Array.getInt(grid, 1) + " " + Array.get(grid, 0) + " " + Arrays.toString((String[]) words)
                + " " + grid.getClass().getComponentType() + " " + words.getClass().getSimpleName() + " " + int[][].class.getName());

        List<String> methods = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            methods.add(method.getName() + method.getParameterCount());
        }
        java.util.Collections.sort(methods);
        System.out.println(methods + " " + type.getDeclaredConstructors().length);
    }

    private static void methodHandles() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle greet = lookup.findVirtual(Target.class, "greet", MethodType.methodType(String.class, String.class));
        MethodHandle sum = lookup.findStatic(Target.class, "sum", MethodType.methodType(long.class, int.class, long.class, short.class, byte.class));
        MethodHandle create = lookup.findConstructor(Target.class, MethodType.methodType(void.class, String.class));
        MethodHandle counter = lookup.findGetter(Target.class, "counter", int.class);
        MethodHandle setCounter = lookup.findSetter(Target.class, "counter", int.class);
        MethodHandle created = lookup.findStaticGetter(Target.class, "created", int.class);
        MethodHandle bump = lookup.findVirtual(Target.class, "bump", MethodType.methodType(int.class, int.class));

        Target target = (Target) create.invoke("mh ");
        setCounter.invoke(target, 20);
        System.out.println((String) greet.invokeExact(target, "x") + " " + (long) sum.invokeExact(1, 2L, (short) 3, (byte) 4)
                + " " + (int) counter.invoke(target) + " " + ((int) created.invoke() > 0) + " " + (int) bump.invoke(target, 2));

        MethodHandle bound = greet.bindTo(target);
        MethodHandle fixed = MethodHandles.insertArguments(sum, 1, 10L, (short) 20);
        MethodHandle swapped = MethodHandles.permuteArguments(
                lookup.findStatic(Introspection.class, "minus", MethodType.methodType(int.class, int.class, int.class)),
                MethodType.methodType(int.class, int.class, int.class), 1, 0);
        MethodHandle dropped = MethodHandles.dropArguments(bound, 0, int.class);
        MethodHandle filtered = MethodHandles.filterReturnValue(bound,
                lookup.findVirtual(String.class, "length", MethodType.methodType(int.class)));
        MethodHandle guarded = MethodHandles.guardWithTest(
                lookup.findStatic(Introspection.class, "positive", MethodType.methodType(boolean.class, int.class)),
                MethodHandles.dropArguments(MethodHandles.constant(String.class, "pos"), 0, int.class),
                MethodHandles.dropArguments(MethodHandles.constant(String.class, "neg"), 0, int.class));
        System.out.println(bound.invoke("y") + " " + fixed.invoke(1, (byte) 2) + " " + swapped.invoke(10, 3) + " " + dropped.invoke(0, "z")
                + " " + filtered.invoke("four") + " " + bound.invokeWithArguments("list") + " " + bound.type() + " "
                + guarded.invoke(5) + guarded.invoke(-5) + guarded.type().parameterCount()
                + " " + sum.asType(MethodType.methodType(Object.class, Integer.class, Long.class, Short.class, Byte.class)).invoke(1, 1L, (short) 1, (byte) 1)
                + " " + MethodHandles.identity(String.class).invoke("same") + " " + lookup.unreflect(Target.class.getDeclaredMethod("greet", String.class)).invoke(target, "u")
                + " " + MethodHandles.arrayElementGetter(int[].class).invoke(new int[] {5, 6}, 1)
                + " " + greet.asSpreader(Object[].class, 2).invoke(new Object[] {target, "spread"}));
    }

    static int minus(int a, int b) {
        return a - b;
    }

    static boolean positive(int value) {
        return value > 0;
    }

    private static void proxies() {
        List<String> calls = new ArrayList<>();
        InvocationHandler handler = (proxy, method, arguments) -> {
            calls.add(method.getName() + (arguments == null ? 0 : arguments.length));
            switch (method.getName()) {
                case "greet": return "proxied " + arguments[0];
                case "run": return null;
                case "call": return "called";
                case "compare": return ((String) arguments[1]).compareTo((String) arguments[0]);
                case "hashCode": return 7;
                case "toString": return "proxy!";
                default: return null;
            }
        };
        ClassLoader loader = Introspection.class.getClassLoader();
        Greeter greeter = (Greeter) Proxy.newProxyInstance(loader, new Class<?>[] {Greeter.class}, handler);
        Runnable runnable = (Runnable) Proxy.newProxyInstance(loader, new Class<?>[] {Runnable.class, Callable.class}, handler);
        @SuppressWarnings("unchecked")
        java.util.Comparator<String> reversed = (java.util.Comparator<String>) Proxy.newProxyInstance(loader,
                new Class<?>[] {java.util.Comparator.class}, handler);
        runnable.run();
        List<String> sorted = new ArrayList<>(List.of("b", "c", "a"));
        sorted.sort(reversed);
        System.out.println(greeter.greet("g") + " " + greeter + " " + greeter.hashCode() + " " + sorted + " " + Proxy.isProxyClass(greeter.getClass())
                + " " + (runnable instanceof Callable) + " " + calls.subList(0, 2) + " " + (Proxy.getInvocationHandler(greeter) == handler));
    }

    private static void executors() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            int n = i;
            jobs.add(() -> n * n);
        }
        int total = 0;
        for (Future<Integer> result : pool.invokeAll(jobs)) {
            total += result.get();
        }
        Future<String> single = pool.submit(() -> "submitted");
        AtomicInteger ran = new AtomicInteger();
        pool.execute(ran::incrementAndGet);
        pool.shutdown();
        System.out.println(total + " " + single.get() + " " + pool.awaitTermination(5, TimeUnit.SECONDS) + " " + ran.get() + " " + pool.isShutdown());

        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        Future<String> later = timer.schedule(() -> "later", 50, TimeUnit.MILLISECONDS);
        System.out.println(later.get(5, TimeUnit.SECONDS) + " " + later.isDone());
        timer.shutdownNow();

        Supplier<String> supplier = () -> "async";
        Function<String, Integer> length = String::length;
        CompletableFuture<Integer> chain = CompletableFuture.supplyAsync(supplier).thenApply(String::toUpperCase).thenApply(length);
        CompletableFuture<String> combined = chain.thenCombine(CompletableFuture.completedFuture("!"), (n, mark) -> n + mark);
        System.out.println(chain.get() + " " + combined.join() + " " + combined.isDone()
                + " " + CompletableFuture.supplyAsync(() -> 1 / Integer.parseInt("0")).exceptionally(e -> -1).get());
    }
}

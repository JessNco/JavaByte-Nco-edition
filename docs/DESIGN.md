# Design

This document explains how JavaByte is put together and why. It is meant for someone changing
the code; for using the tool, start with the README. The desktop app is a separate program
that drives the command-line tool; its design is in [GUI-PLAN.md](GUI-PLAN.md).

## Goal

Observe what untrusted Java code does without letting it do anything. That rules out running the
sample on a real JVM, even an instrumented one, because the JVM would execute its side effects.
JavaByte therefore executes the bytecode itself and decides, call by call, what is real.

## Execution model: a hybrid

There are two established ways to execute bytecode outside the JVM's own engine.

**A full VM written in Java** interprets everything, including the JDK's own classes.
[SSVM](https://github.com/xxDark/SSVM) works this way: it describes itself as "a Java VM running
on a JVM", boots real JDK 8/9 class files inside itself and offers an interface for modifying VM
behaviour. This is the most faithful approach. Its cost is that every native method in the JDK
must be re-implemented, and the result is tied to the JDK versions it can boot.

**A partial interpreter** runs only the code of interest and delegates the rest.
[java-deobfuscator](https://github.com/java-deobfuscator/deobfuscator) does this for string
decryption: its executor asks a chain of "providers" whether each call, field access or type
check may proceed, through paired `canInvokeMethod`/`invokeMethod` style methods.

JavaByte is a hybrid in the second tradition, taken further:

- Classes that belong to the sample are **interpreted**.
- Calls into the JDK run **for real**, but only for members on a deny-by-default allow-list of
  side-effect-free code.
- Everything else is answered by a **stub**, which simulates the API and records the attempt.

Why hybrid: real `String`, `HashMap` and `Cipher` are faster and more correct than any
re-implementation, and most of what a sample does between its interesting actions is exactly
that kind of computation. The price is the boundary between interpreted and real code, which is
where most of the design effort went.

### The call order

`InvocationRouter` is the security boundary. For every call made by guest code:

1. a hook from the config, if one matches;
2. the sample's own bytecode, if an interpreted class implements the method;
3. a stub;
4. the real JDK member, if the policy allows it;
5. otherwise the call is blocked and reported.

Reflection (`Method.invoke`, `Constructor.newInstance`, `Field.get`), method handles and lambdas
all re-enter the router. A reflective `Runtime.exec` meets the same stub as a direct one; this is
covered by a test.

Guest code reaches the JDK through two entry points only: `InvocationRouter` (`hostCall` for
methods, `constructHost` for constructors) and `Fields` (fields of JDK classes). Static JDK
field reads are policy-checked, static writes are never performed, and instance fields are
reached only on host objects the guest obtained through checked calls, and only when public.
The router also refuses any allow-listed JDK method or constructor whose parameters include a
`ClassLoader` (such as `ResourceBundle.Control.newBundle`), because the JDK code would then ask
that loader for classes or resources where no stub sees it.

### Values

Inside the interpreter every `boolean`, `byte`, `char`, `short` and `int` is an `Integer`, as on
the JVM's operand stack. Values are narrowed when passed to real JDK methods (reflection needs
exact primitive types) and widened on return. A `long` or `double` occupies one operand-stack
entry instead of two; the few stack-shuffling opcodes whose behaviour depends on width inspect
the value's type.

Arrays are real Java arrays, so they pass to JDK methods without copying. Arrays whose element
type is an interpreted class are `Object[]`.

## Guest objects and "shells"

An instance of an interpreted class is represented in one of two ways.

- A **plain object** holds the guest fields. Used when the class's only real supertype is
  `Object`.
- A **shell** is used when the class extends a JDK class or implements JDK interfaces. A shell
  is a host class generated with ASM that genuinely extends and implements those types but
  contains none of the sample's code. Each overridable method is a trampoline: if the guest
  class implements the method, run it in the interpreter; otherwise call the JDK superclass.

Shells are what let a guest `Comparator` be passed to the real `Collections.sort`, a guest
exception be a real `Throwable`, and a guest class loader be a real `ClassLoader` object (whose
loading methods are nevertheless all stubs). A full VM does not need shells, because it
interprets the JDK too; shells are the cost of the hybrid model, and they are contained in one
package.

A JDK class may serve as a shell's superclass only if the policy allows guest code to construct
it. Otherwise it is treated as a *phantom* superclass: the guest class is plain, and calls to
the missing parent are reported and answered with neutral values.

When a guest class with a shell is extended by another guest class, the second shell extends
the first, so the generated classes mirror the guest hierarchy. JDK code can observe that
relationship: `Enum.compareTo`, for instance, requires two constants to share their enum class.

Because bytecode allocates an object (`NEW`) before passing constructor arguments, an object
that needs a real host instance exists as a `PendingInstance` placeholder until its constructor
call returns.

## Simulating instead of stubbing each method

Where possible a simulated object is a real subclass of the JDK type, with every method
overridden: `SimFile extends File`, `SimSocket extends Socket`, `SimHttpConnection extends
HttpsURLConnection` (with `SimHttpConnection.Plain extends HttpURLConnection` for `http:` URLs),
`SimProcess extends Process`. Only the points where such an object comes
into being (constructors, `URL.openConnection`, `Runtime.exec`) need a stub. After that, ordinary
virtual dispatch keeps the guest inside the simulation, and guest casts work.

Tests assert that these classes override every public method of their parent, so a method added
by a future JDK cannot silently fall through to the real implementation.

For JDK types that are final or have no usable constructor (`RandomAccessFile`,
`NetworkInterface`, `Timer`), a look-alike with same-named methods is used and marked `StandIn`.

## Class loading and `defineClass`

There is one flat namespace of guest classes rather than a tree of loaders. Malware uses custom
loaders to hide code, not to isolate it, and a flat namespace means a class defined at run time
is reachable however the sample asks for it later.

All ways of creating a class from bytes end in `VirtualMachine.defineClass`:
`ClassLoader.defineClass` (every overload), `Unsafe.defineClass`, `Unsafe.defineAnonymousClass`,
`MethodHandles.Lookup.defineClass`, `defineHiddenClass` and `defineHiddenClassWithClassData`.
The bytes are saved, recorded with their hash, parsed and then interpreted like any other class.

Implicit loading is reproduced as well. On a real JVM, when a class defined by a custom loader
first refers to another class, the JVM asks that loader for it. Loaders that decrypt classes on
demand rely on this, so an unknown class name is offered to the sample's loaders (through their
own `loadClass`) before it is declared missing.

`new URLClassLoader(...)` gives the guest a `SimURLClassLoader`, which only remembers its URLs;
the class-loader stubs read the archives it names from the virtual disk or the network
simulator. A sample's own subclass of `URLClassLoader` gets a shell built on
`SimURLClassLoader` instead of the real class, so JDK code that asks it for a class or resource
cannot reach a real URL either. In the same way a direct subclass of `ClassLoader` or
`SecureClassLoader` gets a shell built on `SimBaseClassLoader` or `SimSecureClassLoader`.
A real base would have a real parent, the emulator's own application loader, so JDK code calling
the sample's loader directly could reach the emulator's class path. The simulated bases have the
bootstrap loader as their real parent and answer JDK code only from the guest's world: JDK
classes, the sample's own `findClass`, and the sample's resources on URLs that cannot be opened.
`getParent()` still reports the parent the sample passed.

The built-in loaders look like those of the advertised Java. On Java 8 the application and
extension loaders are `URLClassLoader`s, so `SimClassLoader` and `SimPlatformClassLoader`
extend `SimURLClassLoader`: `getURLs()` lists the sample's class path (or the usual extension
jars), and `addURL` by reflection is recorded as `class.url-loader-add` and only remembered.
`getClass()` returns a modelled class object for `sun.misc.Launcher$AppClassLoader` (or
`$ExtClassLoader`), which the analysis JDK lacks, with the real name and superclass. On Java 9
and later the type system answers casts and `instanceof` from the imitated
`jdk.internal.loader` class instead, so casting the application loader to `URLClassLoader`
fails with the JDK's own message. The modelled class has no members of its own and cannot be
found with `Class.forName`, and its `getDeclaringClass()` is null rather than
`sun.misc.Launcher`.

## Threads

Each guest thread (and each timer or executor job) is carried by its own host thread, so it has
its own call stack and can be suspended anywhere. A single baton decides which carrier runs:
exactly one executes guest code at a time and the rest are parked.

The baton moves when the running thread blocks (`sleep`, `join`, `wait`, an empty queue, a held
monitor) and after a fixed number of instructions. The second rule is pre-emption: without it a
thread spinning on a flag would never let the thread that sets the flag run.

Why a baton rather than free-running threads:

- the emulator's own data structures need no locking;
- a run is repeatable, because switch points depend only on instruction counts;
- guest code can never run outside the emulator's control.

Consequences that had to be built:

- **Monitors** (`synchronized`) and `wait`/`notify` are implemented by the scheduler. A carrier
  that blocked on a real JVM monitor would stop while holding the baton.
- **Blocking JDK calls** (`Lock.lock`, `BlockingQueue.take`, `CountDownLatch.await`,
  `Semaphore.acquire`, `Future.get`) are stubs that wait cooperatively for their condition.
- **Pre-emption is suppressed underneath real JDK frames**, since the JDK code may hold one of
  its own locks.
- **Hang detection.** If every thread is waiting, none can be woken. The waiting thread is
  ended and reported, which is the emulator's rendering of a thread that would hang forever.
- **Class initialisation** by one thread makes other threads wait, as the JVM requires.

Sleeping and timed waits advance a virtual clock instead of real time; executed instructions
also cost virtual time (1 ns each), so `currentTimeMillis` and `nanoTime` always agree. Waiting
threads wake in deadline order, and a per-thread sleep budget ends beacon loops. The guest's
time zone and locale come from the config (`environment.timeZone` and `environment.locale`),
never from the analysis computer.

An earlier version ran each thread to completion on one stack. It was simpler, but any program
in which a thread waited for another could hang or see wrong results, so it was replaced.

## Native code

Native methods cannot be interpreted. JavaByte handles them in two tiers, described fully in
[NATIVE.md](NATIVE.md).

The core always makes the hand-over precise: each loaded library is parsed for its
architecture and exported JNI functions, saved, and reported, and each native call is reported
with its arguments and the library that implements it.

Execution is delegated to an optional plugin rather than built in. Embedding a CPU emulator
and a model of Windows would be a second project of comparable size, and good open-source ones
exist. The `native-bridge` plugin drives Speakeasy (on Unicorn) in a helper process. The
design rule is that the helper owns memory and registers while JavaByte owns every Java
object: JNI calls made by native code come back as callbacks and go through the ordinary call
router, so policy applies to native code's use of Java exactly as it does to bytecode.

## Configuration and reporting

Two ideas come from Mandiant's [Speakeasy](https://github.com/mandiant/speakeasy), a Windows
emulator: the environment the sample sees (files, network answers, process output) is declared
in a config file, and the result is a JSON report meant for other tools. JavaByte adopts both,
with one config model readable as YAML or JSON.

Budgets that end a run with a named reason, rather than hanging, follow Speakeasy's limits and
the visit and call-depth limits of [Simplify](https://github.com/CalebFenton/simplify)'s
smalivm.

The report separates three things that are easy to conflate:

- **events**: what the sample did;
- **blocked calls**: what policy refused, which is information about the sample;
- **gaps**: where the emulator could not be faithful, which is information about the result.

## Safety properties and their limits

What the design guarantees, assuming the allow-list is sound:

- Guest bytecode is never loaded into the host JVM.
- Guest code cannot start a process, open a file or socket, or start a thread.
- Guest code cannot call emulator internals: only classes from the JDK's boot and platform
  loaders are visible as "host classes".
- Java deserialization is restricted by a JVM-wide filter to value types and keys, because
  deserializing attacker-controlled data could otherwise reach gadget classes.
- No host name is ever looked up for real. The guest's lookups are answered by the network
  simulator, and as a backstop the command-line tool sets `jdk.net.hosts.file` to a file that
  cannot exist before anything uses the network classes, so any lookup a stub missed fails
  with `UnknownHostException` instead of sending a DNS query. `localhost`, loopback and address
  literals still work, since the JDK answers them without a lookup.

Objects the guest builds can reach the outside world through JDK code that runs on its behalf,
where no stub sees the call. These are made inert at creation:

- every URL gets `dev.javabyte.env.SimURLStreamHandler`, whose `hashCode`, `equals` and
  `sameFile` never resolve a host name, and which refuses to open a connection;
- every security provider the guest can reach is a private copy (`SimProvider`), whose services
  always come from the real provider;
- `new URLClassLoader` yields `SimURLClassLoader`, which only remembers its URLs, and
  `new SecureClassLoader` yields `SimSecureClassLoader`;
- a sample's own subclass of `ClassLoader`, `SecureClassLoader` or `URLClassLoader` is built on
  `SimBaseClassLoader`, `SimSecureClassLoader` or `SimURLClassLoader`, whose real parent is the
  bootstrap loader and which never look anything up on the emulator's class path, disk or
  network;
- the built-in application and platform loaders (`SimClassLoader`, `SimPlatformClassLoader`)
  are simulated `URLClassLoader`s that never load anything.

In addition, the router refuses any allow-listed JDK method whose parameters include a
`ClassLoader`, and `SafetyTest` checks that the simulated loaders override every public lookup
method and the protected ones `ClassLoader` itself calls.

These properties are tested from the outside. `EscapeTest` runs a fixture that tries about
eighty escape routes and then inspects the real machine: a canary file must not exist, a secret
file's contents must not appear anywhere in the report, a real listening socket must have
received no connection, and JVM-wide settings must be unchanged. `HostCallbackTest` does the
same for objects handed to JDK code: URLs in collections, providers, loaders and reverse
lookups.

What it does not guarantee:

- The allow-list is curated by hand. A JDK method on it with an overlooked side effect would be
  reachable. `SafetyTest` pins the most important exclusions.
- A sample can consume CPU and memory up to the configured limits and the JVM heap.
- Simulated objects are real JDK subclasses; a JDK method that bypasses their overrides (through
  internal access) would be a hole. None is known.

Treat JavaByte as strong containment for analysis, and still run it somewhere disposable.

## Package map

| Package | Contents |
| --- | --- |
| `dev.javabyte` | `Emulator`, the programmatic entry point |
| `cli` | Command-line front end and static inspection |
| `config` | Config model and loader |
| `vm` | Interpreter, router, scheduler, type and field helpers, `invokedynamic` linking |
| `model` | Guest classes, methods, objects and reflection mirrors |
| `shell` | Shell class generation and the trampolines shells call |
| `host` | Policy, stub registry, the reflective bridge to the JDK, deserialization guard |
| `stubs` | Replacements for JDK APIs, one class per area |
| `env` | The simulated machine: disk, network, processes, clock, executors |
| `report` | Events, artifacts and the report |
| `plugin` | Extension point for family-specific behaviour |
| `nativebridge` | Optional plugin: native code in a CPU emulator via a helper process |

## Adding a stub

1. Decide where the dangerous capability starts (usually a constructor or static factory).
2. If the API returns an object, prefer a `Sim*` subclass in `env` that overrides everything,
   and add a completeness assertion to `SafetyTest`.
3. Register the stub in the matching `stubs` class with `vm.stubs.on(...)`.
4. Record an event for anything an analyst would want to know.
5. Leave the member off the allow-list.

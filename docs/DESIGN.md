# Design

This document explains how JavaByte is put together and why. It is meant for someone changing
the code; for using the tool, start with the README.

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

Because bytecode allocates an object (`NEW`) before passing constructor arguments, an object
that needs a real host instance exists as a `PendingInstance` placeholder until its constructor
call returns.

## Simulating instead of stubbing each method

Where possible a simulated object is a real subclass of the JDK type, with every method
overridden: `SimFile extends File`, `SimSocket extends Socket`, `SimHttpConnection extends
HttpsURLConnection`, `SimProcess extends Process`. Only the points where such an object comes
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
`MethodHandles.Lookup.defineClass` and `defineHiddenClass`. The bytes are saved, recorded with
their hash, parsed and then interpreted like any other class.

Implicit loading is reproduced as well. On a real JVM, when a class defined by a custom loader
first refers to another class, the JVM asks that loader for it. Loaders that decrypt classes on
demand rely on this, so an unknown class name is offered to the sample's loaders (through their
own `loadClass`) before it is declared missing.

## Threads

All guest code runs on one host thread. Starting a thread queues its body; executors, timers and
asynchronous futures are redirected to the same queue; calls that would block (`sleep`, `join`,
`wait`, `Future.get`) give queued tasks a turn. Sleeping advances a virtual clock instantly.

This gives repeatable runs and guarantees nothing outlives the analysis. A per-task sleep budget
ends beacon loops. The trade-off is that true parallelism is not modelled.

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

## Adding a stub

1. Decide where the dangerous capability starts (usually a constructor or static factory).
2. If the API returns an object, prefer a `Sim*` subclass in `env` that overrides everything,
   and add a completeness assertion to `SafetyTest`.
3. Register the stub in the matching `stubs` class with `vm.stubs.on(...)`.
4. Record an event for anything an analyst would want to know.
5. Leave the member off the allow-list.

# Native code

Java samples often carry machine code: a JNI library unpacked at run time and loaded with
`System.load`. JavaByte interprets bytecode, not machine code, so it handles native code in
two tiers.

## Tier 1: always on, description only

Without any extra setup JavaByte:

- parses each loaded library (Windows PE and Linux ELF) and reports its format, architecture
  and how it binds to Java: by exported JNI names, or only through `JNI_OnLoad`, which is the
  mark of a native obfuscator;
- saves the library under `native/` in the output directory;
- reports every call to a `native` method with its arguments and, where an exported name
  matches, the library that implements it. The call returns zero.

`System.loadLibrary("name")` fails with `UnsatisfiedLinkError` when the library is not on the
virtual disk, as it would on a clean machine. Libraries that bundle their own native code rely
on that failure to trigger unpacking.

## Tier 2: the native bridge, opt-in

The `native-bridge` plugin runs the library's code in a CPU emulator, so native methods return
real results and the Windows API calls they make are reported.

It uses [Speakeasy](https://github.com/mandiant/speakeasy), Mandiant's Windows user-mode
emulator, which is built on the [Unicorn](https://www.unicorn-engine.org/) CPU emulator. The
library's instructions are interpreted by Unicorn. They never run on the real processor, and
Speakeasy simulates the Windows API instead of calling it.

### Setup

The bridge needs a Python interpreter with `speakeasy-emulator` installed:

```bash
scripts/setup-native-bridge.sh
```

That creates `.venv-native-bridge/` and prints the interpreter path. Then enable the plugin:

```yaml
plugins:
  native-bridge:
    python: .venv-native-bridge/bin/python
```

or on the command line:

```bash
java -jar target/javabyte-0.2.0.jar run sample.jar -s 'plugins={native-bridge: {python: .venv-native-bridge/bin/python}}'
```

When `python` is not given, the plugin uses the interpreter named by the `JAVABYTE_BRIDGE_PYTHON`
environment variable, and `python3` if that is not set either. The desktop app's "with native
bridge" installer sets the variable to the Python it bundles, so there the plugin only needs
turning on.

### What appears in the report

| Event | Meaning |
| --- | --- |
| `native.bridge-loaded` | A library was loaded into the CPU emulator; its start-up code and `JNI_OnLoad` have run |
| `native.register-natives` | Methods the library bound with `RegisterNatives`, with addresses. For obfuscated libraries this is the only place the Java-to-native mapping is visible |
| `native.emulated` | A native method was executed in the CPU emulator |
| `native.api` | A Windows API call made by native code: name, arguments, result |
| `native.jni-find-class`, `native.jni-lookup` | Java classes and members that native code asked for by name |
| `native.jni-call` | A Java method called from native code |
| `native.bridge-skipped` | A library the bridge does not support (see limits) |
| `gap.nested-native-call`, `gap.native-bridge-*`, `gap.unsupported-jni-function` | Something the bridge could not do; results after it may differ from a real run |
| `gap.native-bridge-callback-failed` | A JNI callback into Java failed. The helper is told, so the two sides stay in step |
| `gap.native-bridge-stopped` | The helper was ended (the run was stopped, or it failed); later native calls are reported, not emulated |
| `gap.jni-handler-failed` | The helper's handler for a JNI function failed; the event names the `function` |
| `gap.array-region-size-mismatch` | A `Get<Type>ArrayRegion` answer had a different size from the region native code asked for. Only the size asked for is written to its buffer |

The helper's own diagnostics are in `native-bridge.log` in the output directory.

### How it works

```text
 JavaByte (Java)                                   helper (Python)
 ---------------                                   ---------------
 interprets bytecode                               Speakeasy + Unicorn
 owns every Java object            JSON lines      owns memory and registers
 NativeBridgePlugin  <-------------------------->  native_bridge.py
        |   call(address, args)  ------------->        runs the function
        |   <-------------  callback: FindClass, CallIntMethod, NewStringUTF ...
        |   answers through the call router
        |   <-------------  result
```

The helper gives the library a JNI environment whose function table points at trap addresses.
When native code calls a JNI function the helper intercepts it. Operations on memory are
handled in the helper. Anything that touches a Java object is sent back to JavaByte, which
carries it out through the same router as bytecode. A native method that calls
`Runtime.exec` through JNI therefore meets the same stub, and the same policy, as Java code.

Java objects cross the boundary only as integer handles. The helper never holds object data
except copies of strings and arrays that native code asked to read.

### Safety

- Native instructions are interpreted by Unicorn; none run on the host processor.
- Windows API calls are simulated by Speakeasy. Files it "creates" exist only in its memory.
- The helper is a separate process started by JavaByte with a fixed command line. The sample
  cannot influence how it is started.
- `plugins.native-bridge.python` names a program that runs on the analysis computer, outside
  the emulator. The desktop app never takes it from a report folder, an imported file or a
  preset, and before a run whose settings name one it asks the person, showing the path.
- The helper parses hostile PE files, so run it under the same isolation as JavaByte itself:
  `scripts/run-isolated.sh` covers both processes.

### Limits of this version

- **64-bit Windows DLLs only.** 32-bit DLLs and Linux or macOS libraries are described (tier 1)
  but not executed.
- **One native call at a time.** If a Java method called from native code is itself native,
  the inner call is reported and returns zero. A library loaded from Java code that native code
  called back into is not emulated (`gap.native-bridge-load-failed`).
- **Stopping.** If the run is stopped while native code is waiting on a Java callback, the
  helper is ended and later native calls are reported, not emulated
  (`gap.native-bridge-stopped`).
- **JNI coverage.** Strings, primitive and object arrays, class and member lookup, all
  `Call<Type>Method` forms, field access, object creation, exceptions, `RegisterNatives`,
  references and `JavaVM.GetEnv` are implemented. Direct byte buffers, reflection conversion
  functions and `DefineClass` are not; using one is reported as a gap.
- **Floating-point arguments** are passed on a best-effort basis.
- **Windows behaviour is Speakeasy's.** An API it does not simulate ends the call early; the
  helper log names it. Files the native code writes are reported as API calls and are not
  copied to JavaByte's virtual disk.
- **Speed.** Emulated machine code is slow. Heavily protected libraries may need a longer
  `limits.timeoutSeconds`.

### Testing

`NativeBridgeTest` runs when `JAVABYTE_BRIDGE_PYTHON` names a suitable interpreter and is
skipped otherwise:

```bash
JAVABYTE_BRIDGE_PYTHON=.venv-native-bridge/bin/python mvn verify
```

The test library is `src/test/resources/native/testlib.dll`, built from
`src/test/native/testlib.c`. To rebuild it (needs the MinGW-w64 cross compiler):

```bash
x86_64-w64-mingw32-gcc -O1 -shared -nostdlib -e DllMain -o src/test/resources/native/testlib.dll src/test/native/testlib.c -lkernel32
```

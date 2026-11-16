# Self-test against public malware

JavaByte was run against public samples from [MalwareBazaar](https://bazaar.abuse.ch/). The
purpose was to find emulator defects on real code from several unrelated families, not to
produce full analyses. Every run used the default policy.

The findings below are what the emulator reported. Network indicators are quoted as observed in
the samples; nothing was contacted.

## Reproducing

```bash
MB_AUTH_KEY=<your key> scripts/fetch-sample.py <sha256>
```

```bash
java -jar target/javabyte-0.1.0.jar run samples/<sha256>/<sha256>.jar -o out/<name>
```

## Results

### STRRAT

`7a4f3b8e31e43bf030fa3f42e38e1d8cb613d326831bc9c27cfae3475cba6ff6`

Obfuscation: Allatori 9.4 (strings keyed on the caller's class and method name from a stack
trace) and a class-folder trick with one-byte decoy `.class` entries.

Observed with defaults:

- Configuration decrypted (PBKDF2 key, AES/CBC); key, IV and plaintext recorded. The plaintext
  holds two C2 hosts with their ports and a download URL.
- Single-instance lock file created through a file-channel lock.
- Persistence: a `schtasks` task named "Skype" every 30 minutes; copies to `%APPDATA%` and both
  Startup folders.
- Relaunch of the installed copy with `java.exe -jar`, then `System.exit(0)`.

Second run with `environment.samplePath` set to the installed copy
([examples/strrat-stage2.yaml](../examples/strrat-stage2.yaml)): three `wmic` fingerprinting
commands, a connection to the first C2 host, and the check-in message sent in clear.

### Adwind / jRAT

`18d22205fc172fcb534505791d7b6ddb08d58fa31063be642e9fafb415ba3891`

A one-class stub. It ran `tasklist`, wrote a 490 kB jar to `%TEMP%` and launched it.

Running JavaByte on that dropped jar: an RSA private key was deserialized and used to unwrap an
AES key, the XML configuration was decrypted, and the sample's `URLClassLoader` subclass defined
`server/main/Start` plus three classes requested implicitly. It then dropped and launched a
third stage.

### Ratty

`2436fa28a278522c298b3a398ddc553c96c4be29ae7b433a999ad49ac71d656c`

Not obfuscated; bundles the JNativeHook keyboard-hook library.

Observed: a `REG ADD` for the `HKCU` Run key, copies to `%APPDATA%` and Startup hidden with
`attrib +H`, a `JNativeHook` library load, a connection to its C2 host and port, and sender and
reader threads. The native hook itself is not emulated.

### SilentNet

`965ef0037754c4d7ac2d4ba952706bace68012485fe4202494c2f4b09f8dc152`

Contains 98 native methods and a 1.1 MB Windows DLL.

Observed with defaults: creation of a directory under `%LOCALAPPDATA%` and a relaunch of itself
with `-restarted`. With that argument supplied ([examples/relaunch-args.yaml](../examples/relaunch-args.yaml)):
DNS-over-HTTPS queries, JSON-RPC requests to several public Polygon endpoints and a fallback
host, all over simulated TLS and therefore readable. Logic inside the DLL is not emulated.

### Fabric mod sample ("Bombay")

`33666d1589c5a3482ff2e9777e9cab20fe3bc05a7947172d4b35becf10b64d82`

A Minecraft Fabric mod with no `main` method. Run through a configured `method` entry point
taken from `fabric.mod.json`, it stopped at its first use of `net.fabricmc.loader.api.FabricLoader`,
which is not in the sample. The report lists the missing class as a gap. Going further needs
the Fabric loader jar on `classpath`.

### IRAHook loader protected with qProtect

`f05a098fa2e1899797e42ebc396932e05ee40adb4454de37f760d87784a4b17c`

A Fabric mod of four classes with 520 `invokedynamic` call sites that resolve through the
sample's own bootstrap method, so no call target is visible statically. Run from the entry point
named in `fabric.mod.json`: 181 call sites were linked by interpreting the bootstrap, with no
gaps. The sample then created a directory under Discord's local storage and sent `eth_call`
requests for an ENS name to three public Ethereum RPC endpoints, which is how this family looks
up its next address.

### Linux profile

MalwareBazaar had no Java sample tagged as Linux-only, so the Linux environment was exercised
with three cross-platform samples run under `environment.os: linux`. Ratty connected to its C2
and skipped the registry step; the Adwind stage decrypted its config and defined its classes as
before; STRRAT, which assumes Windows, produced paths with backslashes in file names, as it
would on a real Linux machine. No emulator errors.

### Java versions

The full test suite and the STRRAT run give the same results on Java 17 and Java 21.

## Defects found and fixed during the self-test

| Found on | Problem | Fix |
| --- | --- | --- |
| STRRAT | 172 decoy entries reported as broken classes | Require the class-file magic |
| STRRAT | `FileOutputStream.getChannel()` unsupported, so the lock failed and the sample exited | Simulated `FileChannel` with locks |
| STRRAT | `/C:/dir` (from a `file:` URL) treated as relative | Path normalisation |
| Ratty | Crash laying out fields when a superclass has reference fields | Fixed; regression case added |
| Ratty | `Class.getPackage()` returned null | `SimPackage` |
| Ratty | `ResourceBundle.getBundle` blocked | Served from the sample's archive |
| Ratty | `ArrayStoreException` storing a phantom in a typed array | Arrays of blocked types are `Object[]` |
| Adwind | Deserialization blocked outright | JVM-wide filter allowing value types and keys |
| Adwind | Classes referenced by a run-time-defined class reported missing | Implicit loading through the sample's loaders |
| Adwind | A null argument crashed a stub | Stub failures are delivered to the guest and reported as gaps |
| SilentNet | A `File` passed to `ProcessBuilder` setters was blocked | Guard checks declared parameter types |
| SilentNet | Raw TLS sockets blocked | Simulated TLS socket and factory |

## Not covered

- A sample written only for Linux.
- Any sample whose main logic is native. SilentNet's DLL exports only `JNI_OnLoad`, so its 98
  native methods are bound at load time and their code was not examined.

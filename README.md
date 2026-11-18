# JavaByte (Nco edition)

JavaByte is a JVM bytecode emulator for analysing untrusted Java code, such as malware in `.jar`
form. It interprets the sample's bytecode itself instead of running it on a real JVM, so the
sample never gets to launch a process, touch the disk or open a connection. Everything it tries
to do is recorded.

It is built to be driven by a person at a terminal or by an AI agent: one config file controls a
run, and every result is available as stable JSON.

> **Handling malware.** JavaByte reads samples as data and does not execute them. The samples
> themselves are still live malware. Keep them out of version control (`samples/` is ignored) and
> do not open them with anything else.

## What you get from a run

- **Behaviour:** process launches, files written, HTTP requests, socket traffic, DNS lookups,
  native library loads, threads, exit codes.
- **Hidden code:** every class the sample defines at run time (`defineClass` and friends) is
  saved and then interpreted, so packed and staged payloads are followed, not just detected.
- **Decrypted material:** strings returned by decryption helpers, cipher keys, IVs and plaintext.
- **Honest limits:** calls the policy blocked and places where emulation was incomplete are
  listed separately, so you know where a result might differ from a real run.

## Quick start

Requirements: Maven and Java 17 or later. The test suite passes on Java 17, 21 and 25.

```bash
mvn -q package
```

```bash
java -jar target/javabyte-0.2.0.jar inspect sample.jar
```

```bash
java -jar target/javabyte-0.2.0.jar run sample.jar
```

`run` prints a summary and writes everything to `javabyte-out/`:

| File | Contents |
| --- | --- |
| `report.json` | The full report: outcome, summary, every event, effective config |
| `trace.log` | Events as they happened, one per line; survives a killed run |
| `dropped/` | Files the sample created on the virtual disk |
| `defined-classes/` | Classes the sample defined at run time |
| `network/` | Request bodies and socket payloads the sample sent |

To control a run, generate a config and edit it:

```bash
java -jar target/javabyte-0.2.0.jar init sample.jar > sample.yaml
```

```bash
java -jar target/javabyte-0.2.0.jar run -c sample.yaml
```

## Desktop app

For people who prefer not to use a command line, [`gui/`](gui/README.md) holds a desktop app
for Windows and Linux. It opens a sample or an earlier report, lets you change every setting
(the same YAML config the command line reads), runs the analysis with live progress, and shows
the whole report: events, limits and gaps, saved files as text, hex or bytecode, and a
comparison of two runs. It is built to WCAG 2.2 AA, its window has no network access, and on
Linux each analysis also runs with no network. The installers bring their own Java; see
[docs/INSTALL.md](docs/INSTALL.md). Building it from source: [gui/README.md](gui/README.md).
The design is in [docs/GUI-PLAN.md](docs/GUI-PLAN.md).

## Commands

| Command | Purpose |
| --- | --- |
| `run [SAMPLE] [-c FILE] [-s KEY=VALUE]... [-o DIR] [--json]` | Emulate and report |
| `run ... --events jsonl [--progress] [--control stdin]` | Stream the run live as JSON lines; accept `stop` on standard input |
| `inspect SAMPLE [--json]` | Static description: entry points, loaders, natives, resources |
| `validate [-c FILE] [-s KEY=VALUE]... [--json]` | Check a config and list every problem; exit code 0 or 2 |
| `schema [--json]` | Every config option with its type, default, allowed values and meaning |
| `init SAMPLE` | Print a starter config |
| `defaults [--json]` | Print every option with its default |
| `policy [--json]` | Print the built-in JDK allow-list; `--json` lists each rule with its effect, section and explanation |
| `disasm FILE [--class NAME]` | Print a class (or one class in a jar) as readable instructions; nothing runs |

Ctrl+C during `run` stops the run and still writes the report.

Exit codes: `0` the command ran (for `run`, read the report for what the sample did, including a
run stopped on request); `1` the
emulator itself failed; `2` bad usage or config; `3` the run hit the instruction or time limit.

## Configuration in one page

A config is YAML or JSON. Unknown keys are errors, so typos cannot be silently ignored. The full
reference is [docs/CONFIG.md](docs/CONFIG.md), and worked examples are in [examples/](examples/).

```yaml
sample: sample.jar

entryPoints:                 # default: the manifest's Main-Class
  - type: main
    args: ["-restarted"]
  - type: clinit-sweep       # run every static initialiser to harvest decrypted strings

environment:
  os: windows                # or linux
  userName: admin
  files:                     # files the sample should find
    - path: 'C:\Users\admin\AppData\Roaming\license.dat'
      text: 'demo'

network:
  mode: respond              # or offline
  http:
    - url: 'example\.test/gate'
      body: 'sleep 60'

processes:                   # canned output for commands the sample "runs"
  - command: 'whoami'
    stdout: "desktop\\admin\n"

hooks:                       # force a method's result
  - method: 'com/example/Checks.isSandbox()Z'
    action: return
    value: false

limits:
  timeoutSeconds: 120
  maxSleeps: 40              # ends beacon loops
```

Runs are repeatable: random numbers are seeded (`environment.randomSeed`), and with
`environment.clockStart` set as well, the same sample and config give the same report.

Any value can also be set from the command line: `-s limits.timeoutSeconds=30`.

## How it stays safe

1. **The sample's code is only ever interpreted.** Its classes are parsed with ASM and executed
   instruction by instruction. They are never given to a class loader.
2. **JDK calls are deny-by-default.** A call into the real JDK runs only if the allow-list
   (`javabyte policy`) permits it. The list holds pure computation: strings, collections, maths,
   cryptography, in-memory streams.
3. **Everything else is simulated or blocked.** Processes, files, network, threads, reflection
   and class loading are replaced by stand-ins that record the attempt. Reflection and method
   handles are routed back through the same checks, so they cannot be used to get around them.
4. **One thread at a time.** Guest threads are interleaved under the emulator's control and
   never run in parallel. Nothing the sample starts can outlive the run.

This is defence in depth for an analysis tool, not a formally verified sandbox. For live malware
add a second layer that does not depend on the emulator being right:

```bash
scripts/run-isolated.sh run sample.jar
```

That runs the tool in a network namespace with no network at all. See [docs/DESIGN.md](docs/DESIGN.md) for
the reasoning and for what was borrowed from other projects.

## Tested against real samples

The emulator is tested against nine public MalwareBazaar samples: STRRAT, Adwind/jRAT, Ratty,
SilentNet, a qProtect-obfuscated IRAHook loader, a Discord token stealer, a Java agent aimed at
a game client, and two Fabric mods that are kept as examples of where it stops. It recovers C2
addresses, persistence commands, decrypted configuration and run-time-defined classes. Results
are in [docs/SELFTEST.md](docs/SELFTEST.md); the expectations are executable, see
[docs/TESTING.md](docs/TESTING.md#real-malware).

## Known limitations

- **Native code needs the optional bridge.** By default JavaByte describes each native
  library and reports native calls without running them. The `native-bridge` plugin runs
  64-bit Windows DLLs in a CPU emulator (Speakeasy and Unicorn) and reports the Windows API
  calls they make. Other platforms' libraries are described only. See
  [docs/NATIVE.md](docs/NATIVE.md).
- **No real parallelism.** Guest threads are interleaved, with working locks, `wait`/`notify`
  and pre-emption, but only one runs at a time. A bug that needs two threads to execute at the
  same instant cannot occur. Sleeps take no real time, so timing measurements see virtual time.
- **Missing libraries become phantoms.** A class that is neither in the sample nor allowed from
  the JDK is replaced by a placeholder whose methods return neutral values. Add the library to
  `classpath` to have it interpreted instead.
- **No GUI.** AWT and Swing are blocked; calls are reported.
- **Deserialization is filtered** to value types and cryptographic keys.
- **Nested archives** (a jar inside a jar) are not mounted automatically.
- **Some network APIs are not simulated.** `java.net.http.HttpClient`, UDP and accepting
  connections on a listening socket are blocked and reported. `HttpURLConnection` and client
  sockets, with or without TLS, are simulated.
- **Invalid bytecode is detected late.** There is no verifier pass; code a JVM would refuse
  to load fails when it runs, as a `VerifyError` with a `gap.invalid-bytecode` event.
- **It is tested, not proven.** A test attempts about eighty escapes and checks the real
  machine afterwards, but that is evidence, not a proof. Use `scripts/run-isolated.sh` or a
  container for live malware.
- **Speed.** About 95 million bytecode instructions per second on the development machine,
  roughly 80 to 100 times slower than JIT-compiled code.

## Extending

Family-specific behaviour belongs in a plugin, not in the core. Implement
`dev.javabyte.plugin.EmulatorPlugin`, register it with `META-INF/services`, and enable it by
adding its id under `plugins:` in the config. A plugin can add stubs, hooks and policy rules.

## Development

```bash
mvn verify
```

The test suite is described in [docs/TESTING.md](docs/TESTING.md). In short:

- **Differential tests** run the same programs on the real JVM and in the emulator and require
  identical output, including bytecode that the Java compiler never produces.
- **Simulated malware** scenarios check that each technique (persistence, C2, theft,
  encryption, sandbox checks, staged loading) is reported correctly.
- **Limit and limitation** scenarios pin down what happens at every limit and for every item
  under "Known limitations", so a limitation is always visible in the report.
- **Fuzzing** feeds the emulator damaged and invalid class files and requires a clean answer.
- **An escape suite** tries about eighty ways out and then checks the real machine.
- **Real malware** scenarios for nine MalwareBazaar samples run when the samples are supplied;
  they are never stored in this repository.

## Documentation

| Document | For |
| --- | --- |
| [docs/CONFIG.md](docs/CONFIG.md) | Every config option |
| [AGENTS.md](AGENTS.md) | Driving the tool from an AI agent or a script; report fields |
| [docs/DESIGN.md](docs/DESIGN.md) | How it works and why |
| [docs/NATIVE.md](docs/NATIVE.md) | Native libraries and the optional CPU-emulator bridge |
| [docs/SELFTEST.md](docs/SELFTEST.md) | Results against real malware |
| [docs/TESTING.md](docs/TESTING.md) | The test suite: what it proves, how to run and extend it |
| [docs/INSTALL.md](docs/INSTALL.md) | Installing the desktop app, and checking a download |
| [docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md) | The desktop app's accessibility: target, status, limits, reporting problems |
| [gui/README.md](gui/README.md) | The desktop app: building, testing, rules for its code |
| [docs/GUI-PLAN.md](docs/GUI-PLAN.md) | The desktop app's design; each phase in [docs/gui/](docs/gui/) |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Changing the code; upgrade procedures |
| [CHANGELOG.md](CHANGELOG.md) | What changed between versions |

## Licence

Copyright (C) 2026 The JavaByte contributors.

JavaByte is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. It is distributed in the hope that it will be
useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE) for the full text.

The desktop installers bundle other software under its own licences; see
[gui/packaging/THIRD-PARTY-NOTICES.txt](gui/packaging/THIRD-PARTY-NOTICES.txt).

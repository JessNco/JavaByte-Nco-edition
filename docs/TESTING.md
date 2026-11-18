# Testing

This document describes the test suite: what each part proves, how to run it, how to add to
it, and what it does not cover.

## The idea

An emulator can be wrong in four different ways, and each needs its own kind of test.

| It could... | So the suite... |
| --- | --- |
| compute the wrong result | runs harmless programs on the real JVM and in the emulator and compares the output |
| miss or misreport behaviour | runs programs that behave like malware, and real malware, and checks the report |
| let the sample out | attempts escapes and then inspects the real machine |
| fall over on hostile input | feeds it damaged and invalid class files and requires a clean answer |

A fifth part checks the promises made to callers: config format, commands, exit codes and the
report layout.

Where possible a test has no hand-written expected values. The real JVM is the reference for
correctness, and the property "never an internal error" is the reference for robustness. Tests
of reported behaviour do need stated expectations; those are written as data, not code (see
[Scenarios](#scenarios)).

## Running

```bash
mvn verify
```

That runs everything that needs no malware and no Python: about 120 tests in under a minute.
It also writes a coverage report to `target/site/jacoco/index.html`.

| To run | Command |
| --- | --- |
| One test class | `mvn verify -Dtest=SimulatedCorpusTest` |
| A longer fuzz hunt | `mvn verify -Dtest=FuzzTest -Djavabyte.fuzz.iterations=3000 -Djavabyte.fuzz.seed=77` |
| The native bridge tests | `JAVABYTE_BRIDGE_PYTHON=/path/to/venv/bin/python mvn verify` (see [NATIVE.md](NATIVE.md)) |
| The real-malware tests | `JAVABYTE_SAMPLES=samples mvn verify -Dtest=RealCorpusTest` (see [below](#real-malware)) |
| That `scripts/run-isolated.sh` runs the jar of the version in `pom.xml` | `scripts/test-run-isolated.sh` (no Java, no namespace; CI runs it) |

Tests that lack what they need are reported as *skipped*, never as passed.

The desktop app in `gui/` has its own Rust tests, browser accessibility checks, a Linux
accessibility-bus check and a network-containment test. They are described, with how to run
them, in [gui/README.md](../gui/README.md#testing). One Java test belongs to them:
`ContractSamplesTest` writes real tool output into `gui/crates/protocol/tests/tool-output/`,
and CI fails if the committed copy drifts from what the tool produces.

## The parts

| Test class | Tests | What it proves |
| --- | --- | --- |
| `DifferentialTest` | 5 | Language features, the JDK boundary, threads, stack-trace-keyed decryption, and reflection, method handles, proxies and executors print exactly what a real JVM prints, with no gap events |
| `BytecodeTest` | 5 | Bytecode the Java compiler never emits: every stack-shuffling form, JSR/RET, wide instructions, obfuscator-style `invokedynamic`; and that unverifiable code becomes a `VerifyError` |
| `SimulatedCorpusTest` | 35 | 18 malware scenarios and 16 limit and limitation scenarios (below) |
| `LoaderTest` | 7 | Second stages are followed from `URLClassLoader` (network and file), `Lookup.defineClass` and `Unsafe.defineClass`; decoy archive entries, missing manifests, bare class files and directories |
| `BehaviourTest` | 7 | `defineClass` routes, lazy loading through the sample's loader, hooks, file fixtures |
| `ContractTest` | 19 | Config loading, overrides and every validation error; each command and exit code, `disasm`, `validate` and `schema` included; report fields; that identical runs give identical reports |
| `StreamingTest` | 7 | The `--events jsonl` stream matches the report; `--progress`; a stop sent on `--control stdin`, and the end of that input, end the run and keep the report; a stop through `RunControl`; unknown stream options are usage errors; the trace survives a killed process |
| `ContractSamplesTest` | 2 | Real tool output is written to `gui/crates/protocol/tests/tool-output/`, where the desktop app's Rust tests read it (see above) |
| `report/ArtifactsTest` | 1 | Saved artifact paths use `/` on every system, so reports read the same elsewhere |
| `FuzzTest` | 5 | About 1,350 mutated inputs by default: corrupted class files, invalid instruction streams, damaged archives, and corrupted class files given to the disassembler. None may end as `internal-error`, throw, or overrun its limits |
| `EscapeTest` | 1 | About eighty escape attempts leave no file, process, thread or connection on the real machine |
| `SafetyTest` | 7 | Simulated classes override every method of the JDK class they replace; dangerous classes are not on the allow-list |
| `NativeTest` | 5 | Native library description, plugin handlers, load failures |
| `NativeBridgeTest` | 3 | A test DLL runs in the CPU emulator and calls back into Java. Needs `JAVABYTE_BRIDGE_PYTHON` |
| `RealCorpusTest` | 10 | Nine MalwareBazaar samples behave as recorded. Needs `JAVABYTE_SAMPLES` |

### Fixtures

Test programs live in `src/test/java/dev/javabyte/guest/`.

- `guest/` holds harmless programs (`Basics`, `Advanced`, `Concurrency`, `StackKeyed`,
  `Introspection`). They run on the real JVM as well, each time in a class loader of its own so
  that static state cannot leak between tests.
- `guest/sim/` holds programs that imitate malware. **They are only ever interpreted.** They
  use made-up `.test` domains and would be harmless-to-useless if run, but no test calls their
  `main`, and none should.
- `GeneratedClasses` writes class files with ASM for bytecode that cannot be expressed in Java.

## Scenarios

A scenario is a YAML file that names a sample, gives one or more configs to run it with, and
states what the report must then contain. `ScenarioRunner` does the rest. Adding a technique to
the suite usually means writing a fixture and a scenario, with no new test code.

```yaml
description: An HTTP command-and-control session driven entirely by canned server answers.
techniques: [http-c2, http-rules, command-execution]
fixture: dev.javabyte.guest.sim.HttpRat      # or, for real malware: sha256, family, source
stages:
  - name: session
    config:                                  # any emulator config; see CONFIG.md
      network:
        http:
          - {url: '/task\?id=\w+&n=1$', body: 'exec whoami /all'}
    expect:
      outcomes: [exit]                       # each must occur; internal-error never may
      stdout: ['^uninstalled$']              # a printed line must match
      notPrinted: ['^unreachable']           # no printed line may match
      events:                                # each must match between min (default 1) and max events
        - {category: process, action: exec, where: {command: '^cmd\.exe /c whoami'}, because: why this matters}
      absent:                                # none of these may occur
        - {category: net, action: connect}
      artifacts: ['dropped/.*stage\.bin$']   # a saved file must match
      maxGaps: 0
```

Values under `where` are regular expressions matched against the event's data fields. A stage
can take its sample from a file the previous stage produced, with
`sampleFromArtifact: 'dropped/.*\.jar$'`; that is how droppers are followed through their
stages. A stage marked `nativeBridge: true` runs only when the bridge is available.

Write `because:` on any expectation whose purpose is not obvious. It is printed when the
expectation fails, and it is what tells the next reader whether the test or the emulator is
wrong.

### Simulated malware (`corpus/simulated/`)

| Scenario | Technique |
| --- | --- |
| `persistence-windows`, `persistence-linux` | Run keys, scheduled tasks, startup folder; cron, autostart entries, shell profiles |
| `anti-analysis-clean`, `-detected-user`, `-detected-tools`, `-hook-bypass` | Sandbox checks by user name, process list, MAC address, debugger and sleep timing; a config hook that defeats them |
| `http-rat`, `http-rat-offline`, `http-rat-idle` | Command loop over HTTP driven by rules; no network; no commands |
| `socket-rat`, `socket-rat-tls`, `socket-rat-reconnect` | Binary protocol over a socket; TLS; reconnect loop |
| `stealer` | Credential file theft, in-memory zip, AES-GCM, HTTPS upload |
| `ransom` | Directory walk, file encryption, note, backup removal |
| `background` | Timers, scheduled executors, asynchronous futures, shutdown hooks |
| `desktop` | Screen capture, clipboard, dialogs and browser launch are blocked and reported |
| `serialized-config` | Deserialization of a config; the filter stops a gadget class |
| `file-operations` | Every common file API against the virtual disk |

### Limits and limitations (`corpus/limits/`)

Each limit has a scenario that reaches it and checks the outcome and that evidence gathered
before the limit survives.

| Scenario | Limit | Outcome |
| --- | --- | --- |
| `limit-instructions` | `limits.maxInstructions` | `instruction-limit` |
| `limit-timeout` | `limits.timeoutSeconds` | `timeout` |
| `limit-call-depth` | `limits.maxCallDepth` | A `StackOverflowError` the sample can catch |
| `limit-sleeps` | `limits.maxSleeps` | `sleep-budget` |
| `limit-blocked` | (deadlock detection) | `blocked` |
| `limit-tasks` | `limits.maxTasks` | Event `thread.task-limit-reached` |
| `limit-written-bytes` | `limits.maxWrittenBytes` | `policy` |

`limits.maxEventsPerCategory` is checked in `ContractTest`.

Each documented limitation has a scenario that pins down what happens instead. The point is
that a limitation is always visible in the report and never a silent wrong answer. When one is
lifted, its scenario fails and names the documentation to update.

| Scenario | Limitation | What the report shows |
| --- | --- | --- |
| `limitation-http-client` | `java.net.http.HttpClient` is not simulated | `policy.blocked`; no `net.http` |
| `limitation-server-socket` | Listening sockets never accept | `net.listen` with the port |
| `limitation-udp` | UDP is not simulated | `policy.blocked`; the DNS lookup is still seen |
| `limitation-virtual-time` | Time is virtual | An hour of sleep passes at once on every clock |
| `limitation-native-unbound` | Native code needs the bridge | `native.call`, result zero |
| `limitation-native-missing-library` | A library must be on the virtual disk | `UnsatisfiedLinkError` |
| `limitation-missing-class` | Missing libraries become phantoms | `gap.missing-class` |
| `limitation-nested-jar` | Jars inside the jar are not mounted | Readable as data, classes not found |
| `limitation-no-parallelism` | Threads are interleaved | Unsynchronised writers finish; the run is repeatable |

No GUI is covered by `desktop`, the deserialization filter by `serialized-config`, and
non-Windows native libraries by `NativeBridgeTest`.

## Real malware

`RealCorpusTest` runs the scenarios in `corpus/real/` against samples from
[MalwareBazaar](https://bazaar.abuse.ch/).

> **The samples are live malware. They are not in this repository and must never be added.**
> The emulator reads them as data and never starts them. Even so, fetch and test on a machine
> you can discard, and prefer `scripts/run-isolated.sh` when running the tool by hand.

```bash
pip install pyzipper
MB_AUTH_KEY=<your key> scripts/fetch-corpus.py samples
JAVABYTE_SAMPLES=samples mvn verify -Dtest=RealCorpusTest
```

`fetch-corpus.py` reads the hashes from the scenario files, so the two cannot drift apart. The
test checks each file's SHA-256 before using it. Set `JAVABYTE_SAMPLES_REQUIRED=true` to make
a missing sample a failure instead of a skip. Without samples the scenario files are still
checked for completeness and valid patterns, so they cannot rot unnoticed.

These tests are deliberately not part of CI: a public pipeline should not download malware.
Run them before a release and after any change to the interpreter, the router or the stubs.

| Scenario | Family | What is checked |
| --- | --- | --- |
| `strrat` | STRRAT | First run: decrypted config with both C2 hosts, scheduled task, startup copies, relaunch. Relaunched copy: C2 connection, WMIC fingerprinting |
| `adwind` | Adwind/jRAT | Three chained stages: dropper, RSA+AES config and four classes defined in memory, third stage |
| `ratty` | Ratty | Run key, C2, bundled keylogger DLL described; with the bridge, the two `SetWindowsHookExA` calls |
| `silentnet` | SilentNet | Relaunch argument; DNS-over-HTTPS and blockchain RPC endpoints over TLS |
| `irahook-qprotect` | IRAHook | No main class; with the mod entry point, calls hidden behind the sample's own `invokedynamic` resolve and the RPC requests appear |
| `discord-stealer` | (untagged) | About thirty profile paths probed; a planted profile is read |
| `runelite-agent` | (untagged) | Java agent entry point; DES and Blowfish strings decrypted. **Limitation:** uses `HttpClient`, so no request is recorded |
| `funrat-skidfuscator` | FunRAT (tagged) | Two layers of string decryption. **Limitation:** stops at a missing Minecraft class |
| `bombay` | Bombay | **Limitation:** stops at the missing Fabric loader; the report names the class |

Three of the nine are kept as limitation cases on purpose. A corpus of only successes would
hide where the tool stops.

## Coverage

`mvn verify` measures which lines of the emulator the tests execute. With the real samples and
the native bridge the figures are about 79% of lines and 66% of branches; without them a few
points lower.

| Package | Lines | Notes |
| --- | --- | --- |
| `config`, `model`, `shell`, `cli`, `report` | 90% or more | |
| `vm` (interpreter, router, scheduler) | 86% | The uncovered part is mostly rarely used reflection and error paths |
| `host` | 84% | |
| `stubs` | 75% | Many small stubs for rarely used JDK methods |
| `env` | 64% | By design: each simulated class overrides every method of its JDK parent, most of which no sample calls. `SafetyTest` checks they exist |
| `nativebridge` | 56% | Error handling for a failing Python process |

Coverage shows what was executed, not what was checked. The differential tests are the strong
evidence for correctness; coverage is there to point at code no test reaches.

## Cost of the live features

`trace.log` is now written line by line as events happen, and `run --events jsonl` serialises
each event as it is recorded. Both cost a fixed amount per event, not per instruction, measured
with a benchmark harness that runs the same sample repeatedly in one JVM (interleaved modes,
median of 15 rounds after warm-up; 17 November 2026):

| Cost | Per event |
| --- | --- |
| Writing and flushing a trace line | about 4 µs |
| Serialising and writing a stream record | about 3 µs |

A typical run records tens to hundreds of events, so the two add a few milliseconds at most.
The worst case is bounded by `limits.maxEventsPerCategory`: at the default 20,000 per category,
a run that fills every category spends at most about two seconds on them, in a run that must
already have executed for far longer to produce so many events.

The phase 1 plan set the budget as "at most 2% on the differential fixtures". That was the
wrong yardstick: those fixtures finish in 4–25 ms, so even microseconds per event show as 10–30%
there, while on any run lasting a second or more the cost is well under 1%. The budget is now
stated per event (under 10 µs for both together), which is what scales with use.

## What the suite does not prove

- **Containment is tested, not proven.** The escape attempts are the ones we thought of.
- **Fuzzing is shallow.** Mutations are random, not guided by coverage, and start from two
  fixtures.
- **Real behaviour is checked against the emulator's earlier output**, reviewed by hand, not
  against a run on a real machine.
- **Linux samples** are covered by simulated scenarios only; no real Linux-specific sample is
  in the corpus.
- **Performance** has no test. A change that halves interpreter speed would pass.
- **Timing inside a millisecond** is not controlled: the virtual clock advances a millisecond
  each time it is read, so the count of clock reads affects the values a sample sees.

## Adding tests

| You changed or want to check | Add |
| --- | --- |
| An opcode or a language feature | Code in a `guest/` fixture. The differential test picks it up |
| Bytecode javac cannot produce | A generator in `GeneratedClasses` and a test in `BytecodeTest` |
| How a behaviour is reported | A fixture in `guest/sim/` and a scenario in `corpus/simulated/` |
| A new limit, or a limitation lifted or added | A scenario in `corpus/limits/` and a row in the tables above |
| A config key, command, exit code or report field | A case in `ContractTest` |
| The event stream, progress or stopping a run | A case in `StreamingTest` |
| A new way out of the sandbox | An attempt in the `Escape` fixture |
| A new simulated class | A completeness assertion in `SafetyTest` |
| A sample that showed a bug | A scenario in `corpus/real/`, after reducing the bug to a fixture if you can |

When a fuzz run fails, the message names the seed and iteration and groups failures by cause.
Reproduce with the same `-Djavabyte.fuzz.seed` and iteration count.

## Defects this suite found

Writing the suite changed the emulator. Each of these was found by a new test and fixed with
it, and the test now guards the fix.

| Found by | Defect |
| --- | --- |
| Limit scenarios | An endless write to one open file exhausted the emulator's memory instead of reaching `maxWrittenBytes` |
| Fuzzing | A damaged archive threw a raw `ZipException`; unverifiable bytecode ended runs as `internal-error` |
| Contract tests | Config type errors did not name the key; `inspect` on a missing file returned exit code 1; dropped events were not flagged |
| Determinism test | Random numbers came from real entropy; `new Date()`, `Calendar` and `java.time` read the real clock |
| Differential fixture | Nested-class reflection queries; method handles with `byte`/`short`/`char`/`boolean` arguments; proxy `toString` and identity; exceptions inside `supplyAsync` |
| File scenario | `RandomAccessFile` data methods; deleting a non-empty directory succeeded |
| Simulated corpus | `NetworkInterface` instance calls; `Map.Entry` in the deserialization filter; previews of mostly-text binary data; unreported `isFile`/`isDirectory` probes |

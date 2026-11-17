# Changelog

Notable changes, newest first. The report's JSON layout has its own version,
`schemaVersion`, which changes only when a reader could break.

## Unreleased

### Added
- Desktop app, phase 4: the full report. Limits and gaps with what to try next, the files a run
  saved (viewed as text, hex or bytecode; saved as AES password zips, or as is behind a
  warning), the settings used with Run again with changes, filtering events by action and
  going to an event number, comparing any two analyses, and opening reports made on the
  command line. See docs/gui/PHASE4-PLAN.md.
- `javabyte disasm FILE [--class NAME]`: a class file, or one class in a jar, as readable
  instructions. It only parses; damaged classes give a clean error (exit code 2).
- Desktop app, phase 3: a Settings screen for every option, with forms built from the tool's
  schema and the same settings as YAML (comments kept), problems shown at their fields, nine
  presets for common next steps plus saved presets, import, export and copy as command line.
  All app text now comes from translatable resource files. See docs/gui/PHASE3-PLAN.md.
- `schema --json` lists plugins with their descriptions and settings
  (`EmulatorPlugin.description()` and `settings()`).
- Desktop app (`gui/`), phase 2: open a sample (picker, drag and drop, or on the command
  line), see what it is and choose where to start, watch and stop the analysis, and read the
  report's summary, indicators and events. Recent analyses can be reopened. See
  docs/gui/PHASE2.md.
- `run --events jsonl`: the run as JSON lines on standard output while it happens (start,
  event, progress, entry, finish; `streamVersion` 1).
- `run --progress`: progress about every half second, in the stream or on standard error.
- `run --control stdin` and Ctrl+C: stop a run early with outcome `stopped`, exit code 0, and
  the report still written. `RunControl` and `RunListener` offer the same to Java callers.
- `javabyte validate`: every config problem at once, each with its exact key, with suggestions
  for misspelt keys.
- `javabyte schema`: every config option with type, default, allowed values and description,
  from `@ConfigDoc` annotations that are now the single source for option descriptions.
- A full test suite, described in docs/TESTING.md: declarative scenarios for simulated and
  real malware, limit and limitation scenarios, generated-bytecode differential tests, contract
  tests for config, commands and the report, seeded fuzzing, and coverage reporting.
- `environment.randomSeed`: random numbers the sample does not seed itself are now repeatable.
  Set it to `null` for real entropy.
- `summary.eventsDropped` in the report, listing categories that hit the event cap.
- Event `gap.invalid-bytecode`: code a JVM verifier would reject now ends as the sample's own
  `VerifyError` instead of an emulator failure.
- `scripts/fetch-corpus.py` downloads the samples the real-malware tests use.
- Reflection on nested classes (`getEnclosingClass`, `getDeclaredClasses`, `isMemberClass`
  and related), `Proxy.isProxyClass` and `getInvocationHandler` for sample-defined interfaces,
  and `RandomAccessFile`'s binary read and write methods.
- Optional `native-bridge` plugin: runs 64-bit Windows JNI libraries in a CPU emulator
  (Speakeasy and Unicorn) and reports their Windows API calls. See docs/NATIVE.md.
- `EmulatorPlugin.close()` and `NativeHandler.NOT_HANDLED` for plugin authors.
- `EnumMap`, `EnumSet` and other JDK enum utilities work with enums defined by the sample.
- Dynamic proxies for interfaces defined by the sample.
- `schemaVersion` in the report.
- Config validation for rule patterns and numeric limits.
- Size cap on archive entries, against decompression bombs.
- Package documentation, contributing guide, CI workflow.

### Changed
- Desktop app code: every public item documented, large modules split by job, and clippy run
  in CI with warnings as errors. No change in behaviour.
- `trace.log` is flushed after every event, so a killed run keeps its trace.
- Config errors name list items by position (`network.http[2].url`), and a config with several
  problems reports all of them.
- `environment.clockStart` must be an ISO-8601 time; this used to fail only when the run began.
- `new Date()`, `Calendar.getInstance()` and the `java.time` `now()` methods follow the virtual
  clock. They used to read the real clock, which disagreed with `System.currentTimeMillis`
  after a simulated sleep.
- A damaged archive is read as far as possible and the damage reported, instead of failing.
- Deleting a directory that still has contents fails, as on a real disk.
- Config errors about a value's type name the key. `inspect` and `init` return exit code 2 for
  a missing sample.
- `System.load` and `System.loadLibrary` now fail with `UnsatisfiedLinkError` when the library
  is not on the virtual disk, as a JVM would. Libraries that unpack their own native code
  depend on that failure.
- A guest thread that can never be woken now ends with outcome `blocked` (was `sleep-budget`).
- Generated shell classes mirror the sample's class hierarchy.
- Dependencies: ASM 9.11.1, Jackson 2.23.0, picocli 4.7.7, JUnit 5.14.4.
- The tool version comes from `pom.xml` only.

### Fixed
- Saved-file paths in the report (`artifacts`, `requestBodyFile`) use `/` on every system;
  on Windows they used `\\`, which broke reading a report made on another system.
- Writing without end to one open file exhausted memory before `limits.maxWrittenBytes` applied.
- Method handles invoked with `byte`, `short`, `char` or `boolean` arguments failed.
- An exception inside `CompletableFuture.supplyAsync` or `runAsync` escaped to the caller.
- `toString()` on a proxy for a sample-defined interface did not reach the invocation handler.
- `toString()` inherited from a JDK class printed an internal class name for sample classes.
- `Enum.compareTo` failed between enum constants that have bodies.
- A sample lambda could not receive emulator stand-ins (such as a method mirror) as arguments.
- Dropped files were lost when a run ended by hard timeout.

## 0.1.0

First version.

- Bytecode interpreter with a deny-by-default JDK allow-list.
- Simulated files, network (HTTP, sockets, TLS), processes and class loading, including
  run-time class definition.
- Interleaved guest threads with monitors and `wait`/`notify`.
- YAML/JSON configuration, JSON report, command-line tool, plugin interface.
- Native library description and native-method handlers.
- Tested against six malware families; differential, escape and structural test suites.

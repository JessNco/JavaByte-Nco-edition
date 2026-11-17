# Phase 1: making the command-line tool drivable

Status: **complete** on branch `claude/gui-phase1`, pending CI on Windows.

## Goal

Give the command-line tool everything the GUI needs to drive it, so that the GUI never has to
scrape text meant for people or guess. Every addition is an ordinary, documented command-line
feature: scripts and AI agents gain from it as much as the GUI.

No screens change in this phase. The Running screen keeps its pretend data until phase 2.

## Decisions

| Question | Decision |
| --- | --- |
| Exit code for a run the person stopped | **0**, with outcome `stopped`. The tool did its job and wrote the report; exit code 3 stays for runs cut short by their own limits |
| What the event stream carries | **Every event** the report would hold. The GUI decides how much to show |
| Wait for the manual NVDA and Orca test? | **No.** This phase has no screens, so it does not depend on that result |

## Command-line additions

| # | Feature | Behaviour |
| --- | --- | --- |
| 1 | `javabyte schema --json` | Every config option: key path, type, default, allowed values, whether it repeats, and a one-line description |
| 2 | `javabyte validate [-c FILE] [-s KEY=VALUE]... [--json]` | All problems at once, each with its key path. Exit code 0 if valid, 2 if not |
| 3 | `run --events jsonl` | One JSON object per line on standard output as things happen: `start`, `event`, `progress`, `entry`, `finish`. The text summary goes to standard error instead |
| 4 | `run --progress` | A progress line about every half second: elapsed time, instructions, current entry point, events by category. In the event stream as `progress` records; otherwise on standard error |
| 5 | Graceful stop | `run --control stdin`: the line `stop`, or the end of input, ends the run with outcome `stopped` and the report is still written. Ctrl+C does the same in every run |
| 6 | `trace.log` written as it happens | Each line reaches the file before the next event, so the trace survives a killed run |

### Event stream format (`streamVersion` 1)

```json
{"type":"start","streamVersion":1,"tool":{"name":"javabyte","version":"0.1.0"},"sample":{"path":"…","sha256":"…","size":1234,"mainClass":"…"},"output":"javabyte-out"}
{"type":"event","seq":0,"category":"process","action":"exec","caller":"a/B.main:3","data":{"command":"calc.exe"}}
{"type":"progress","elapsedMs":500,"instructions":1200000,"entry":"a.B.main","events":42,"eventCounts":{"process":1}}
{"type":"entry","entry":"a.B.main","outcome":"completed","detail":null,"instructions":3400000}
{"type":"finish","exitCode":0,"report":"javabyte-out/report.json","runs":[…],"summary":{…}}
```

`streamVersion` changes only when a reader could break, like the report's `schemaVersion`.
Records may gain fields; readers ignore fields they do not know.

### How the pieces are built

- **One source for option descriptions.** Each config field gets a `@ConfigDoc` annotation
  with its description and, where the value is a choice, the allowed values. `schema`,
  `validate` and the existing checks all read it. A test fails if `docs/CONFIG.md` misses an
  option.
- **`validate` and `run` share their checks.** The config loader collects every problem
  instead of stopping at the first; `run` still refuses an invalid config with the same
  messages.
- **Stopping is cooperative.** A stop request is checked where the time and instruction limits
  are already checked, so it costs nothing extra. If the sample is stuck inside a JDK call, the
  run ends after a short grace period, as a timeout does today.
- **Progress comes from the emulator thread**, at the same checkpoint, so it never reads data
  while it is being changed.
- **Overhead budget.** Under 10 µs per event for the trace and the stream together. (The first
  version of this plan said "2% on the differential fixtures"; those finish in milliseconds, so
  a percentage there measures the wrong thing. Measured: about 4 µs and 3 µs; see
  `docs/TESTING.md`, "Cost of the live features".)

## Rust side

- **`protocol` crate:** types for the event stream records, the report, `inspect` output, the
  config schema and `validate` output.
- **Drift check:** a Java test writes real outputs from harmless fixtures into
  `gui/crates/protocol/tests/tool-output/` and fails if they differ from what is committed. The Rust
  tests read the committed files. A format change on the Java side therefore fails a build
  until both sides agree.
- **`backend::runner`:** finds Java and the jar, starts a run with the event stream and stop
  control, reads records as they arrive and keeps running counts, sends `stop`, kills the
  process only if it does not stop in time, and turns outcomes and exit codes into plain words.
  Tested against harmless fixtures only, on Linux and Windows.

## Tests and documentation

- `ContractTest`: each new command and flag, its output shape and exit codes; stopping by
  standard input; the trace surviving a killed run.
- `docs/CONFIG.md`, `AGENTS.md`, `CHANGELOG.md`, `docs/TESTING.md`, the GUI plan.

## Done when

- every feature above has tests, and the Java suite passes on Java 17, 21 and 25;
- the Rust drift check passes;
- the runner passes on Linux and Windows;
- the overhead is measured and recorded (done: about 7 µs per event in total).

## Not in this phase

Real screens (phase 2), the settings editor (phase 3), packaging and the bundled Java runtime
(phase 5).

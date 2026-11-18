# GUI plan

Status: **phases 0 to 5 built; the accessibility audit (phase 6) is next.** This document plans a desktop
interface for JavaByte for people who are not comfortable with a command line. It records the
decisions made, the design, the changes the command line needed, and the questions still open.
The code is in [`gui/`](../gui/README.md); what each phase built, and how it was tested, is in
[`docs/gui/`](gui/). Where the code and this plan differ, the phase documents say why.

## Goals

1. **Accessible first.** Meets WCAG 2.2 level AA. Everything works by keyboard and with a screen
   reader, follows the operating system's text size, contrast and motion settings, and never
   uses colour alone to carry meaning.
2. **Same power as the command line.** Every command, option and report field is reachable.
   A config made in the GUI runs unchanged on the command line, and the reverse.
3. **No slower.** Emulation speed is unchanged, and the GUI stays responsive on the largest
   reports the tool can produce.
4. **No new risk.** Text from a sample is always treated as untrusted data, and the GUI never
   gives a sample's files a way to run.

Not goals: replacing the command line, downloading samples, running the test corpus.

## Decisions so far

| Question | Decision |
| --- | --- |
| Toolkit | Tauri 2 (Rust), screens written in Rust with Leptos and compiled to WebAssembly |
| Platforms | Windows and Linux |
| Java | A trimmed Java runtime is bundled; nothing else to install |
| Code location | This repository, under `gui/` |
| Config editing | Forms for every option, plus a YAML view kept in sync |
| Accessibility target | WCAG 2.2 AA |
| Native bridge | Bundled |
| Samples | Local files only; no MalwareBazaar download, no corpus runner |
| Saving dropped files | Password-protected zip (`infected`) by default; saving as is only after a very clear warning |
| Code signing | No certificate yet; release unsigned at first with honest install instructions, and obtain signing before a wide release (see [Code signing](#code-signing)) |
| Isolation on Windows | A virtual machine is the standard guidance. A built-in sandbox is explored as an optional add-on, not shipped as standard (see [Future: Windows sandbox add-on](#future-windows-sandbox-add-on)) |
| Runs at once | One analysis at a time |
| Languages | English first; every piece of text in resource files so translations can be added |

**Why Tauri.** It draws its screens with the system web view (WebView2 on Windows, WebKitGTK on
Linux), which gives the most mature screen-reader, zoom, high-contrast and keyboard support
available to a Rust program. The cost is that accessibility on Linux depends on WebKitGTK, which
is less proven with screen readers than WebView2; phase 0 tests exactly that before anything
else is built (see [Risks](#risks)).

## Architecture

```
┌──────────────────────── JavaByte GUI (one Tauri app) ────────────────────────┐
│                                                                              │
│  Screens (Leptos → WebAssembly, in the system web view)                      │
│    semantic HTML, text only; asks the backend for data a page at a time      │
│                         │  Tauri commands and events (typed, few, audited)   │
│  Backend (Rust)         ▼                                                    │
│    • starts and stops the Java process         • keeps settings              │
│    • reads streamed events, keeps the counts   • indexes report.json         │
│    • validates and converts config (via Java)  • recent runs                 │
└─────────────────────────────│────────────────────────────────────────────────┘
                              │ command line arguments, stdin, stdout (JSON lines)
                              ▼
          bundled Java runtime → javabyte.jar (unchanged emulator)
                              │
                              └→ bundled Python → native bridge (optional, per run)
```

The GUI **drives the existing command-line tool as a separate process**. It does not embed the
emulator or reimplement any of it. This is what keeps the two in step and keeps emulation speed
exactly as it is: the GUI's work happens in a different process and only reads what the
emulator writes.

### Why not call Java in-process

Embedding a JVM in the Rust process (through JNI) would save about 0.3 seconds of start-up per
command, but would tie a crashing or runaway emulation to the window that is showing it, and
would need a second, GUI-only code path through the emulator. Process separation also means
**Stop** can always stop a run, however stuck it is.

### Layout of the code

```
gui/
  Cargo.toml                 workspace
  crates/
    protocol/                serde types: report, event, inspect output, config schema;
                             the text resources and the settings form's layout.
                             The one place the GUI knows the JSON formats.
    backend/                 process control, report index, settings (no UI code; unit tested)
  app/                       Tauri shell: window, network guard, the commands screens may call
  ui/                        Leptos screens and components
  locales/                   every word the app shows, per language (Fluent)
  tests/
    a11y/                    axe-core and keyboard checks of every screen in Chromium
    atspi/                   what the Linux accessibility bus receives from the real window
    egress/                  hostile script in the real window, in a private network
```

The plan was end-to-end tests through WebDriver (`tauri-driver`). They were not needed: the
backend tests drive the real tool, the browser checks cover the screens, and the AT-SPI check
goes through a whole analysis in the real window.

`protocol` is checked against real output from the Java tool in CI, so a change to the report
format that the GUI does not know about fails a build rather than a user's run.

## Changes needed in the command-line tool

All of these were made in phase 1 ([gui/PHASE1-PLAN.md](gui/PHASE1-PLAN.md)), and
`javabyte disasm` was added in phase 4 for the bytecode view. They are small, useful on their own, and keep the GUI from guessing. Each one is a normal,
documented command-line feature, so the command line gains rather than diverges.

| Change | Why the GUI needs it | Also helps |
| --- | --- | --- |
| `javabyte schema --json`: every config option with its type, default, allowed values and description | Forms are generated from it, so a new option appears in the GUI without GUI changes | Agents; CONFIG.md can be generated from it |
| `javabyte validate -c FILE --json`: all config errors, each with its key path | Inline errors next to the right field | Scripts, CI |
| `run --events jsonl`: each event as one JSON line on stdout as it happens | Live progress without polling files | Agents and pipelines |
| A graceful stop: `stop` on stdin ends the run like a limit, with outcome `stopped`, and still writes the report | A Stop button that keeps the results; Windows has no reliable signal for this | People pressing Ctrl+C |
| `trace.log` flushed after each line | The README promises it survives a killed run; today it is buffered, so the last events are lost | Everyone |
| `run --progress`: a status line every half second (instructions, elapsed time, current entry point) | A progress display that costs nothing when idle | Long command-line runs |

The `ConfigLoader` error messages already name the key, and `inspect --json`, `defaults --json`
and the report's `schemaVersion` already exist.

## Screens

Every screen has one `<h1>`, a landmark structure (`banner`, `navigation`, `main`), and a
logical heading order, so screen-reader users can jump by heading or region.

### 1. Start

- **Open a sample**: a button that opens the system file picker, plus drag and drop as an
  alternative (never the only way). Accepts `.jar`, `.class`, a folder, or a JavaByte config.
- **Recent runs**: a list with sample name, date, outcome in words, and number of findings.
- A short safety notice the first time, in plain language: the sample is read, not run; still
  use a virtual machine for live malware.

### 2. Sample overview (from `inspect --json`)

What the sample is before anything runs: main class or "none", entry-point candidates, class
loaders it defines, native methods and libraries, hidden call sites, largest resources,
unreadable classes.

Each finding comes with a suggested next step in plain words, taken from the table in
AGENTS.md. For example: "This sample has no main class. Choose where to start:" followed by the
candidates as buttons. The primary action is **Analyse with default settings**; the secondary
is **Change settings first**.

### 3. Settings for a run (config editor)

- **Forms tab.** Sections match CONFIG.md: Sample and entry points, Environment, Files on the
  virtual disk, Network, Programs, Hooks, Limits, Policy, Plugins, Output. Each field has a
  visible label, a one-line description from the schema, the default shown, and its own error
  message linked with `aria-describedby`. Lists (entry points, HTTP rules, hooks) are tables
  with Add, Edit, Move up/down and Remove buttons; no drag-only reordering.
- **YAML tab.** The same config as text, in a plain, accessible text area with line numbers
  shown beside it (not inside it). Editing either tab updates the other. Parse errors name the
  line and the key.
- **Validation** runs through `javabyte validate` on demand and a short pause after typing.
  Errors are listed in a summary at the top that links to each field, and announced politely.
- **Import / Export / Copy as command line**: load an existing YAML or JSON config, save one,
  or copy the equivalent `javabyte run ...` command. This is how GUI and command-line users
  share work.
- A **Presets** menu gives the common follow-up runs from AGENTS.md ("relaunch with the
  arguments the sample used", "act as the copy the sample installed", "supply the file it
  looked for"). Each preset only fills in form fields that the user can see and change.

### 4. Running

- Status in words: entry point, elapsed time, instructions executed, events so far by category.
- A short live list of the most important events (processes, network, files written, classes
  defined), newest first.
- **Stop** (keeps results) and a keyboard shortcut for it.
- Only one analysis runs at a time. While it runs, **Analyse** elsewhere in the app is
  disabled with a visible reason ("An analysis is running. Stop it or wait for it to finish."),
  rather than queued or hidden.
- Screen readers hear a short summary at most every few seconds ("12 events, 1 network
  connection"), never one announcement per event, and a clear message when the run ends.

### 5. Report

Tabs, each reachable by keyboard and with its own heading:

| Tab | Content |
| --- | --- |
| **Summary** | A plain-language paragraph first ("The sample tried to start 2 programs, connected to 1 server and wrote 3 files. 1 part could not be emulated faithfully."), then outcome per entry point, then the summary counts |
| **Indicators** | Programs started, network (HTTP, connections, DNS), files written, classes defined, native libraries, decrypted strings and crypto output. Each list can be copied as text |
| **Events** | Every event in an accessible data grid: filter by category and action, full-text search, jump to sequence number. Rows are fetched a page at a time from the backend |
| **Limits and gaps** | Blocked calls, gap events, dropped-event counts, why the run stopped, each with what it means and what to try next |
| **Files** | Artifacts: dropped files, defined classes, network payloads. View as text or hex inside the app; save a copy (see [Saving files a sample produced](#saving-files-a-sample-produced)) |
| **Settings used** | The effective config, with **Run again with changes** |

**Compare runs** puts two reports side by side and lists what was added or removed per
indicator. Runs are repeatable (seeded randomness, fixed clock), so differences are real.

### 6. Tools

Read-only viewers for the host policy (`javabyte policy --json`: each rule, whether it allows or
blocks, its group and the policy file's explanation) and every option with its default, type and
meaning (from `javabyte schema --json`, which also covers plugin settings). Each has a search that
matches every typed word in any column, with a live count.

### 7. Preferences

Java runtime (bundled, or a chosen Java 17+), native bridge status, default output folder,
appearance (follow system, light, dark, high contrast), text size, reduced motion, line
spacing, and the isolation setting on Linux (below).

## Command-line parity

| Command line | GUI |
| --- | --- |
| `run SAMPLE` | Open → Analyse with default settings |
| `run -c FILE` | Open a config file, or Settings → Import |
| `run -s KEY=VALUE` | The corresponding form field; the YAML tab for anything else |
| `run -o DIR` | Settings → Output, and Preferences default |
| `run --json` | Report → Save report (the same `report.json`) |
| `inspect [--json]` | Sample overview; Save as JSON |
| `init SAMPLE` | Settings → Start from this sample's suggestions; Export |
| `defaults [--json]` | Tools → Defaults |
| `policy [--json]` | Tools → Policy |
| `disasm FILE [--class NAME]` | Report → Files → a saved class, shown as bytecode |
| exit codes 0/1/2/3 | Outcome shown in words; 1 is shown as a tool fault with a "copy details" button |
| `scripts/run-isolated.sh` | Linux: Preferences → "Run without network access" (on by default) |
| Plugins (`plugins:`) | Settings → Plugins, from the schema |
| Native bridge | Settings → Plugins → Native bridge, available because it is bundled |

A CI test reads `javabyte schema --json` and fails if any option has no form field, so parity
cannot quietly drift.

## Accessibility

Target: **WCAG 2.2 AA**, checked automatically and by hand.

**Structure and semantics.** Native HTML controls wherever one exists (`button`, `input`,
`select`, `table`, `details`). ARIA only where HTML has nothing, following the ARIA Authoring
Practices patterns (tabs, grid, listbox). Every control has a visible label that matches its
accessible name.

**Keyboard.** Everything works without a mouse, in a logical tab order, with a visible focus
indicator at least 2 px thick and 3:1 contrast that is never hidden behind sticky headers
(WCAG 2.4.11). A "skip to content" link. Shortcuts always use a modifier key, are listed in a
help dialog, and can be changed. The events grid uses arrow keys inside and a single tab stop.

**Screen readers.** Tested with NVDA on Windows and Orca on Linux. Live regions are polite and
throttled. Tables have header cells and captions. Long-running states are announced at start
and finish. Icons always come with text or an accessible name.

**Vision.** Text and controls follow the system text size and work at 200% zoom and 400% reflow
without horizontal scrolling of whole pages (WCAG 1.4.10; the events grid scrolls by itself).
Contrast at least 4.5:1 for text and 3:1 for controls in every theme. Windows high-contrast
mode is respected via `forced-colors`. Severity and outcome are always written in words, with
colour and shape only as extras.

**Motion and timing.** No time limits. No animation when the system asks for reduced motion,
and none that is needed to understand anything.

**Language and errors.** Plain language throughout; technical terms explained the first time
(for example "entry point: where the analysis starts running the sample"). Error messages say
what happened and what to do. Choices made earlier are remembered so they need not be entered
again (WCAG 3.3.7). Targets at least 24 by 24 pixels (WCAG 2.5.8).

**Untrusted text is still accessible.** Strings from a sample can be very long, contain control
characters, or mimic interface text. They are shown in clearly labelled containers, truncated
with an explicit "show all", and control characters are shown as visible escapes (`\x07`) so
neither eyes nor screen readers are misled.

**How it is checked.**
- axe-core runs on every screen in the end-to-end tests in CI; any violation fails the build.
- A written manual test script per screen for keyboard only, NVDA, Orca, 200% zoom and high
  contrast, run before each release.
- Sessions with real users, including people who use screen readers and people new to the
  command line, at the end of phases 2 and 4.

## Performance

- **Emulation is unaffected.** The GUI is a different process. The only extra work in Java is
  writing events to stdout, which the trace file already does; it is measured in phase 1 and
  must cost under 2% on the differential fixtures, or it is buffered.
- **Large reports.** A report can hold hundreds of thousands of events. The backend reads
  `report.json` once into a compact index (streaming parse with serde) and sends the web view
  only the rows on screen. The web view never receives the whole report.
- **Live updates** are batched and drawn at most ten times a second, whatever the event rate.
- **Start-up.** The window appears before Java starts; Java is only started when a command
  needs it. Target: window in under a second, inspect result in under two on a typical laptop.
- **Footprint.** Expected idle memory 80–150 MB (web view included), and nothing running in
  the background when no analysis is.

## Security

- **The sample is only ever passed as a path** to the Java tool, which reads it as data. The
  GUI never loads, extracts or opens it itself.
- **Nothing a sample produced is ever opened with another program.** No "open with default
  app", no shell execution, no link following. Dropped files, payloads and defined classes are
  shown as text or hex inside the app.
- **All sample text is inserted as text**, never as HTML; Leptos escapes by default and the
  code forbids raw HTML insertion (checked by a lint).
- **The window cannot reach the network**, in three independent layers, so that a mistake in
  one does not open it:
  1. A strict content security policy: only the app's own packaged files, no inline scripts,
     no frames, workers, plugins, media, forms or `<base>`.
  2. A navigation guard in Rust that refuses any navigation away from the app's own origin
     (links, forms, `window.location`, meta refresh), which the policy does not cover. WebRTC
     is removed before any page script runs, because it sends UDP outside the policy.
  3. A black-hole proxy: all of the web view's network traffic goes to a closed port on the
     computer itself (`127.0.0.1:9`). This also stops what neither of the others can, such as
     `<link rel="preconnect">` and the connection the engine opens when a link is clicked,
     both of which look up the host name (which can carry stolen data) before any check. On
     Windows the proxy is also told not to exempt `localhost`, which Chromium otherwise
     sends direct.
  The app's files and its channel to the backend are served inside the process, not over the
  network, so none of this affects them. `gui/tests/egress` attacks all three from inside the
  real window; see [gui/PHASE0-TESTING.md](gui/PHASE0-TESTING.md#network-containment).
- **Tauri permissions are minimal**: the screens can call a short list of typed backend
  commands and nothing else; file access is limited to files the user picked and the output
  folders.
- **Linux isolation**: runs start in a network namespace with no network by default, as
  `scripts/run-isolated.sh` does today. The setting can be turned off, with an explanation.
- **Windows** has no equivalent that works without administrator rights. The GUI says so
  plainly on the first run and in Preferences, and recommends running JavaByte inside a
  virtual machine. A built-in sandbox may come later as an add-on (see below).

### Saving files a sample produced

Dropped files, payloads and run-time-defined classes may be live malware: a dropped `.jar` or
`.exe` is exactly what the sample meant to run.

- **Default: password-protected zip.** **Save a copy** writes a zip encrypted with AES-256 and
  the password `infected`, the convention MalwareBazaar and most analysis tools use. The zip
  holds the file under its original name plus a small text file with its SHA-256, the run it
  came from and a warning. Several files can be saved into one zip. ZipCrypto (the old, weak
  zip encryption) is not used, so the archive also passes mail and antivirus filters that
  reject it.
- **Save as is, behind a very clear warning.** A separate, secondary button, never the
  default. It opens an alert dialog that:
  - says in plain words what the file is and what can happen if it is opened ("This file was
    created by the sample. It may be malware. Opening it may infect this computer.");
  - gives the file's name, type and SHA-256;
  - requires ticking "I understand this file may be harmful" before **Save as is** becomes
    available, and puts keyboard focus on **Cancel**;
  - uses no countdown or timer, so it works for everyone (WCAG 2.2.1), and is announced in
    full by screen readers (`role="alertdialog"`, with the warning as its description).
  The choice is recorded in the run's log. The warning cannot be switched off permanently.
- The GUI never offers to open a saved file, and never saves into the folder the sample came
  from.

## Packaging

| | Windows | Linux |
| --- | --- | --- |
| Installer | MSI (and a portable zip) | AppImage and `.deb` |
| Web view | WebView2, present on Windows 10/11; bootstrapper included | WebKitGTK 4.1 from the system |
| Java | `jlink` runtime with only the modules JavaByte uses | same |
| Native bridge | Bundled standalone Python with Speakeasy and Unicorn, wheels pre-built in CI | same |

### Code signing

There is no code-signing certificate yet. Without one, Windows SmartScreen shows "Windows
protected your PC" when the installer is first run, which is likely to put off the people this
GUI is for. The plan:

1. **Early releases unsigned**, with install instructions on the download page and in the
   README that show the SmartScreen screen, explain why it appears, and list the SHA-256 of
   each installer so it can be checked. Linux packages are signed with a GPG key, which needs
   no certificate authority.
2. **Before a wide release, obtain signing.** Options to compare at that time, as prices and
   eligibility change:
   - **Microsoft's cloud signing service** (Azure Trusted Signing): low monthly cost and no
     hardware token, but eligibility for individuals depends on country and identity checks.
   - **SignPath Foundation**: free signing for open-source projects that meet its conditions,
     with signing done in CI from the public repository.
   - **A standard OV certificate** from a certificate authority: requires a hardware token or
     cloud key storage under current rules.
   Note that since 2024 no type of certificate, including EV, gives immediate SmartScreen
   reputation; a signed installer builds reputation as people install it.
3. The release pipeline is built so that signing is one added step: unsigned and signed builds
   come from the same CI job.

Rough sizes: app 10–15 MB, Java runtime 40–60 MB, JavaByte 4 MB, native bridge 150–250 MB.
Total roughly 200–330 MB installed. The bridge is most of it; it can become a separate
download later without changing the design.

The Java tool and the GUI share one version number and are released together.

## Text and translation

English is the only language at first, but nothing in the code holds user-visible text.

- All text lives in [Fluent](https://projectfluent.org/) resource files (`gui/locales/en/`), shared by the screens and the backend,
  read through the `fluent` crates. Fluent handles plurals and grammar properly ("1 file",
  "3 files"), which matters for the plain-language summaries.
- The page's `lang` attribute is set from the active language, so screen readers use the right
  voice and pronunciation. Text that comes from a sample is marked as of unknown language.
- Labels, help text, errors and the plain-language summaries all come from the resource files.
  A test fails if a screen contains text that is not from a resource.
- Layouts allow for text up to twice as long as the English, which translations often need and
  which also covers large text sizes.
- A short writing guide sits next to the English resources: plain words, short sentences,
  terms explained on first use, the same word for the same thing everywhere.

## Phases

| Phase | Content | Done when |
| --- | --- | --- |
| 0. Spike | Tauri + Leptos window with a form, a tab set, a large data grid and a live region, on Windows and Linux | NVDA and Orca read and operate all four correctly; the grid scrolls 300,000 rows smoothly. Go or no-go for WebKitGTK |
| 1. Command-line additions | `schema`, `validate`, `--events jsonl`, graceful stop, trace flushing, `--progress`; `protocol` crate; see [gui/PHASE1-PLAN.md](gui/PHASE1-PLAN.md) | Tests for each; event streaming overhead measured |
| 2. Open, inspect, run, summary | Start, Sample overview, Running, Report summary and indicators; see [gui/PHASE2.md](gui/PHASE2.md) | First user sessions |
| 3. Config editor | Generated forms, YAML tab, validation, import/export, presets; see [gui/PHASE3-PLAN.md](gui/PHASE3-PLAN.md) | Parity test passes for every option |
| 4. Full report | Events grid, limits and gaps, files viewer, compare; see [gui/PHASE4-PLAN.md](gui/PHASE4-PLAN.md) | Second user sessions |
| 5. Packaging | jlink runtime, bundled bridge, installers, Linux isolation, GPG-signed Linux packages, unsigned-install instructions; see [gui/PHASE5-PLAN.md](gui/PHASE5-PLAN.md) | Clean install and first run on fresh Windows 11 and Ubuntu LTS machines |
| 6. Accessibility audit | Full manual script, fixes, accessibility statement | No open AA issues |

## Testing

- `protocol` and `backend`: Rust unit tests, plus round-trip tests against real JSON produced
  by the Java test suite.
- End-to-end: the backend tests run the real tool on fixtures from the existing suite; axe-core
  and keyboard checks run on each screen in Chromium (`gui/tests/a11y`); on Linux the AT-SPI
  check goes through a whole analysis in the real window (`gui/tests/atspi`). WebDriver
  (`tauri-driver`) was planned but not needed.
- Text: no words for people in code, and no missing or unused messages (`ui_text`).
- Network containment: a hostile script in the real window reaches nothing (`gui/tests/egress`).
- Parity test from the config schema.
- The existing Java suite is unchanged and still the authority on emulation.
- The GUI's own tests use only the harmless and simulated fixtures; no real malware.

## Risks

| Risk | Mitigation |
| --- | --- |
| WebKitGTK's screen-reader support on Linux is weaker than WebView2's | Phase 0 tests it first. If it fails, the fallback is a Linux build with Slint or GTK 4 sharing the same backend and protocol crates, at the cost of a second set of screens |
| Accessible data grids are hard to get right | Use the ARIA grid pattern as written, test with both screen readers in phase 0, and offer a plain paginated table view as an alternative |
| Leptos and Tauri change faster than the Java side | Pin versions; the `protocol` and `backend` crates do not depend on either |
| Bundled Python adds size and update work | Pre-built in CI from pinned versions; can later move to a separate download |
| An analyst exports a dropped file and runs it | Encrypted zip by default; "save as is" only after an explicit warning; the GUI never opens saved files |
| Unsigned installer puts off new users | Clear install instructions with published hashes now; signing before a wide release |

## Future: Windows sandbox add-on

Not part of the first release. On Windows the standard guidance stays "run JavaByte in a
virtual machine". This section records what an optional add-on could look like, so that the
first release leaves room for it.

**What it would add.** On Linux, runs already have no network. The add-on would give Windows a
comparable second layer: if the emulator ever had a flaw, the Java process still could not
reach the network or most of the disk.

**Room left in the first release.** The backend starts Java through a small "launcher"
interface. The first release has two launchers: plain (Windows) and network namespace (Linux).
An add-on supplies another launcher, and Preferences lists whichever are installed. Nothing
else in the GUI needs to change.

**Options to explore**

| Option | How it works | Needs administrator rights | Strengths | Weaknesses |
| --- | --- | --- | --- | --- |
| AppContainer | Starts Java in a Windows AppContainer with no network capability and access only to the sample, the runtime and the output folder | No | Built into Windows 10/11 Home and up; light; nothing to install | Folder permissions must be granted per run; Java and the bundled Python must be tested inside it; significant engineering |
| Windows Sandbox | Starts a throwaway Windows desktop from a `.wsb` file with networking off and only the sample and output folders mapped | No, once the feature is enabled (enabling it does need administrator rights) | Strong isolation from Microsoft; everything inside is discarded afterwards | Windows Pro, Enterprise or Education only; virtualisation must be on; slower start (tens of seconds); one sandbox at a time |
| Restricted token and job object | Starts Java with a reduced-privilege token, in a job object limiting processes and memory | No | Simple; works on every edition | No network blocking on its own; weaker than the others |
| Firewall rule for the bundled Java | Blocks network access for the bundled `java.exe` | Yes, once at install time | Easy to understand | Needs administrator rights; network only |

A likely shape is a separately downloaded add-on that offers AppContainer where it works and
Windows Sandbox where it is available, with job-object limits in both. It would ship only after
its own escape testing, along the lines of the existing `EscapeTest`, run inside each launcher.

**Questions for then:** which Windows editions to support; whether a slower but stronger
option (Windows Sandbox) is acceptable for the people using it; and how the add-on is
distributed and signed.

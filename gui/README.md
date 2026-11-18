# JavaByte desktop app

A graphical interface for JavaByte, for people who prefer not to use a command line. The design
is in [docs/GUI-PLAN.md](../docs/GUI-PLAN.md).

**Status: phase 5 of 6.** The app opens a sample (or an earlier report, including one made on the
command line), shows what it is, lets people change every setting, runs the analysis, and shows
the whole report: summary, indicators, events, limits and gaps, saved files (viewed as text, hex
or bytecode, kept as password zips), the settings used, and a comparison of any two analyses.
It is packaged as installers for Windows and Linux that bring their own Java, and on Linux it
runs analyses with no network. The accessibility audit comes next (phase 6). What each phase
built and how it is tested: [phase 2](../docs/gui/PHASE2.md),
[phase 3](../docs/gui/PHASE3-PLAN.md), [phase 4](../docs/gui/PHASE4-PLAN.md),
[phase 5](../docs/gui/PHASE5-PLAN.md); the phase 0 accessibility trial is in
[docs/gui/PHASE0-TESTING.md](../docs/gui/PHASE0-TESTING.md).

All text the app shows is in [`locales/en/`](locales/en/), with a short writing guide.

Installing it: [docs/INSTALL.md](../docs/INSTALL.md). Building the installers:
[packaging/README.md](packaging/README.md). Releasing: [docs/gui/RELEASING.md](../docs/gui/RELEASING.md).

A build from source has no bundled Java or jar. It uses `JAVABYTE_JAVA`, `JAVA_HOME` or `java`
on the path, and the tool's jar from `JAVABYTE_JAR`, a `javabyte.jar` next to the app, or the
`target/` folder after `mvn package` at the repository root.

## Layout

| Folder | What | Built with |
| --- | --- | --- |
| `crates/protocol` | Data types shared by screens and backend: the tool's stream and report (`stream`, `report`), sample facts (`inspect`), event views, limits and comparisons; safe display of untrusted text (`display`); the text resources (`i18n`); the settings form's layout (`form`) and the schema it is checked against (`schema`, `validate`) | Rust |
| `crates/backend` | Starting and watching the command-line tool (`runner`); the bundled parts and the app's preferences (`setup`); runs without network on Linux (`isolation`); one window's sample, settings, run, report and recent list (`session/`); settings as YAML edited in place, and the matching command line (`config/`); saved files, pages and password zips (`artifacts`); event storage, filtering and paging (`events`); report reading (`report`); the pretend run and made-up events for browser tests (`simulation`, `synthetic`) | Rust, no UI code |
| `ui` | The screens (`screens/`, with the settings editor in `screens/settings/`), the event grid and table (`events/`), and the bridge to the app or, in a plain browser, to the pretend backend (`bridge.rs`) | Rust (Leptos), compiled to WebAssembly with Trunk |
| `locales` | Every word the app shows, per language | Fluent |
| `app` | The desktop window, its network guard (`guard.rs`) and the commands the screens may call (`main.rs`) | Rust (Tauri 2) |
| `packaging` | Installers: staging the Java runtime, jar and bridge, the Tauri bundle settings, the Linux AppArmor profile and the Windows "Open with" entry | Bash, JSON, WiX |
| `tests/a11y` | Automated accessibility checks in Chromium | Node.js, Playwright, axe-core |
| `tests/atspi` | What the Linux accessibility bus receives from the real window | Python, AT-SPI |
| `tests/egress` | Hostile script inside the real window, in a private network with logging listeners | Python, Bash |

## Building

Requirements: Rust (stable), the WebAssembly target and Trunk.

```bash
rustup target add wasm32-unknown-unknown
```

```bash
cargo install --locked trunk@0.22.1
```

Linux also needs WebKitGTK and its build files (Debian and Ubuntu package names):

```bash
sudo apt-get install libwebkit2gtk-4.1-dev libgtk-3-dev librsvg2-dev
```

Windows needs the Microsoft C++ build tools; WebView2 is part of Windows 10 and 11.

Build the screens first, then the app (from this folder):

```bash
(cd ui && trunk build --release)
```

```bash
cargo build --release -p javabyte-gui
```

The app is `target/release/javabyte-gui` (`.exe` on Windows). A file named as the first
argument is opened at start, as if it had been dropped on the window:

```bash
target/release/javabyte-gui path/to/sample.jar
```

`--self-check` prints what the app found (Java, the jar, the native bridge, network isolation,
the output folder) and whether the tool runs, without opening a window.

## Testing

The desktop app embeds the built screens, so build them before testing it:

```bash
(cd ui && trunk build --release)
```

```bash
cargo test
```

Three tests keep the code honest without anyone remembering to:

- `crates/protocol/tests/ui_text.rs` fails on text for people written in code instead of
  `locales/`, on a message the code names that does not exist, and on a message nothing uses.
  A line that only looks like words (a key, a command) can end with `// not-ui-text: why`.
- `crates/protocol/tests/form_parity.rs` checks the settings form against the tool's config
  schema (`crates/protocol/tests/tool-output/schema.json`): every option has a place, a widget and a label.
- `crates/backend/tests/config_edits.rs` makes thousands of random edits to configs with
  comments and checks an independent YAML reader reads what was meant and no comment was lost.
  It uses a fixed seed; set `CONFIG_EDITS_SEED` to try others.

`crates/protocol/tests/tool-output/` holds real output of the command-line tool, written by the Java
test `ContractSamplesTest`; the Rust tests read it, so the two cannot drift apart. The runner
tests drive the real tool on harmless fixtures and need it built (`mvn package` at the
repository root):

```bash
JAVABYTE_JAR=$PWD/../target/javabyte-0.1.0.jar JAVABYTE_TEST_CLASSES=$PWD/../target/test-classes cargo test -p javabyte-gui-backend
```

The accessibility checks run the screens in Chromium:

```bash
(cd ui && trunk build --release)
```

```bash
cd tests/a11y && npm install && npx playwright install chromium && node run.mjs ../../ui/dist
```

On Linux, the accessibility-bus check starts the real app on a virtual display. With
`--sample`, the app opens that file and the check goes through a whole analysis to the event
grid, pressing buttons through the bus. Use only a harmless sample, such as one of the test
fixtures packed into a jar:

```bash
jar --create --file /tmp/http-rat.jar --main-class dev.javabyte.guest.sim.HttpRat -C ../target/test-classes dev/javabyte/guest/sim/HttpRat.class
```

```bash
PYTHON=/usr/bin/python3 tests/atspi/run.sh target/release/javabyte-gui --sample /tmp/http-rat.jar --print
```

`PYTHON` must be the Python that the system's `python3-pyatspi` package was built for.

The network-containment test attacks the real window from inside (Linux, needs root for a
private network namespace). Build the probe and the control (every protection removed), then
run both:

```bash
cargo build --release -p javabyte-gui --features egress-probe && cp target/release/javabyte-gui /tmp/probe
```

```bash
TAURI_CONFIG='{"app":{"security":{"csp":null},"windows":[{"label":"main","title":"JavaByte"}]}}' cargo build --release -p javabyte-gui --features egress-control && cp target/release/javabyte-gui /tmp/control
```

```bash
sudo tests/egress/run.sh /tmp/probe
```

```bash
sudo tests/egress/run.sh /tmp/control expect-leaks
```

Rebuild without features afterwards: the probe builds must never be given to anyone.

## Rules for this code

- **Text from a sample is untrusted.** Pass it through `display_text` and insert it as text.
  Never use `inner_html` or anything that parses markup.
- **The window has no network.** Three layers (policy, navigation guard, black-hole proxy)
  are described in `app/src/guard.rs`. Do not loosen any of them; `cargo test` and
  `tests/egress` will fail if you do.
- **The screens can call only the commands in `app/src/main.rs`.** Adding one is a security
  decision: it must take typed data, and must not open files, start processes or reach the
  network on the screens' behalf.
- **Accessibility is part of done.** New screens pass `tests/a11y` and the relevant part of the
  manual script.

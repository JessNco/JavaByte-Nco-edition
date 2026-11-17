# JavaByte desktop app

A graphical interface for JavaByte, for people who prefer not to use a command line. The design
is in [docs/GUI-PLAN.md](../docs/GUI-PLAN.md).

**Status: phase 2.** The app opens a sample, shows what it is, runs the analysis with the
command-line tool, and shows the report's summary, indicators and events. The settings editor
(phase 3) and the remaining report tabs (phase 4) come next. What is built and how it is tested
is in [docs/gui/PHASE2.md](../docs/gui/PHASE2.md); the phase 0 accessibility trial and its
result are in [docs/gui/PHASE0-TESTING.md](../docs/gui/PHASE0-TESTING.md).

The app needs `java` on the path and the tool's jar: set `JAVABYTE_JAR`, put `javabyte.jar`
next to the app, or build it with `mvn package` at the repository root.

## Layout

| Folder | What | Built with |
| --- | --- | --- |
| `crates/protocol` | Data types shared by screens and backend; safe display of untrusted text | Rust |
| `crates/backend` | Starting and watching the command-line tool (`runner`); one window's sample, run, report and recent list (`session`); event storage, filtering and paging; report reading; the pretend run for browser tests | Rust, no UI code |
| `ui` | The screens | Rust (Leptos), compiled to WebAssembly with Trunk |
| `app` | The desktop window and the commands the screens may call | Rust (Tauri 2) |
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

The app is `target/release/javabyte-gui` (`.exe` on Windows). To show the events of a real
report instead of made-up ones:

```bash
target/release/javabyte-gui --report path/to/report.json
```

## Testing

The desktop app embeds the built screens, so build them before testing it:

```bash
(cd ui && trunk build --release)
```

```bash
cargo test
```

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

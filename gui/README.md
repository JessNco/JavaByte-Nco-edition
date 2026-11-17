# JavaByte desktop app

A graphical interface for JavaByte, for people who prefer not to use a command line. The design
is in [docs/GUI-PLAN.md](../docs/GUI-PLAN.md).

**Status: phase 0, an accessibility trial.** The app contains one example of each hard
component (a form with errors, tabs, a 300,000-row event list, live progress) so that screen
reader, keyboard, zoom and high-contrast support can be tested on Windows and Linux before the
real screens are built. It does not analyse anything yet. How to test it, and what decides
whether the design goes ahead, is in
[docs/gui/PHASE0-TESTING.md](../docs/gui/PHASE0-TESTING.md).

## Layout

| Folder | What | Built with |
| --- | --- | --- |
| `crates/protocol` | Data types shared by screens and backend; safe display of untrusted text | Rust |
| `crates/backend` | Event storage, filtering and paging; report reading; the pretend run | Rust, no UI code |
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

```bash
cargo test
```

The accessibility checks run the screens in Chromium:

```bash
(cd ui && trunk build --release)
```

```bash
cd tests/a11y && npm install && npx playwright install chromium && node run.mjs ../../ui/dist
```

On Linux, the accessibility-bus check starts the real app on a virtual display:

```bash
PYTHON=/usr/bin/python3 tests/atspi/run.sh target/release/javabyte-gui --print
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

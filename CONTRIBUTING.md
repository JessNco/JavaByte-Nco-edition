# Contributing and maintenance

This guide is for whoever changes JavaByte next, a person or an AI agent. Read
[docs/DESIGN.md](docs/DESIGN.md) first for how the pieces fit.

## Building and testing

```bash
mvn verify
```

Every change should keep all tests passing on Java 17, 21 and 25. CI runs all three.

The suite is described in [docs/TESTING.md](docs/TESTING.md): what each part proves, the
scenario format, and which kind of test to add for which kind of change. The short version:

| Changed | Run at least |
| --- | --- |
| Interpreter, router, type handling | `DifferentialTest`, `BytecodeTest`, `FuzzTest` |
| A stub or a simulated class | `SimulatedCorpusTest`, `SafetyTest`, `EscapeTest` |
| Config, command line, report | `ContractTest` |
| Anything, before a release | Everything, plus `RealCorpusTest` with the samples and `NativeBridgeTest` with the bridge |

The quickest way to find an interpreter bug is to add code to a fixture in
`src/test/java/dev/javabyte/guest/` (`Basics`, `Advanced`, `Concurrency`, `Introspection`).
Those programs are harmless and run on the real JVM too, so the test needs no hand-written
expectations.

Fixtures that imitate malware (everything in `guest/sim/`, and `Dropper`, `Stage2`, `Escape`,
`Reflective`) are only ever interpreted. Never call their `main` from a test.

The desktop app in `gui/` is Rust and has its own build and tests, described in
[gui/README.md](gui/README.md). Run them when you change it, and also when you change the
command line, the config or the report, because the app reads all three:

```bash
cd gui && (cd ui && trunk build --release) && cargo test && cargo clippy --all-targets -- -D warnings
```

CI runs them on Linux and Windows, with the accessibility checks and the network-containment
test, in the `GUI` workflow.

Expectations in `ContractTest` are the public interface. Changing one means a caller somewhere
may break: record it in the changelog, and raise `schemaVersion` if a report field was removed,
renamed or redefined.

## Conventions

- **Comments say why.** A comment should explain a decision or a constraint that the code
  cannot show. Do not restate what a line does.
- **Plain language.** Prefer short sentences and ordinary words in comments, messages and
  documentation. Spell a term out the first time it appears.
- **Messages for users name the fix.** A config error says which key is wrong and what is
  allowed.
- **No output that relies on colour or symbols alone.** Reports are plain text and JSON so
  that screen readers, log viewers and other tools can all consume them.
- **One job per class.** Stubs are grouped by JDK area, one class each. If a class passes
  about 600 lines, look for a second job hiding in it.
- **Guest values are never trusted.** Do not call `toString()`, `equals()` or `hashCode()` on
  a guest object from emulator code; use `Values.describe`. Bounds-check anything parsed from
  a sample.
- Formatting follows `.editorconfig` (4 spaces, 140 columns).

In the desktop app, also:

- **Every word people see is in `gui/locales/en/`**, named from code with `t!`. The `ui_text`
  test fails on text written in code, on a missing message and on an unused one.
- **Text from a sample or a report is untrusted.** Show it through `display_text`, as text.
- **A new command for the screens is a security decision.** See the rules in
  [gui/README.md](gui/README.md#rules-for-this-code); `tests/egress` checks the window still
  has no network.
- **Accessibility is part of done.** A new screen or control passes `gui/tests/a11y`, works by
  keyboard alone, and announces changes through the shared announcer.
- **Public items in `protocol` and `backend` are documented**: both crates warn on
  `missing_docs`, and CI's lint step (`cargo clippy -- -D warnings`) fails on any warning.
- **Shared page state** (the flow, the settings, the announcer) lives in `thread_local` cells
  rather than Leptos context, because timers and async code cannot reach context.

## Common changes

### Adding a stub for a JDK API

1. Find where the capability begins, usually a constructor or a static factory.
2. If the API returns an object, prefer a `Sim*` class in `env` that extends the JDK type and
   overrides every public method, and add a completeness assertion to `SafetyTest`.
3. Register it in the matching class under `stubs` with `vm.stubs.on(...)`.
4. Record an event for anything an analyst would want to know.
5. Leave the member off the allow-list.
6. Add the escape route to the `Escape` fixture if it could reach the disk, network or a thread.

### Allowing a JDK member

Edit `src/main/resources/dev/javabyte/default-policy.txt`. The test for inclusion is at the top
of that file: the member must not be able to read or change anything outside the emulator's
own memory. Prefer naming classes over package wildcards.

### Adding a config option

Add the field, with its default and a Javadoc comment, to `EmulatorConfig`; validate it in
`ConfigLoader.validate` if it has rules; document it in `docs/CONFIG.md`; add it to
`examples/full.yaml`.

The desktop app's parity test then fails until the option has a form field. Add its label
(`opt-<path with dashes>`), and words for each value of a choice (`choice-<path>-<value>`), to
`gui/locales/en/settings.ftl`. If most people will need it, add its path to `COMMON` in
`gui/crates/protocol/src/form.rs` so it shows without opening "More settings". Then regenerate
the app's copy of the schema as described under [Changing the report](#changing-the-report).

### Changing the report

Adding a field needs no version change. Removing or renaming a field, or changing what one
means, requires increasing `Report.SCHEMA_VERSION` and a note in `CHANGELOG.md` and `AGENTS.md`.

The desktop app reads real output of the tool, kept in `gui/crates/protocol/tests/tool-output/`.
After changing the report, the event stream, `inspect`, `schema` or `validate`, regenerate it
and commit the result:

```bash
mvn test -Dtest=ContractSamplesTest -Djavabyte.updateSamples=true
```

Then update the matching types in `gui/crates/protocol/src` until `cargo test` passes in
`gui/`. CI's drift check fails if the committed output no longer matches the tool's.

## Upgrading

### A new JDK release

1. Add the version to the CI matrix and run the tests on it.
2. `SafetyTest` fails if the JDK added a public method to `File`, `Socket` or `SSLSocket` that
   a simulated class does not override. Add the override.
3. Skim the release notes for new APIs that start threads, open files or sockets, or load
   code, in packages the policy allows by wildcard (`java/util/**` is the widest). Deny or
   stub them, and add them to the `Escape` fixture.
4. If the JDK removed an API that samples still use, provide it as a stand-in, as was done for
   `sun.misc.BASE64Decoder`.

### Class files from a newer Java

Samples compiled for a Java version newer than ASM understands fail to parse and appear as
`gap.unparseable-class`. Raise `asm.version` in `pom.xml`. New bytecode features (a new
constant kind, a new bootstrap method in the JDK) may need support in `IndyLinker`.

### Dependencies

```bash
mvn versions:display-dependency-updates
```

There are four Java ones: ASM, Jackson (YAML), picocli and JUnit. The optional native bridge
has its own, pinned in `scripts/native-bridge-requirements.txt`; after changing it, run
`NativeBridgeTest`.

For the Java ones: Raise the version property in
`pom.xml`, run the tests, and note the change in `CHANGELOG.md`.

### The desktop app's tools

- **Rust**: CI uses the latest stable; `rust-version` in `gui/Cargo.toml` is the oldest
  supported. After a new Rust release, fix any new `cargo clippy --all-targets` warnings.
- **Crates** (Tauri, Leptos and the rest): `cargo update` in `gui/`, then build the screens and
  run `cargo test`. A new Tauri minor version needs the egress test (Linux, root):
  see [gui/README.md](gui/README.md#testing). Keep `tauri` and `tauri-build` on matching
  minor versions.
- **Trunk** is pinned in `.github/workflows/gui.yml`, `.github/workflows/installers.yml` and
  `gui/README.md`; change all three. The Tauri CLI used for bundling is pinned in
  `gui/packaging/build.sh`; keep it on the same minor version as the `tauri` crate.
- **The bundled Java** is Temurin 21 (`installers.yml`), trimmed by jlink to the modules listed
  in `gui/packaging/stage.sh`. A newer LTS needs the whole Java suite run on it first (CI does)
  and, if a module was renamed or removed, a change to that list (`jdk.crypto.ec` was merged
  into `java.base` in Java 22).
- **The bridge's Python** is CPython 3.12 fetched by uv; both versions are pinned in
  `gui/packaging/stage.sh`. After changing either, run the Installers workflow with "Also build
  the with native bridge edition" ticked: its self-check imports Speakeasy.
- **Playwright and axe-core** are pinned in `gui/tests/a11y/package.json`. A new axe-core may
  find new problems; fix them rather than turning rules off.

### Releasing

The version is written in three places that must agree: `pom.xml` (the tool reads it at run
time), `gui/app/tauri.conf.json` and `gui/Cargo.toml`. `gui/packaging/version.sh` checks them.
Pushing a tag such as `v0.2.0` builds every installer and a draft release; the steps, and how
to set up signing, are in [docs/gui/RELEASING.md](docs/gui/RELEASING.md).

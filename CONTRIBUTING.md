# Contributing and maintenance

This guide is for whoever changes JavaByte next, a person or an AI agent. Read
[docs/DESIGN.md](docs/DESIGN.md) first for how the pieces fit.

## Building and testing

```bash
mvn verify
```

Every change should keep all tests passing on Java 17, 21 and 25. CI runs all three.

| Test class | What it protects |
| --- | --- |
| `DifferentialTest` | Interpreter correctness: programs must print the same as on a real JVM |
| `BehaviourTest` | Reporting of malware-like behaviour; `defineClass`; hooks; fixtures |
| `EscapeTest` | Containment: about eighty escape attempts, checked on the real machine |
| `SafetyTest` | Structure: simulated classes override everything; key policy exclusions |
| `NativeTest` | Native reporting, plugin handlers, library parsing |
| `NativeBridgeTest` | Native code in the CPU emulator. Skipped unless `JAVABYTE_BRIDGE_PYTHON` is set; see docs/NATIVE.md |

The quickest way to find an interpreter bug is to add code to a fixture in
`src/test/java/dev/javabyte/guest/` (`Basics`, `Advanced`, `Concurrency`). Those programs are
harmless and run on the real JVM too, so the test needs no hand-written expectations.

Fixtures that imitate malware (`Dropper`, `Stage2`, `Escape`, `Reflective`) are only ever
interpreted. Never call their `main` from a test.

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

### Changing the report

Adding a field needs no version change. Removing or renaming a field, or changing what one
means, requires increasing `Report.SCHEMA_VERSION` and a note in `CHANGELOG.md` and `AGENTS.md`.

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

### Releasing

Change `<version>` in `pom.xml`. That is the only place the version is written; the tool reads
it at run time. Move the "Unreleased" entries in `CHANGELOG.md` under the new version.

# Changelog

Notable changes, newest first. The report's JSON layout has its own version,
`schemaVersion`, which changes only when a reader could break.

## Unreleased

### Added
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
- `System.load` and `System.loadLibrary` now fail with `UnsatisfiedLinkError` when the library
  is not on the virtual disk, as a JVM would. Libraries that unpack their own native code
  depend on that failure.
- A guest thread that can never be woken now ends with outcome `blocked` (was `sleep-budget`).
- Generated shell classes mirror the sample's class hierarchy.
- Dependencies: ASM 9.11.1, Jackson 2.23.0, picocli 4.7.7, JUnit 5.14.4.
- The tool version comes from `pom.xml` only.

### Fixed
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

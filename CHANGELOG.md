# Changelog

Notable changes, newest first. The report's JSON layout has its own version,
`schemaVersion`, which changes only when a reader could break.

## Unreleased

### Security
- The trust manager factory added for OkHttp handed the sample a security provider of the
  tool's own making, which the sample could write to. A provider is a table of class names the
  JDK constructs on request, so an entry followed by `MessageDigest.getInstance(name, provider)`
  made the JDK construct any class on the tool's class path (a `FileHandler` wrote a log file
  to the home directory). The factory now answers with the sample's private copy of the JDK's
  TLS provider, and `getInstance` refuses any provider object that is neither such a copy nor
  one of the JVM's own (`policy.blocked`, `kind: provider-object`). Found in review, before
  the change was merged.
- The simulated socket factories and two stubs asked an address the sample supplied for its
  host name inside the tool, which is a reverse lookup on the analysis machine's name server
  when the address has no name. They now read the name without a lookup, as the sample's own
  `getHostName` calls already did.
- Second audit pass. In the desktop app, the removal of settings that reach this computer
  (`policy.allow`, `classpath`, host files, the bridge's Python) from imported files, presets
  and report folders could be bypassed: the app and the tool read YAML by different rules (a
  repeated key, a tagged key, the line breaks only YAML 1.1 knows), so a file could show the
  app nothing to remove and still give the tool `policy.allow`. The settings are now taken
  apart as a value, and every run is started from the value the app checked, written out
  afresh, never from the text as imported or typed.
- An isolated run on Linux has a session of its own and so no controlling terminal. With one
  it could push a command into the terminal's input (`TIOCSTI`), which the shell would run
  after the run, outside the isolation.
- More routes to the real JDK closed: `StructuredTaskScope` (a real thread per fork),
  `KeyStore.Builder` and `KEM` for machine key stores, `InetAddress.getHostName` on a
  nameless address (no lookup any more, with or without the command line's backstop), and
  stubs that pass a call through now refuse file, path and class-loader overloads as the
  router does.
- What a sample can make the tool write or compute is bounded: files saved during a run
  (defined classes, payloads, libraries) count against `limits.maxWrittenBytes` (event
  `gap.artifacts-not-saved` past it), an archive is unpacked up to 1 GiB, and the native
  library parser no longer follows a crafted section table for billions of steps.
- The tool no longer reads `@file` arguments, so a sample named `@x` is a sample. The desktop
  app always names the sample by its full path.
- CI now requires the isolation tests (they used to skip themselves where namespaces were
  refused), and the check that every GitHub Action is pinned to a commit catches more
  spellings and also runs before a release.
- **A sample could run real JDK methods by naming the wrong class at a call.** There is no
  bytecode verifier, and a call was approved for the class the call site named and then
  looked up by name on the object actually there. A call naming `String` (every member
  allowed) on a `Class`, `Method` or `Runtime` object ran the real `getMethod`, `invoke` or
  `exec`, with nothing in the report. A JDK call is now made only if the method resolves on
  the named class and the receiver is an instance of it, and stubs and deny rules are also
  looked up from the receiver's real class.
- **A sample's subclass could call any constructor of an allowed JDK class.** `super(...)`
  ran the JDK constructor directly, without the per-overload policy or the file-argument
  check, so a class extending `PrintStream` with `super(fileName)` wrote a real file. The
  superclass constructor is now judged as a direct `new` is; one that is not allowed is not
  run and is reported (`policy.blocked`, then `gap.phantom-superclass`).
- A stream filter set with `ObjectInputStream.setObjectInputFilter` can no longer switch the
  deserialization guard off for that stream (reported as `policy.blocked` with
  `kind: deserialization-filter`).
- Static, protected and private JDK methods can no longer be reached through a simulated
  object that extends the JDK class (`File.createTempFile` through a simulated `File`), and
  the fields of the emulator's own objects cannot be read or written by the sample.
- Key stores and security providers backed by the analysis machine (`Windows-MY`,
  `KeychainStore`, `PKCS11`, `SunMSCAPI` and others) are refused and reported
  (`kind: machine-key-store`). `Integer.getInteger`, `Long.getLong` and `Boolean.getBoolean`
  read the guest's properties, not the real JVM's. `CodeSource.implies` and
  `ZoneRulesProvider.registerProvider` are denied.
- `init` quotes the sample path and entry class. Unquoted, a line break in a class name or a
  folder name added config keys of the sample's choosing, `policy.allow` among them.
- Text printed for people (the run summary, `inspect`, `disasm`, `trace.log`, progress) shows
  control, line-breaking and bidirectional characters from the sample as `\uXXXX` escapes, so
  a sample cannot forge report lines or send escape sequences to the terminal. JSON output is
  unchanged.
- Saved files: a name that is a Windows device (`CON`, `COM1`) gets a leading underscore, and
  a file the Windows shell reads by itself when a folder is opened (`desktop.ini`, `.lnk`,
  `.url`, `.scf` and similar) is saved with `.saved` appended. A name that clashes with an
  earlier one no longer stops the remaining files from being saved.
- Archives opened with `ZipFile`/`JarFile`: the temporary copy is deleted as soon as it is
  open (when the tool exits, on Windows), and `getName()` no longer shows its real path.
- `scripts/fetch-sample.py` writes a file only if its SHA-256 is the one asked for.
- Desktop app: settings from an imported file, a preset or a report folder lose
  `policy.allow`, `classpath` and the keys that name a file on this computer to read
  (`hostFile`, `bodyFile`, a socket rule's `file`), as they already lost the bridge's Python.
  Set them by hand if you want them. Links in an opened report folder are not followed.
- Releases are always signed. The release workflow fails if the signing key is missing, expired
  or not the one whose public half is `docs/javabyte-signing-key.asc`; the public key is no
  longer uploaded with the release it signs, and its fingerprint is in `docs/INSTALL.md`.
- What is fetched while the installers are built is fixed to a version and a checksum (the
  exceptions are listed in `docs/gui/RELEASING.md`): GitHub Actions by commit, the Tauri CLI by `package-lock.json`, the native bridge's Python
  packages (which ship in the "with native bridge" installers) and uv by hashed requirements
  files, and WiX by its package's checksum. `scripts/setup-native-bridge.sh` installs the same
  pinned packages.
- An isolated run on Linux (the desktop app's default, and `scripts/run-isolated.sh`) now has a
  read-only file system. It can write only to its output folder and to a private temporary
  folder that replaces the shared `/tmp` for the run. Before, isolation removed the network and
  hid a fixed list of service sockets, and left every file as writable as it is for the user,
  so an escape from the emulator could still have written `~/.bashrc`.
- `/run`, `/tmp` and `/dev/shm` are empty in an isolated run, instead of a fixed list of known
  service folders being hidden: sockets of agents and services nobody listed are gone too. The
  sample, the config, the tool, the Java runtime, the bridge's Python and the output folder
  stay visible at their own paths, also under `/tmp`.
- The isolated Java process has no capabilities in its namespace, so it cannot undo the mounts.
  `scripts/run-isolated.sh` used to leave it with all of them.
- If the isolation cannot be set up completely, the run does not start.
- The desktop app starts the tool, and the Windows sandbox add-on starts the program in the
  AppContainer, with a short list of environment variables. `scripts/run-isolated.sh` does the
  same. Tokens and keys in the analyst's environment are no longer passed on, and neither are
  `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS` or `CLASSPATH`, which would change
  the analysis JVM itself.
- `scripts/run-isolated.sh` now needs `setpriv` (util-linux) as well as `unshare`, and treats
  `-o`/`--out` as the folder the run may write to (`./javabyte-out` for `run` without it). An
  output folder set only in the config is not writable in an isolated run: pass it with `-o`.
- JDK code can no longer reach the network on the sample's behalf. URLs in collections
  (`HashMap`, `HashSet`, `List.contains`) and `URL.sameFile` no longer cause real DNS lookups.
  `InetAddress.getCanonicalHostName` is simulated (event `net.dns-reverse`).
- As a backstop, the command-line tool turns off host name lookups for its whole JVM before it
  starts, so a lookup that a stub ever missed fails instead of sending a DNS query. `localhost`
  and address literals still work.
- The sample can no longer change the JVM's security providers: it gets private copies, and
  changes are reported as `crypto.provider-change`.
- `URLClassLoader` is simulated, including a sample's own subclasses of it, and allow-listed
  JDK methods that take a `ClassLoader`, such as `ResourceBundle.Control.newBundle`, are
  blocked. New events `class.url-loader` and `class.url-loader-add`; JDK code that tries to
  open a URL is reported as `policy.blocked` with `kind: url-open-by-jdk`.
- A sample's own direct subclass of `ClassLoader` or `SecureClassLoader` is now built on a
  simulated base too, as is `new SecureClassLoader()`. Its real parent used to be the
  emulator's own application loader, so JDK code given the loader could reach the emulator's
  class path and real resources. Now it sees only JDK classes, the sample's own `findClass`
  and the sample's resources, and `getParent()` still reports the parent the sample passed.
- What the sample writes to a launched process's standard input is now reported as
  `process.stdin` events and saved under `processes/`; it used to be thrown away.

### Anti-analysis fidelity
- The simulated machine is now one coherent desktop (Dell OptiPlex 7050, Core i5-7500, 16 GB,
  1920x1080, Windows 10 Pro 22H2). Management beans, `ProcessHandle`, `Runtime.version`, the
  screen, a loopback interface and recon commands (whoami, tasklist, wmic, systeminfo,
  ipconfig, getmac and more) answer from it. The disk looks used: system binaries, installed
  software, Desktop and Recent files. The full set of standard Java 8 system properties and
  Windows environment variables is present.
- New `environment` options: `manufacturer`, `model`, `processors`, `memoryMegabytes`,
  `screenWidth`, `screenHeight`, `processId`, `jvmArguments` and `builtInCommands`. Launches
  answered by a built-in command have `matchedRule: built-in:<program>`.
- The default local address is now 192.168.1.23 (it was VirtualBox's host-only address) and
  the default MAC address D4:BE:D9:5E:3A:9C, a Dell prefix.
- The guest has its own time zone and locale, `environment.timeZone` (default
  `America/New_York`) and `environment.locale` (default `en-US`). The analysis computer's own
  settings are never shown.
- Time is consistent: executed instructions cost virtual time, so `currentTimeMillis` and
  `nanoTime` agree, and `nanoTime` starts at a believable machine uptime. Delayed tasks,
  `parkNanos`, `Future.get(timeout)`, timed joins and timed waits all pass in virtual time.
  `Year.now`, chronologies and calendars follow `environment.clockStart`.
- Threads look like a real JVM's: the main thread has id 1 and is not a daemon, and
  `Thread.getAllStackTraces` lists the JVM's system threads. Parallel APIs no longer start
  real threads.
- JVM-made exceptions match the advertised Java 8: an implicit `NullPointerException` has no
  message, and a failed cast reads "X cannot be cast to Y". Cast and class-name messages,
  `getClass()` and loader text never name the emulator's own classes, and the application
  class loader prints as Java 8's and has a platform or extension parent.
- On the Java 8 profile the application and extension loaders are `URLClassLoader`s, as on a
  real Java 8: `getClass()` gives `sun.misc.Launcher$AppClassLoader` (or `$ExtClassLoader`)
  with `java.net.URLClassLoader` as superclass, `getURLs()` lists the sample's class path, and
  `addURL` called by reflection is reported as `class.url-loader-add` and only remembered. On
  Java 9 and later profiles the cast to `URLClassLoader` fails with the JDK's own message, and
  `Method.invoke` refuses a receiver that is not an instance of the method's class.
- `loadClass` on a sample's loader now asks the JDK before the sample's own `findClass`, as
  parent-first delegation does.
- Exceptions thrown inside JDK methods, and methods called through reflection, keep their JDK
  frames in the stack trace.
- `Package` metadata comes from the sample's `MANIFEST.MF`.

### Fixes
- `--json` output is UTF-8 on every system. It was printed in the platform's charset, so where
  no locale is set (as under `scripts/run-isolated.sh`) every non-ASCII character of a report
  on standard output became `?`. `report.json` in the output folder was not affected.
- Native bridge: boxed arguments reach native code. JNA's dispatcher reads each `Boolean`,
  `Byte`, `Character`, `Short`, `Integer`, `Long`, `Float` and `Double` it passes on through
  the box's private `value` field, which the bridge could not read ("no host field
  java/lang/Long.value"), so native code saw 0 for every one. Native code that tries to
  change a box is refused and reported, since the JDK shares cached boxes.
- Native bridge: `float` and `double` values cross the bridge. The helper addressed the XMM
  registers by a name the CPU emulator does not know, so every floating-point argument, result
  and field value silently became 0.
- `clone()` on an array works whatever class the bytecode names. Kotlin, older compilers and
  code shrinkers write `java/lang/Object.clone` for it, which stopped with a gap; OkHttp's
  `Headers.of` is one place this happens.
- Native bridge: native methods with more than four arguments (counting `JNIEnv` and the class
  or object) read their fifth and later arguments from the wrong stack slots, because the CPU
  emulator lays out stack arguments without the Windows x64 shadow space. JNA's
  `Native.invokeXxx(Function, long, int, Object[])` therefore saw a wrong `Object[]` ("Argument
  is not an array"). The helper now places them where Windows code expects them. Native code
  asking for the length of something that is not an array is reported (gap
  `native-bridge-not-an-array`) and answered as an empty array, instead of failing the helper.
- A loop that records nothing no longer runs silently until the time limit. After 100 million
  silent instructions (or half of the new `limits.maxSilentInstructions`, default 500 million)
  a `lifecycle.silent-loop` event says where the guest is looping; at the limit, only that task
  is ended (outcome `silent-loop`) and the rest of the run carries on. Every run limit's message
  now also says where the guest was when it stopped.
- `clone()` now works on every guest object. A class that implements `Cloneable` or extends a
  JDK class used to stop with a gap, because the call went to the JDK's `Object.clone()`,
  which is protected and native. The copy now also has fields of its own; before, a JDK
  `clone()` such as `ArrayList`'s gave the copy the original's guest fields. A class that is
  not `Cloneable` gets `CloneNotSupportedException` naming the class, as on a real JVM.
- `AtomicReferenceFieldUpdater`, `AtomicIntegerFieldUpdater` and `AtomicLongFieldUpdater` work
  on the sample's own fields (event `reflect.field-updater`). Making one checks what the JDK
  checks, with the same exceptions: a missing field, a field the caller may not access, the
  wrong type, a field that is not volatile, or a static field. `getClass()` names the JDK's
  own implementation classes.
- An `http:` URL now gives a plain `HttpURLConnection`; it used to give an
  `HttpsURLConnection`.
- Renaming a directory in the virtual file system now moves its contents; the `file.rename`
  event gains `entriesMoved`.
- A directory sample's `META-INF/MANIFEST.MF` `Main-Class` is now honoured by `run`, as
  `inspect` already did. `inspect` and `run` now read samples with the same code.
- Malformed `network.defaultAddress`, `network.localAddress`, `network.dns` values or
  `network.macAddress` now stop the run up front as a config error (exit code 2) instead of an
  internal error part way, and `javabyte validate` lists them. So do `processors`,
  `memoryMegabytes`, `screenWidth`, `screenHeight` and `processId` of 0 or less.
- Native bridge: a failing JNI callback no longer leaves the helper out of step. New gap
  events `native-bridge-callback-failed`, `native-bridge-stopped` and
  `array-region-size-mismatch`; `jni-handler-failed` now names the JNI function.
- Plugins and the trace file are closed even when loading the sample fails.
- Plugin hooks with action `trace` no longer skip the method, and unknown actions are rejected.
  Native-handler patterns accept `?`, as config hooks do.
- Dynamic-constant bootstrap methods declared with varargs now link.
- `file:` URLs keep `+` as a plus sign.

### Desktop app and Windows add-on
- On Linux each analysis also runs in its own mount namespace, which hides the Unix sockets of
  host services (D-Bus, systemd-resolved, nscd, Avahi, CUPS, X11, Wayland, Docker). A network
  namespace alone does not.
- The app opens only a sample the person chose with the picker, a drop or the command line,
  and never touches remote (UNC) paths, including those named in opened reports.
- Settings from report folders, imports and presets no longer carry
  `plugins.native-bridge.python`, and before a run whose settings name a program for the
  native bridge the app asks, showing its path.
- Run endings always include the tool's full error output, including the sandbox add-on's
  reason for an isolation failure. A run being ended is first asked to stop and given time to
  tidy up.
- Windows sandbox add-on: each AppContainer run uses its own container, deleted afterwards.
  Grants are journalled, so a forced end is undone by the next run. Write access no longer
  includes changing permissions. Job-object failures stop the run. Paths with non-ASCII
  letters, trailing backslashes or a `%` in the sample's name now work in Windows Sandbox.
  Stop no longer leaves the helper waiting for ever, and leftover staged samples are deleted.
- The window-sealing test now covers the packaging overlays merged into shipped builds.
- The egress test now fails when its fake network is incomplete, instead of passing.
- `scripts/run-isolated.sh` runs the jar of the `pom.xml` version, never an older one left in
  `target/`, and the GUI CI takes the jar name from `version.sh`.
- Code: the preference changes and the isolation question are shared between the app and the
  screens, and the Report, Tools and Settings tabs share one tab-list component. No change in
  behaviour.

### Docs
- docs/CONFIG.md describes every option, with the validation rules.
- docs/DESIGN.md describes the two entry points to the JDK, the objects made inert for JDK
  code, the DNS backstop and the virtual clock.
- AGENTS.md lists the new events; docs/TESTING.md the new test classes and scenarios.
- The Linux isolation wording, the GUI status, the Windows Sandbox notes and the phase plans
  are brought up to date.

### Added
- Java 21 thread builders: `Thread.ofVirtual()`, `Thread.ofPlatform()` (`name`, `start`,
  `unstarted`, `factory` and the settings) and `Thread.startVirtualThread`. Their threads run on
  the scheduler like any other; `thread.start` says whether a thread is `virtual`. Samples built
  for Java 21 start their workers this way, and before, those workers never ran.
- `SocketFactory.getDefault()` gives simulated sockets, and `TrustManagerFactory` gives a trust
  manager that accepts every certificate without reading the certificate store from disk.
  OkHttp needs both before its first request; before, its requests never reached the
  simulation.
- Events `crypto.key-derivation` (password, salt, iterations and the derived key of PBKDF2 and
  other password-based keys), `crypto.digest` and `crypto.mac` (input and result of a hash or
  MAC, all updates together). `crypto.cipher-init` gives the salt and iteration count of a
  password-based cipher, and `crypto.cipher-output` also shows blocks returned by
  `Cipher.update`, marked `partial`. A password that is not plain text is also given as hex
  of its characters (`passwordUtf16`), since such a password cannot be written out as text.
- The text summary lists cipher keys, keys derived from passwords and cipher output; before,
  they were only in `report.json`.
- Hashes, MACs and partial cipher output together get a quarter of
  `limits.maxEventsPerCategory`; `crypto.not-recorded` marks where they stop. A sample that
  stretches its key by hashing thousands of times no longer fills the category before the key
  is used.
- `trace.previewLength` (default 2000) sets how much of a value an event shows.
- Windows sandbox add-on, a separate download: each analysis runs with no network, in an
  AppContainer (every edition, the default) or in a fresh Windows Sandbox (Pro and up, chosen in
  Preferences). The Running screen shows Windows Sandbox starting; if it cannot start, the app
  says why and asks before using an AppContainer instead. See docs/gui/WINDOWS-SANDBOX.md.
- Desktop app, phase 6 (accessibility): checks for the WCAG 2.2 criteria axe does not test, run
  on every screen in CI; a manual audit script for NVDA and Orca (docs/gui/PHASE6-AUDIT.md); an
  accessibility statement (docs/ACCESSIBILITY.md), and where to report problems, in Preferences.
- Desktop app: a Tools screen with the built-in policy and every option with its default, each
  searchable.
- `javabyte policy --json`: each rule of the built-in policy with its effect (allow or block),
  section and the explanation written above it.
- JavaByte is free software under the GNU General Public License, version 3 or later (LICENSE).
  The installers carry the licence beside the app, and Preferences says so.
- Desktop app, phase 5: installers. MSI and portable zip for Windows, .deb and AppImage for
  Linux, each with its own Java runtime, in a standard edition and one with the native bridge.
  On Linux each analysis runs with no network (the .deb adds the AppArmor permission Ubuntu
  24.04 needs; otherwise the app asks first). Preferences for the output folder, the Java
  runtime and isolation. "Open with" for .jar files without taking them over. A new icon.
  `--self-check`. Tagging a version builds a draft release with checksums, signed when a key is
  stored. See docs/INSTALL.md and docs/gui/PHASE5-PLAN.md.
- The `native-bridge` plugin's default Python can be set with `JAVABYTE_BRIDGE_PYTHON`.
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
- `crypto.cipher-output` previews show up to `trace.previewLength` bytes (2000 by default)
  instead of 512, so a decrypted configuration usually appears whole. Byte arrays in
  `trace.log` and call arguments show 64 bytes instead of 24: a 256-bit key is no longer cut.
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
- Desktop app: "Reset to default", "Move up", "Move down" and "Choose…" are now named starting
  with their visible words, so voice control can press them (WCAG 2.5.3). The Tools tables can
  be scrolled by keyboard at large text sizes.
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

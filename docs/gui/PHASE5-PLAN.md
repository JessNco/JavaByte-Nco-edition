# GUI phase 5: packaging

Part of [GUI-PLAN.md](../GUI-PLAN.md). Phase 5 turns the app into installers that work on a
clean machine with nothing else installed, and adds the settings that only matter once it is
installed: where results go, which Java runs the tool, and the Linux network isolation.

Done when: a clean install and first run work on fresh Windows 11, Ubuntu 22.04 and Ubuntu
24.04 machines.

## Decisions

| Question | Decision |
| --- | --- |
| Native bridge | Two installers per system: **standard**, and **with native bridge**. Same app; it uses the bridge when it finds one next to it |
| Getting installers to people | Pushing a tag `vX.Y.Z` builds every installer and a checksum file and creates a **draft** GitHub release. A person reviews and publishes it |
| Linux signing | The release job signs the checksum file with GPG when a key is stored as a repository secret. Until then releases are unsigned and still list SHA-256 checksums |
| Isolation unavailable on Linux | Ask before each run, explaining why and how to get isolation. "Don't ask again" makes the answer stick, and it can be changed in Preferences |
| `.jar` files | JavaByte appears under "Open with" for `.jar` files. It never becomes the default program |
| Preferences added | Output folder; Java runtime (bundled, or a chosen Java 17 or later); network isolation (Linux); native bridge status |
| Icon | A simple new icon drawn as SVG and exported to every size |
| Linux versions | Ubuntu 22.04 and 24.04. Packages are built on 22.04 so they run on newer systems |

## What is in an installer

| Part | Where it comes from | Size, roughly |
| --- | --- | --- |
| The app | `cargo tauri build` | 15 MB |
| Java runtime | `jlink` from Temurin 21: the `java.se` modules plus `jdk.unsupported`, `jdk.charsets`, `jdk.zipfs`, `jdk.localedata` and `jdk.crypto.ec`. All standard modules, not only the ones `jdeps` finds, because a sample may call any standard API and a missing class would change what the analysis sees | 50 MB |
| JavaByte | `mvn package` | 4 MB |
| Native bridge (bridge installers only) | A standalone Python build (python-build-standalone, pinned) with `scripts/native-bridge-requirements.txt` installed | 150–250 MB |
| Notices | `THIRD-PARTY-NOTICES.txt`: the licences of the runtime, Python, Speakeasy and Unicorn, and where to get their source | — |

Files go into the app's resource folder: `runtime/`, `javabyte.jar` and, in bridge installers,
`bridge/`. The app finds them with Tauri's resource directory, then falls back to the
developer locations used today (`JAVABYTE_JAR`, `target/`).

| | Windows | Linux |
| --- | --- | --- |
| Installer | MSI (WiX), with the WebView2 bootstrapper embedded | `.deb` |
| Without installing | Portable zip | AppImage |

## How the app uses them

- **Java.** The bundled runtime unless Preferences names another one. A chosen runtime is
  checked by running `java -version` and must be Java 17 or later.
- **Native bridge.** The tool's `native-bridge` plugin reads its default Python from
  `JAVABYTE_BRIDGE_PYTHON` when the config does not name one. The app sets that variable to
  the bundled interpreter, so turning the plugin on in Settings is enough. Preferences shows
  whether a bridge was found and, if not, how to get one.
- **Output folder.** Documents/JavaByte unless Preferences names another folder.
- **Preferences** are stored by the app in its data folder (`preferences.json`), because the
  backend needs them. Display preferences stay in the window's storage as before.

## Network isolation on Linux

Each run starts in a new user and network namespace, which has no network interfaces except
a loopback that is down. This is what `scripts/run-isolated.sh` does, but done by the app
itself between `fork` and `exec`, not by starting `unshare`. That matters on Ubuntu 24.04,
which allows unprivileged namespaces only to programs with an AppArmor profile that permits
them: the `.deb` installs such a profile for the app, and the namespace is created by the
app's own process, so the profile applies. The child maps its own user and group to
themselves, so Java sees the same user, home folder and file permissions as without
isolation.

At start the app checks once whether a namespace can be created. When it cannot (the
AppImage on 24.04, or a system that turned namespaces off), starting a run asks first:

> **This run will not be isolated from the network.** Your system does not let JavaByte turn
> off the network for the analysis. JavaByte still simulates every network call the sample
> makes, so the sample cannot reach the network through JavaByte; isolation is a second,
> independent layer in case of a flaw. To get it, install the .deb package instead of the
> AppImage, or run JavaByte in a virtual machine without network.

with **Run without isolation**, **Cancel** (focused), and **Don't ask again**. Preferences
shows the same status and the setting.

Windows has no equivalent without administrator rights. The start screen says so once, on
first run, and recommends a virtual machine; Preferences says it permanently.

## Opening `.jar` files

- **Windows:** a WiX fragment registers a `JavaByte.jar` ProgID and adds it to
  `.jar\OpenWithProgids`. It does not touch the default value of `.jar`, so double-clicking a
  jar still does whatever it did before.
- **Linux:** the desktop entry lists `application/x-java-archive` and takes `%f`, so file
  managers offer it under "Open with". It does not change the system's default.

## Checking an install

`javabyte-gui --self-check` prints what the app found: version, Java runtime and its version,
JavaByte jar, whether `javabyte schema` runs, native bridge (found, and whether it imports
Speakeasy), isolation (available or why not) and output folder. It exits with 0 when the
runtime and the tool work. Support can ask for its output, and CI uses it to test installers.

## CI

- **On every push** (`GUI` workflow): build the standard installers on both systems, then
  install and self-check them:
  - `.deb` in fresh `ubuntu:22.04` and `ubuntu:24.04` containers (dependencies come from
    `apt`, which proves the package declares them);
  - the AppImage extracted and self-checked;
  - the MSI installed silently on Windows, self-checked, and uninstalled;
  - on the Linux runner, the installed `.deb` goes through the accessibility-bus check with a
    harmless fixture: a whole analysis in the real window, using only bundled parts.
- **On a tag** (`Release` workflow): build both variants on both systems, write
  `SHA256SUMS`, sign it if a key is present, and create a draft release with everything.
  The version in the tag must match `pom.xml`, `tauri.conf.json` and the GUI crates.

## Out of scope

Code signing for Windows (no certificate yet: see [Code signing](../GUI-PLAN.md#code-signing);
the release job leaves room for one step), automatic updates (the app has no network), the
Windows sandbox add-on, and installers for other Linux distributions.

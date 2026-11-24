# Packaging

Builds the installers (docs/gui/PHASE5-PLAN.md). From `gui/`, after `mvn package` at the
repository root, with `JAVA_HOME` set to a Java 21 JDK:

```bash
packaging/build.sh
```

```bash
packaging/build.sh --bridge
```

The installers land in `gui/target/installers/`. CI does the same on every push (standard
installers) and on a version tag (both editions, as a draft release).

| File | What |
| --- | --- |
| `version.sh` | Prints the version after checking `pom.xml`, `app/tauri.conf.json` and `Cargo.toml` agree (and, given a tag, that it matches) |
| `stage.sh` | Puts what an installer bundles in `gui/target/bundle-input/`: the jar, a jlink runtime, the notices, and with `--bridge` a standalone Python with the native bridge's packages (those in `scripts/native-bridge-requirements.txt`, checked against the checksums there) |
| `build.sh` | Runs `stage.sh`, then the Tauri bundler, and names the results |
| `package.json`, `package-lock.json` | The Tauri CLI's version and the checksums of its files; `build.sh` installs it with `npm ci` |
| `uv-requirements.in`, `uv-requirements.txt` | The version of uv, which fetches the bundled Python, and the checksums of its files |
| `sign-checksums.sh` | Signs a release's `SHA256SUMS` with the project's GPG key, or fails; used by the release workflow (docs/gui/RELEASING.md) |
| `tauri.bundle.conf.json` | Added to `app/tauri.conf.json` when bundling: the resources and the per-system installer settings. Kept apart because the resources exist only after staging, and a plain `cargo build` checks every listed resource |
| `tauri.bridge.conf.json` | Added for the "with native bridge" edition: merged on top of `tauri.bundle.conf.json` (a JSON Merge Patch), it adds only the `bridge/` resource |
| `windows/sandbox-addon.wxs`, `windows/sandbox-addon-README.txt` | The Windows sandbox add-on's own MSI (WiX 5, built with the standard edition) and the README it installs; a zip of the same files serves the portable edition |
| `linux/javabyte-gui.desktop` | The desktop entry: `%f` and the Java MIME types put JavaByte under "Open with" for `.jar` files without making it the default |
| `linux/apparmor-profile`, `linux/postinst`, `linux/postrm` | Ubuntu 24.04 allows user namespaces (which network isolation needs) only to programs with a profile that permits them. The `.deb` installs this profile where AppArmor 4 is present |
| `windows/open-with.wxs` | A WiX fragment adding JavaByte to `.jar`'s "Open with" list, leaving the default program alone |
| `THIRD-PARTY-NOTICES.txt` | The licences of what is bundled; installed beside the app with JavaByte's own `LICENSE.txt` |

To check an installed app, run it with `--self-check`: it prints what it found and whether the
tool runs, and exits with 0 when it does.

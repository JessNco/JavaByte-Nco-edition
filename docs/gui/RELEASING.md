# Releasing the desktop app

The tool and the desktop app share one version number and are released together.

## Making a release

1. Set the version in all three places, and nowhere else:
   - `pom.xml` (`<version>`),
   - `gui/app/tauri.conf.json` (`"version"`),
   - `gui/Cargo.toml` (`[workspace.package] version`).

   `gui/packaging/version.sh` fails if they differ, and the release fails if the tag does not
   match them.
2. In `CHANGELOG.md`, rename the "Unreleased" heading to `## <version>` (such as `## 0.2.0`)
   and start a new, empty "Unreleased" above it. The release notes include that section.
3. Merge to `main` and wait for CI.
4. Tag and push:

   ```bash
   git tag v0.2.0
   ```

   ```bash
   git push origin v0.2.0
   ```

5. The **Release** workflow first checks the signing key (below), then builds both editions on
   Windows and Linux, tests every installer on clean systems (as on every push), writes
   `SHA256SUMS`, signs it, and creates a **draft** release. Nothing is public yet. If the key
   is missing, wrong or expired, the workflow fails at the start and no release is made.
6. Open the draft on GitHub, read the notes, and if possible install one download on a real
   machine. Then press **Publish release**.

Releases are never published by CI. A draft can be deleted and the tag pushed again after a
fix (delete the tag first: `git push --delete origin v0.2.0`).

## Signing releases

Every release's `SHA256SUMS` is signed with the project's GPG key. There are no unsigned
releases: the workflow's first job (`signing-key`) and its signing step both run
`gui/packaging/sign-checksums.sh`, which fails unless all of this holds:

- the secrets `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` are set;
- the public key is committed as `docs/javabyte-signing-key.asc`;
- that key's fingerprint is written in `docs/INSTALL.md`;
- the stored private key is that same key, and the passphrase unlocks it;
- the signature verifies with the committed public key alone.

The public key is not uploaded with the release. People take it from the repository and
compare its fingerprint with `docs/INSTALL.md`, so someone who could replace a release's files
could not also replace the key they are checked with. The passphrase is never on a command
line: the workflow passes both secrets in the environment and the script pipes them to gpg.

### Setting the key up (once)

1. Make a key for releases only, on a computer you trust:

   ```bash
   gpg --quick-generate-key "JavaByte releases" ed25519 sign 2y
   ```

   Choose a strong passphrase when asked.
2. Put the public key in the repository:

   ```bash
   gpg --armor --export "JavaByte releases" > docs/javabyte-signing-key.asc
   ```

3. Print the fingerprint and write it in `docs/INSTALL.md`, in the block under "Its fingerprint
   is" (replacing what is there):

   ```bash
   gpg --fingerprint "JavaByte releases"
   ```

4. Commit both files and merge them to `main` like any other change, so the key is reviewed.
5. Export the private key:

   ```bash
   gpg --armor --export-secret-keys "JavaByte releases" > javabyte-release-key.asc
   ```

6. In the repository on GitHub: **Settings**, **Secrets and variables**, **Actions**, **New
   repository secret**. Add:
   - `GPG_PRIVATE_KEY`: the whole content of `javabyte-release-key.asc`;
   - `GPG_PASSPHRASE`: the passphrase.
7. Delete `javabyte-release-key.asc` and keep the key itself backed up somewhere offline.
8. Publish the fingerprint in at least one place that does not depend on this repository
   (your website, your profile, a key server such as keys.openpgp.org), so that a change to
   the repository alone cannot swap the key unnoticed.

To try the setup without tagging, run the check on your own computer with the two values in
the environment:

```bash
gui/packaging/sign-checksums.sh check
```

### Renewing or replacing the key

Renew the key before it expires (`gpg --quick-set-expire`), export the public key to
`docs/javabyte-signing-key.asc` again, commit it, and update the `GPG_PRIVATE_KEY` secret. The
fingerprint stays the same. A **new** key has a new fingerprint: change the file, the
fingerprint in `docs/INSTALL.md` and wherever else you published it, and say so in
`CHANGELOG.md`, because everyone who checks downloads has to trust the new key afresh.

## Refreshing pinned tools

Everything that goes into an installer, or runs while one is built, is fixed to an exact
version and checked against a checksum kept in this repository. Nothing updates by itself, so
refresh the pins from time to time and whenever a security fix is announced. Each refresh is an
ordinary change: review the diff, then let the **Installers** workflow build and test the
result (tick "Also build the with native bridge edition" when the Python side changed).

| What | Pinned in | How to refresh |
| --- | --- | --- |
| GitHub Actions | every `uses:` line in `.github/workflows/`, as a full commit SHA with the version in a comment | See below |
| Tauri CLI (bundles the installers) | `gui/packaging/package.json` (version) and `package-lock.json` (checksums) | Set the version in `package.json`, then `npm install --package-lock-only --ignore-scripts` in `gui/packaging/`. Keep it on the same minor version as the `tauri` crate |
| The bridge's Python packages (shipped in the "with native bridge" installers) | `scripts/native-bridge-requirements.in` (what is wanted) and `.txt` (every package, with checksums) | `scripts/update-native-bridge-requirements.sh` (needs [uv](https://docs.astral.sh/uv/)). Run `NativeBridgeTest` afterwards |
| uv (fetches the bundled Python) | `gui/packaging/uv-requirements.in` and `.txt` | Set the version in the `.in` file, then the same script |
| WiX 5 (builds the sandbox add-on's MSI) | `WIX_VERSION` and `WIX_SHA256` in `.github/workflows/installers.yml` | See below |
| Trunk, and the Rust crates | `cargo install --locked trunk@…` in the workflows; `gui/Cargo.lock` | [CONTRIBUTING.md](../../CONTRIBUTING.md#the-desktop-apps-tools) |

**Actions.** A tag such as `v4` can be moved to different code later; a commit cannot. Find the
commit of the release you want, for example:

```bash
git ls-remote --tags https://github.com/actions/checkout 'v4.*'
```

Where a tag has two lines, use the one ending in `^{}` (the commit; the other is the tag
object). Replace the SHA and the version comment on every `uses:` line of that action.
`dtolnay/rust-toolchain` has no version tags: use the head of its `stable` branch
(`git ls-remote https://github.com/dtolnay/rust-toolchain stable`) and note the date. Read the
action's release notes before moving to a new major version. CI fails if any action is used by
tag or branch (`scripts/check-action-pins.sh`).

**WiX.** Download the new package and take its checksum:

```bash
curl -fLO https://api.nuget.org/v3-flatcontainer/wix/5.0.2/wix.5.0.2.nupkg
```

```bash
sha256sum wix.5.0.2.nupkg
```

Compare it with a second source before trusting it (nuget.org shows the package's SHA-512 on
its page), then set `WIX_VERSION` and `WIX_SHA256`.

Still taken as "the newest" on each build, and so not covered by a checksum here: the Rust
toolchain (`stable`), the Temurin 21, Python 3.12 and Node 22 builds the setup actions install,
the system libraries from Ubuntu's package archive, and the tools Tauri's bundler downloads for
itself (WiX 3 on Windows, the AppImage tools on Linux).

## Signing the Windows installer

Not set up yet: there is no code-signing certificate (see "Code signing" in
[GUI-PLAN.md](../GUI-PLAN.md#code-signing)). When there is one, add a step to the `windows`
job in `.github/workflows/installers.yml` after "Build the installers" that signs
`gui/target/installers/*.msi` and the `.exe` inside the portable zip. Nothing else changes.

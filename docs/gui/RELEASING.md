# Releasing the desktop app

The tool and the desktop app share one version number and are released together.

## Making a release

1. Set the version in all three places, and nowhere else:
   - `pom.xml` (`<version>`),
   - `gui/app/tauri.conf.json` (`"version"`),
   - `gui/Cargo.toml` (`[workspace.package] version`).

   `gui/packaging/version.sh` fails if they differ, and the release fails if the tag does not
   match them.
2. In `CHANGELOG.md`, rename the "Unreleased" heading to `## <version>` (such as `## 0.1.0`)
   and start a new, empty "Unreleased" above it. The release notes include that section.
3. Merge to `main` and wait for CI.
4. Tag and push:

   ```bash
   git tag v0.1.0
   ```

   ```bash
   git push origin v0.1.0
   ```

5. The **Release** workflow builds both editions on Windows and Linux, tests every installer
   on clean systems (as on every push), writes `SHA256SUMS`, signs it if a key is stored, and
   creates a **draft** release. Nothing is public yet.
6. Open the draft on GitHub, read the notes, and if possible install one download on a real
   machine. Then press **Publish release**.

Releases are never published by CI. A draft can be deleted and the tag pushed again after a
fix (delete the tag first: `git push --delete origin v0.1.0`).

## Signing the Linux downloads with GPG

Until a key is stored, releases are unsigned and say so; they still list SHA-256 checksums.
To sign them:

1. Make a key for releases only, on a computer you trust:

   ```bash
   gpg --quick-generate-key "JavaByte releases" ed25519 sign 2y
   ```

   Choose a strong passphrase when asked.
2. Export the private key:

   ```bash
   gpg --armor --export-secret-keys "JavaByte releases" > javabyte-release-key.asc
   ```

3. In the repository on GitHub: **Settings**, **Secrets and variables**, **Actions**, **New
   repository secret**. Add:
   - `GPG_PRIVATE_KEY`: the whole content of `javabyte-release-key.asc`;
   - `GPG_PASSPHRASE`: the passphrase.
4. Delete `javabyte-release-key.asc` and keep the key itself backed up somewhere offline.

From then on every release has `SHA256SUMS.asc` and the public key
`javabyte-signing-key.asc`. Publish the key's fingerprint somewhere else too (the README, a
website), so people can tell it is really yours:

```bash
gpg --fingerprint "JavaByte releases"
```

Renew the key before it expires (`gpg --quick-set-expire`), then update the secret.

## Signing the Windows installer

Not set up yet: there is no code-signing certificate (see "Code signing" in
[GUI-PLAN.md](../GUI-PLAN.md#code-signing)). When there is one, add a step to the `windows`
job in `.github/workflows/installers.yml` after "Build the installers" that signs
`gui/target/installers/*.msi` and the `.exe` inside the portable zip. Nothing else changes.

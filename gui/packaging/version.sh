#!/usr/bin/env bash
# Prints the version, after checking that every place that states it agrees: pom.xml (the
# tool), gui/app/tauri.conf.json (the installers) and gui/Cargo.toml (the app's crates). With an
# argument (a tag such as v0.1.0), also checks that the tag names the same version.
set -euo pipefail
gui="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(cd "$gui/.." && pwd)"
# The project's own <version> is the first one in pom.xml, before any dependency.
pom="$(sed -n 's:^  <version>\(.*\)</version>:\1:p' "$repo/pom.xml" | head -n 1)"
tauri="$(sed -n 's/^  "version": "\(.*\)",/\1/p' "$gui/app/tauri.conf.json")"
cargo="$(sed -n '/^\[workspace.package\]/,/^\[/s/^version = "\(.*\)"/\1/p' "$gui/Cargo.toml")"
if [[ "$pom" != "$tauri" || "$pom" != "$cargo" ]]; then
    echo "error: versions differ: pom.xml $pom, tauri.conf.json $tauri, Cargo.toml $cargo" >&2
    exit 2
fi
if [[ $# -gt 0 && "$1" != "v$pom" ]]; then
    echo "error: tag $1 does not match version $pom" >&2
    exit 2
fi
echo "$pom"

#!/usr/bin/env bash
# Installs the native bridge's Python packages into the given interpreter's environment, taking
# only what scripts/native-bridge-requirements.txt lists and only files whose checksum is
# written there. Used by scripts/setup-native-bridge.sh and gui/packaging/stage.sh, so a
# developer's environment and the Python bundled in the installers hold the same packages.
#
# Usage: scripts/install-native-bridge-packages.sh PYTHON
#
# Two steps, because one package (lznt1) is published only as source and has to be built. pip
# would normally fetch the newest setuptools to build it, with no version and no checksum. So
# the pinned setuptools is installed first, from a ready-made wheel, and the build then uses it
# (--no-build-isolation) and fetches nothing.
set -euo pipefail

python="${1:?usage: install-native-bridge-packages.sh PYTHON}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
requirements="$here/native-bridge-requirements.txt"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
# The setuptools entry with its checksums: from its first line to the last one that ends in "\".
awk '/^setuptools==/ {on = 1} on {print} on && !/\\$/ {exit}' "$requirements" > "$work/build-tools.txt"
if ! grep -q -- '--hash=sha256:' "$work/build-tools.txt"; then
    echo "error: no pinned setuptools with checksums in $requirements" >&2
    exit 2
fi

pip=("$python" -m pip install --quiet --disable-pip-version-check --no-warn-script-location --require-hashes)
"${pip[@]}" --only-binary :all: -r "$work/build-tools.txt"
"${pip[@]}" --no-build-isolation -r "$requirements"

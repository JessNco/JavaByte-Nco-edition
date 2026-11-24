#!/usr/bin/env bash
# Rewrites the pinned, checksummed lists of Python packages from their short ".in" files:
#
#   scripts/native-bridge-requirements.in  ->  scripts/native-bridge-requirements.txt
#       what the native bridge needs, for every system and Python 3.10 or later
#   gui/packaging/uv-requirements.in       ->  gui/packaging/uv-requirements.txt
#       uv, which fetches the Python bundled in the "with native bridge" installers
#
# Run it after editing a ".in" file, or to take newer releases of the packages they pull in.
# Needs uv (https://docs.astral.sh/uv/). Review the diff before committing: every changed line
# is a different file that will be shipped inside the installers.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compile=(uv pip compile --quiet --universal --generate-hashes --upgrade
    --custom-compile-command scripts/update-native-bridge-requirements.sh)

(cd "$repo/scripts" && "${compile[@]}" --python-version 3.10 native-bridge-requirements.in -o native-bridge-requirements.txt)
(cd "$repo/gui/packaging" && "${compile[@]}" uv-requirements.in -o uv-requirements.txt)
git -C "$repo" --no-pager diff --stat -- scripts/native-bridge-requirements.txt gui/packaging/uv-requirements.txt

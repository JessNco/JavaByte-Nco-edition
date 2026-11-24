#!/usr/bin/env bash
# Creates a Python environment for the optional native bridge (see docs/NATIVE.md).
#
# A dedicated virtual environment is used for two reasons. Speakeasy pins an older Unicorn
# binding that needs "pkg_resources", which recent setuptools releases no longer ship, so
# setuptools is held below version 81. And distribution-patched system Pythons can fail to
# build one of Speakeasy's dependencies; a virtual environment avoids that.
#
# The packages are the ones pinned in native-bridge-requirements.txt, each file checked against
# the checksum listed there. The installers' bundled Python gets the same ones the same way.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
venv="${1:-$here/.venv-native-bridge}"

python3 -m venv "$venv"
"$here/scripts/install-native-bridge-packages.sh" "$venv/bin/python"
"$venv/bin/python" -W ignore -c "import speakeasy" >/dev/null

echo "Native bridge environment ready. Use this interpreter:"
echo "  $venv/bin/python"

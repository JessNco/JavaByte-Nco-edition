#!/usr/bin/env bash
# Starts the desktop app on a virtual display with an accessibility bus, then checks what the
# bus receives. Linux only. Usage: [PYTHON=/usr/bin/python3.12] tests/atspi/run.sh path/to/javabyte-gui [--print]
set -euo pipefail
app="${1:?path to the javabyte-gui binary}"
here="$(cd "$(dirname "$0")" && pwd)"
export NO_AT_BRIDGE=0 GTK_A11Y=atspi WEBKIT_DISABLE_COMPOSITING_MODE=1
dbus-run-session -- bash -c "
  /usr/libexec/at-spi-bus-launcher --launch-immediately &
  sleep 1
  xvfb-run -a -s '-screen 0 1280x900x24' '$app' &
  status=0
  "${PYTHON:-python3}" '$here/dump_tree.py' \"\${@}\" || status=\$?
  pkill -f '$app' || true
  exit \$status
" -- "${@:2}"

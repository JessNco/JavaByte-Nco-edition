#!/usr/bin/env bash
# Starts the desktop app on a virtual display with an accessibility bus, then checks what the
# bus receives. Linux only.
#
# Usage: [PYTHON=/usr/bin/python3.12] tests/atspi/run.sh path/to/javabyte-gui [--sample FILE] [--print]
# With --sample, the app opens that file (it must be harmless: it is analysed, in the emulator)
# and the check goes on through a run to the report and its event grid.
# PYTHON must be the interpreter the system's python3-pyatspi package was built for.
set -euo pipefail
app="$(realpath "${1:?path to the javabyte-gui binary}")"
shift
sample=""
if [ "${1:-}" = "--sample" ]; then
  sample="$(realpath "${2:?--sample needs a file}")"
  shift 2
  set -- --report "$@"
fi
here="$(cd "$(dirname "$0")" && pwd)"
python="${PYTHON:-python3}"

# Everything runs inside a private D-Bus session, so nothing here touches the desktop's own bus.
exec dbus-run-session -- bash -s "$app" "$here" "$python" "$sample" "$@" <<'INNER'
set -u
app="$1" here="$2" python="$3" sample="$4"
shift 4
/usr/libexec/at-spi-bus-launcher --launch-immediately &
bus=$!
Xvfb :91 -screen 0 1280x900x24 -nolisten tcp >/dev/null 2>&1 &
display=$!
sleep 2
DISPLAY=:91 GTK_A11Y=atspi WEBKIT_DISABLE_COMPOSITING_MODE=1 "$app" ${sample:+"$sample"} >/dev/null 2>&1 &
window=$!
status=0
timeout 180 "$python" "$here/dump_tree.py" "$@" || status=$?
# Stop exactly the processes started here, by process id.
kill "$window" "$display" "$bus" 2>/dev/null || true
exit "$status"
INNER

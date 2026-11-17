#!/usr/bin/env bash
# Attacks the desktop app's network containment from inside its own window. Linux only; needs
# root (or user namespaces) for a private network namespace.
#
# Usage: tests/egress/run.sh path/to/probe-build/javabyte-gui [expect-leaks]
#
# The binary must be built with `--features egress-probe`. The app runs in a network namespace
# whose only network is a fake DNS server and logging listeners (listener.py), so nothing can
# leave the machine whatever happens. The test passes if the probe ran and nothing reached any
# listener. With "expect-leaks" the result is inverted, for the control run against a build
# with protections removed: it proves the harness can see a leak when there is one.
set -euo pipefail
app="$(realpath "${1:?path to a javabyte-gui built with --features egress-probe}")"
mode="${2:-expect-none}"
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
port=8099

unshare --net --mount bash -s "$app" "$here" "$work" "$port" <<'INNER'
set -eu
app="$1" here="$2" work="$3" port="$4"
ip link set lo up
# Name lookups go to the fake DNS server, through a private copy of resolv.conf.
echo "nameserver 127.0.0.1" > "$work/resolv.conf"
mount --bind "$work/resolv.conf" /etc/resolv.conf
python3 "$here/listener.py" "$work/network.log" "$port" &
listener=$!
for _ in $(seq 50); do grep -q ready "$work/network.log" 2>/dev/null && break; sleep 0.1; done
Xvfb :93 -screen 0 1280x900x24 -nolisten tcp >/dev/null 2>&1 &
display=$!
sleep 1
DISPLAY=:93 NO_AT_BRIDGE=1 WEBKIT_DISABLE_COMPOSITING_MODE=1 \
  JAVABYTE_PROBE_PORT="$port" JAVABYTE_PROBE_REPORT="$work/report.json" \
  timeout 120 dbus-run-session -- "$app" > "$work/app.log" 2>&1 || true
sleep 1
kill "$listener" "$display" 2>/dev/null || true
INNER

python3 - "$work" "$mode" <<'CHECK'
import json, pathlib, sys
work, mode = pathlib.Path(sys.argv[1]), sys.argv[2]
events = [json.loads(l) for l in (work / "network.log").read_text().splitlines() if l.strip()]
hits = [e for e in events if e["kind"] != "ready"]
report_file = work / "report.json"
report = json.loads(report_file.read_text()) if report_file.exists() else None
app_log = (work / "app.log").read_text(errors="replace")
print("== What the page saw")
if report is None:
    print("  no report: the probe did not run or could not report")
else:
    for name, outcome in report["results"].items():
        print(f"  {name:22} {outcome}")
    print(f"  policy violations reported by the page: {len(report['violations'])}")
    print(f"  WebRTC available to page script: {report['webrtcAvailable']}")
print("== What reached the fake network")
for e in hits:
    detail = e.get("name") or e.get("first_line") or f"{e.get('size')} bytes"
    print(f"  {e['kind']:4} port {e.get('port', 53):<5} {detail}")
if not hits:
    print("  nothing")
blocked = app_log.count("blocked navigation away from the app")
print(f"== Navigations refused by the app: {blocked}")
print(f"== Work folder: {work}")
if report is None:
    print("FAIL the probe did not report, so this run proves nothing")
    sys.exit(1)
if mode == "expect-leaks":
    ok = len(hits) > 0
    print("PASS control run: the harness detected leaks" if ok else "FAIL control run saw no leaks: the harness cannot be trusted")
else:
    ok = not hits
    print("PASS nothing left the window" if ok else f"FAIL {len(hits)} attempts reached the network")
sys.exit(0 if ok else 1)
CHECK

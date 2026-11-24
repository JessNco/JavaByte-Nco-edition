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
#
# This is about the window. Each analysis the app starts has an isolation of its own (no
# network, a read-only file system, host service sockets hidden: crates/backend/src/isolation.rs),
# which is tested where it is made, by `cargo test -p javabyte-gui-backend isolation` and
# scripts/test-run-isolated.sh, with a stand-in for Java that tries to write to the home folder
# and the shared /tmp. The app runs no analysis here, so that isolation never starts in this
# test, and nothing here depends on /tmp being shared.
#
# A broken fake network fails the run instead of passing it: the listener must be ready within
# five seconds and still running when the app has finished (check.py also checks that every
# listener it depends on was opened).
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
# A default route (to nowhere: everything goes to the loopback interface). Without one, WebKit
# decides the machine is offline and fails requests for host names before looking them up,
# which would hide name-based leaks from this test instead of stopping them in real use.
ip addr add 10.255.0.1/32 dev lo
ip route add default dev lo
# Name lookups go to the fake DNS server, through a private copy of resolv.conf.
echo "nameserver 127.0.0.1" > "$work/resolv.conf"
mount --bind "$work/resolv.conf" /etc/resolv.conf
python3 "$here/listener.py" "$work/network.log" "$port" &
listener=$!
for _ in $(seq 50); do grep -q '"ready"' "$work/network.log" 2>/dev/null && break; sleep 0.1; done
if ! grep -q '"ready"' "$work/network.log" 2>/dev/null; then
    echo "FAIL the fake network's listener did not become ready within 5 s" >&2
    kill "$listener" 2>/dev/null || true
    exit 1
fi
Xvfb :93 -screen 0 1280x900x24 -nolisten tcp >/dev/null 2>&1 &
display=$!
sleep 1
# No proxy settings: with one, the web view would hand requests for host names to a proxy that
# does not exist in here, and they would fail before any name lookup, hiding exactly the leaks
# this test looks for.
env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY -u all_proxy -u ALL_PROXY \
  -u no_proxy -u NO_PROXY -u ftp_proxy -u FTP_PROXY \
  DISPLAY=:93 NO_AT_BRIDGE=1 WEBKIT_DISABLE_COMPOSITING_MODE=1 \
  JAVABYTE_PROBE_PORT="$port" JAVABYTE_PROBE_REPORT="$work/report.json" \
  timeout 120 dbus-run-session -- "$app" > "$work/app.log" 2>&1 || true
sleep 1
# A listener that died during the run saw nothing after that, so check.py fails the run.
if ! kill -0 "$listener" 2>/dev/null; then
    echo '{"time": 0, "kind": "listener-exited"}' >> "$work/network.log"
fi
kill "$listener" "$display" 2>/dev/null || true
INNER

python3 "$here/check.py" "$work" "$mode"

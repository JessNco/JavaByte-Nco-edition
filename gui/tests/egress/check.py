#!/usr/bin/env python3
"""Judges one tests/egress run from its work folder: report.json (what the page saw),
network.log (what reached the fake network) and app.log.

Usage: check.py WORK_FOLDER [expect-none | expect-leaks | expect-none-without-dns | expect-leaks-without-dns]

The "-without-dns" modes are for machines where name lookups cannot be observed (Windows CI).

A run passes only if the probe reported and the fake network was all there; otherwise it proves
nothing. In every mode the listener must have logged "ready". In the expect-none modes, every
listener a leak would reach must also have opened: DNS on port 53 (not in the "-without-dns"
mode) and TCP on 80, 443, the probe port and the one after it. A listener that crashed or could
not bind would otherwise turn a real leak into a clean result. UDP 3478 and the black-hole port 9
only warn, because Windows may reserve them. "blackhole" entries are requests the app's
black-hole proxy caught: they show the proxy working and are not leaks.
"""
import json
import pathlib
import sys

MODES = ("expect-none", "expect-leaks", "expect-none-without-dns", "expect-leaks-without-dns")
# Entries about the fake network itself, not attempts to reach it. "listener-exited" is added by
# run.sh, or the Windows job, when the listener was no longer running after the app finished.
HARNESS_KINDS = ("ready", "blackhole", "bind-failed", "listener-exited")


def required_listeners(mode, probe_port):
    """The listeners a clean result in this mode depends on, as (kind, port) pairs.

    Only the expect-none modes need any: a control run that is missing a listener sees fewer
    leaks and fails on its own.
    """
    if not mode.startswith("expect-none"):
        return set()
    needed = {("tcp", port) for port in (80, 443, probe_port, probe_port + 1)}
    if mode == "expect-none":
        needed.add(("dns", 53))
    return needed


work, mode = pathlib.Path(sys.argv[1]), (sys.argv[2] if len(sys.argv) > 2 else "expect-none")
if mode not in MODES:
    print(f"FAIL unknown mode {mode!r}; expected one of {', '.join(MODES)}")
    sys.exit(2)
log_file = work / "network.log"
events = [json.loads(l) for l in log_file.read_text().splitlines() if l.strip()] if log_file.exists() else []
hits = [e for e in events if e["kind"] not in HARNESS_KINDS]
unwatched = [e for e in events if e["kind"] == "bind-failed"]
absorbed = [e for e in events if e["kind"] == "blackhole"]
report_file = work / "report.json"
report = json.loads(report_file.read_text()) if report_file.exists() else None
app_log = (work / "app.log").read_text(errors="replace") if (work / "app.log").exists() else ""

print("== What the page saw")
if report is None:
    print("  no report: the probe did not run or could not report")
else:
    for name, outcome in sorted(report["results"].items()):
        print(f"  {name:22} {outcome}")
    print(f"  policy violations reported by the page: {len(report['violations'])}")
    print(f"  WebRTC available to page script: {report['webrtcAvailable']}")
print("== Caught by the black-hole proxy (went nowhere): %d" % len(absorbed))
for e in unwatched:
    print(f"== Not watched: {e['listener']} port {e['port']} ({e['error']})")
ready = next((e for e in events if e["kind"] == "ready"), None)
if ready is None:
    print("== The listener never became ready")
print("== What reached the fake network")
for e in hits:
    detail = e.get("name") or e.get("first_line") or f"{e.get('size')} bytes"
    print(f"  {e['kind']:4} port {e.get('port', 53):<5} {detail}")
if not hits:
    print("  nothing")
print(f"== Navigations refused by the app: {app_log.count('blocked navigation away from the app')}")
print(f"== Work folder: {work}")

if ready is None:
    print("FAIL the listener never became ready, so this run proves nothing")
    sys.exit(1)
if any(e["kind"] == "listener-exited" for e in events):
    print("FAIL the listener stopped before the app did, so later attempts went unseen")
    sys.exit(1)
watched = {(w["listener"], w["port"]) for w in ready.get("watched", [])}
missing = sorted(required_listeners(mode, ready.get("probe_port", 8099)) - watched)
if missing:
    names = ", ".join(f"{kind} port {port}" for kind, port in missing)
    print(f"FAIL a leak could have gone unseen: not watched: {names}")
    sys.exit(1)
if report is None:
    print("FAIL the probe did not report, so this run proves nothing")
    sys.exit(1)
if mode == "expect-leaks":
    # The control must show both kinds of leak, or a clean result elsewhere proves nothing
    # about the kind it could not see.
    saw_dns = any(e["kind"] == "dns" for e in hits)
    saw_tcp = any(e["kind"] == "tcp" for e in hits)
    ok = saw_dns and saw_tcp
    print("PASS control run: the harness detected name lookups and connections" if ok
          else f"FAIL control run: name lookups seen {saw_dns}, connections seen {saw_tcp}; the harness cannot be trusted")
elif mode == "expect-leaks-without-dns":
    ok = any(e["kind"] == "tcp" for e in hits)
    print("PASS control run: the harness detected connections" if ok else "FAIL control run saw no connections; the harness cannot be trusted")
elif mode == "expect-none-without-dns":
    # Windows: there is no private network there, so name lookups cannot be observed; only
    # connections to the local listeners can.
    ok = not [e for e in hits if e["kind"] != "dns"]
    print("PASS no connection left the window (name lookups not observable here)" if ok else f"FAIL {len(hits)} attempts reached the listeners")
else:
    ok = not hits
    print("PASS nothing left the window" if ok else f"FAIL {len(hits)} attempts reached the network")
sys.exit(0 if ok else 1)

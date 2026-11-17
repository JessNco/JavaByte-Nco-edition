#!/usr/bin/env python3
"""Judges one tests/egress run from its work folder: report.json (what the page saw),
network.log (what reached the fake network) and app.log.

Usage: check.py WORK_FOLDER [expect-none | expect-leaks | expect-none-without-dns | expect-leaks-without-dns]

The "-without-dns" modes are for machines where name lookups cannot be observed (Windows CI).

A run passes only if the probe reported; otherwise it proves nothing. "blackhole" entries are
requests the app's black-hole proxy caught: they show the proxy working and are not leaks.
"""
import json
import pathlib
import sys

work, mode = pathlib.Path(sys.argv[1]), (sys.argv[2] if len(sys.argv) > 2 else "expect-none")
log_file = work / "network.log"
events = [json.loads(l) for l in log_file.read_text().splitlines() if l.strip()] if log_file.exists() else []
hits = [e for e in events if e["kind"] not in ("ready", "blackhole", "bind-failed")]
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
if not any(e["kind"] == "ready" for e in events):
    print("== The listener never became ready")
print("== What reached the fake network")
for e in hits:
    detail = e.get("name") or e.get("first_line") or f"{e.get('size')} bytes"
    print(f"  {e['kind']:4} port {e.get('port', 53):<5} {detail}")
if not hits:
    print("  nothing")
print(f"== Navigations refused by the app: {app_log.count('blocked navigation away from the app')}")
print(f"== Work folder: {work}")

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

#!/usr/bin/env python3
"""Tests for check.py, the judge of a tests/egress run, on made-up work folders.

No app, network or listener runs here. Each test writes the network.log and report.json that a
run would leave behind and checks the verdict. The point is that a broken fake network fails
the run: a listener that never got ready, could not bind a port a leak would reach, or stopped
before the app did, would otherwise turn a real leak into a clean result.

Usage: python3 -I tests/egress/test_check.py      (from gui/; plain unittest, no packages)
"""
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

CHECK = pathlib.Path(__file__).resolve().parent / "check.py"
PORT = 8099
# What listener.py opens on Linux; the Windows job runs it with --no-dns.
ALL_TCP = [{"listener": "tcp", "port": p} for p in (80, 443, PORT, PORT + 1)]
DNS = {"listener": "dns", "port": 53}
UDP = {"listener": "udp", "port": 3478}
BLACKHOLE = {"listener": "blackhole", "port": 9}
REPORT = {"results": {"fetch": "blocked"}, "violations": [], "webrtcAvailable": False}


def ready(watched):
    """The "ready" entry listener.py writes once it has tried every listener."""
    return {"time": 1.0, "kind": "ready", "probe_port": PORT, "watched": watched}


class CheckTest(unittest.TestCase):
    """Runs check.py on one made-up work folder per test and looks at its verdict."""

    def judge(self, events, mode, report=REPORT):
        """Writes the folder, runs check.py in MODE, and returns (exit code, output)."""
        with tempfile.TemporaryDirectory() as work:
            folder = pathlib.Path(work)
            (folder / "network.log").write_text("".join(json.dumps(e) + "\n" for e in events))
            if report is not None:
                (folder / "report.json").write_text(json.dumps(report))
            done = subprocess.run([sys.executable, "-I", str(CHECK), work, mode],
                                  capture_output=True, text=True, check=False)
            return done.returncode, done.stdout

    def test_clean_run_with_every_listener_passes(self):
        code, out = self.judge([ready([DNS, UDP, *ALL_TCP, BLACKHOLE])], "expect-none")
        self.assertEqual(code, 0, out)
        self.assertIn("PASS nothing left the window", out)

    def test_listener_that_never_got_ready_fails_every_mode(self):
        for mode in ("expect-none", "expect-none-without-dns", "expect-leaks", "expect-leaks-without-dns"):
            with self.subTest(mode=mode):
                code, out = self.judge([], mode)
                self.assertEqual(code, 1, out)
                self.assertIn("FAIL the listener never became ready", out)

    def test_dns_not_watched_fails_on_linux(self):
        failed = {"kind": "bind-failed", "listener": "dns", "port": 53, "error": "in use"}
        code, out = self.judge([failed, ready([UDP, *ALL_TCP, BLACKHOLE])], "expect-none")
        self.assertEqual(code, 1, out)
        self.assertIn("not watched: dns port 53", out)

    def test_dns_is_not_needed_without_dns(self):
        code, out = self.judge([ready([UDP, *ALL_TCP, BLACKHOLE])], "expect-none-without-dns")
        self.assertEqual(code, 0, out)

    def test_each_tcp_port_not_watched_fails(self):
        cases = [(port, "expect-none") for port in (80, 443, PORT, PORT + 1)]
        cases += [(port, "expect-none-without-dns") for port in (PORT, PORT + 1)]
        for port, mode in cases:
            with self.subTest(port=port, mode=mode):
                tcp = [w for w in ALL_TCP if w["port"] != port]
                failed = {"kind": "bind-failed", "listener": "tcp", "port": port, "error": "denied"}
                code, out = self.judge([failed, ready([DNS, UDP, *tcp, BLACKHOLE])], mode)
                self.assertEqual(code, 1, out)
                self.assertIn(f"tcp port {port}", out)

    def test_ports_80_and_443_only_warn_without_dns(self):
        # Without the fake DNS no probe can reach them (each names its port), and Windows
        # runners may refuse to bind port 80.
        for port in (80, 443):
            with self.subTest(port=port):
                tcp = [w for w in ALL_TCP if w["port"] != port]
                failed = {"kind": "bind-failed", "listener": "tcp", "port": port, "error": "denied"}
                code, out = self.judge([failed, ready([UDP, *tcp, BLACKHOLE])], "expect-none-without-dns")
                self.assertEqual(code, 0, out)
                self.assertIn(f"Not watched: tcp port {port}", out)

    def test_probe_port_comes_from_the_ready_entry(self):
        # A listener started on another port must be judged by that port, not the default.
        tcp = [{"listener": "tcp", "port": p} for p in (80, 443, 9000, 9001)]
        entry = {"time": 1.0, "kind": "ready", "probe_port": 9000, "watched": [DNS, *tcp]}
        code, out = self.judge([entry], "expect-none")
        self.assertEqual(code, 0, out)

    def test_udp_and_blackhole_only_warn(self):
        # Windows may reserve these ports; the run still counts.
        events = [{"kind": "bind-failed", "listener": "udp", "port": 3478, "error": "reserved"},
                  {"kind": "bind-failed", "listener": "blackhole", "port": 9, "error": "reserved"},
                  ready(ALL_TCP)]
        code, out = self.judge(events, "expect-none-without-dns")
        self.assertEqual(code, 0, out)
        self.assertIn("Not watched: udp port 3478", out)

    def test_listener_that_stopped_early_fails(self):
        events = [ready([DNS, UDP, *ALL_TCP, BLACKHOLE]), {"time": 0, "kind": "listener-exited"}]
        for mode in ("expect-none", "expect-leaks"):
            with self.subTest(mode=mode):
                code, out = self.judge(events, mode)
                self.assertEqual(code, 1, out)
                self.assertIn("FAIL the listener stopped before the app did", out)

    def test_leak_fails_and_control_needs_both_kinds(self):
        dns = {"time": 2.0, "kind": "dns", "name": "a.leak.test", "qtype": 1}
        tcp = {"time": 2.1, "kind": "tcp", "port": 80, "first_line": "GET / HTTP/1.1"}
        everything = ready([DNS, UDP, *ALL_TCP, BLACKHOLE])
        self.assertEqual(self.judge([everything, tcp], "expect-none")[0], 1)
        self.assertEqual(self.judge([everything, dns, tcp], "expect-leaks")[0], 0)
        self.assertEqual(self.judge([everything, tcp], "expect-leaks")[0], 1)
        self.assertEqual(self.judge([everything, tcp], "expect-leaks-without-dns")[0], 0)

    def test_missing_report_fails(self):
        code, out = self.judge([ready([DNS, UDP, *ALL_TCP, BLACKHOLE])], "expect-none", report=None)
        self.assertEqual(code, 1, out)
        self.assertIn("the probe did not report", out)

    def test_unknown_mode_is_refused(self):
        code, out = self.judge([ready(ALL_TCP)], "expect-nothing")
        self.assertEqual(code, 2, out)


if __name__ == "__main__":
    unittest.main()

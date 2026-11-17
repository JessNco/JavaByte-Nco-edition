#!/usr/bin/env python3
# Run with the Python the system packages were built for (python3-pyatspi), e.g. /usr/bin/python3.12.
"""Checks what the Linux accessibility bus (AT-SPI) receives from the JavaByte window.

Orca reads applications through AT-SPI, so this is the closest automated check of what Orca
can know. It does not show how Orca speaks it; that needs a person (docs/gui/PHASE0-TESTING.md).

Usage, with the app already running on an accessibility bus:
    python3 dump_tree.py [--print]

Exit status 0 if every expected object was found. Needs python3-pyatspi.
"""
import sys
import time

import pyatspi

EXPECTED = [
    # (role name as AT-SPI reports it, accessible name or None for any)
    ("push button", "Skip to main content"),
    ("heading", "JavaByte"),
    ("page tab list", "Sections"),
    ("page tab", "Run settings"),
    ("page tab", "Events"),
    ("page tab", "Running"),
    ("page tab", "Display"),
    ("entry", "Sample (required)"),
    ("radio button", "The main method named in the sample (usual)"),
    ("check box", "Run the sample's shutdown code at the end"),
]


def find_app(name_fragment, timeout_s=30):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        desktop = pyatspi.Registry.getDesktop(0)
        for app in desktop:
            if app is not None and name_fragment.lower() in (app.name or "").lower():
                return app
        time.sleep(0.5)
    return None


def walk(node, depth=0, out=None, limit=4000):
    if out is None:
        out = []
    if node is None or len(out) >= limit:
        return out
    try:
        role = node.getRoleName()
        name = node.name or ""
        states = node.getState().getStates()
    except Exception:  # objects can disappear while walking
        return out
    out.append((depth, role, name, states))
    for i in range(node.childCount):
        try:
            walk(node.getChildAtIndex(i), depth + 1, out, limit)
        except Exception:
            continue
    return out


def main():
    app = find_app("javabyte")
    if app is None:
        names = [a.name for a in pyatspi.Registry.getDesktop(0) if a is not None]
        print(f"FAIL the app is not on the accessibility bus; applications seen: {names}")
        return 1
    # The web content is filled in after the page loads.
    for _ in range(60):
        nodes = walk(app)
        if any(role == "page tab" for _, role, _, _ in nodes):
            break
        time.sleep(0.5)
    if "--print" in sys.argv:
        for depth, role, name, _ in nodes:
            print(f"{'  ' * min(depth, 30)}{role}: {name[:80]!r}")
    missing = [(role, name) for role, name in EXPECTED
               if not any(r == role and (name is None or n == name) for _, r, n, _ in nodes)]
    print(f"{len(nodes)} accessible objects; {len(EXPECTED) - len(missing)} of {len(EXPECTED)} expected found")
    for role, name in missing:
        print(f"FAIL missing {role} {name!r}")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())

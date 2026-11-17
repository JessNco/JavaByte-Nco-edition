#!/usr/bin/env python3
# Run with the Python the system packages were built for (python3-pyatspi), e.g. /usr/bin/python3.12.
"""Checks what the Linux accessibility bus (AT-SPI) receives from the JavaByte window.

Orca reads applications through AT-SPI, so this is the closest automated check of what Orca
can know. It does not show how Orca speaks it; that needs a person (docs/gui/PHASE0-TESTING.md).

Usage, with the app already running on an accessibility bus:
    python3 dump_tree.py [--report] [--print]

--report: the app was started with a harmless sample; go on through a run to the report.

Exit status 0 if every expected object was found. Needs python3-pyatspi.
"""
import sys
import time

import pyatspi

EXPECTED = [
    # (role name as AT-SPI reports it, accessible name or None for any)
    ("link", "Skip to main content"),
    ("heading", "JavaByte"),
    ("heading", "Analyse a sample"),
    ("push button", "Choose a sample file…"),
    ("push button", "Choose a folder of classes…"),
    ("heading", "Recent analyses"),
]

# With --report: the app was given a harmless sample, so the check goes through the flow as a
# screen reader user would, pressing buttons through the bus.
EXPECTED_OVERVIEW = [
    ("heading", "What it is"),
    ("heading", "Same analysis on the command line"),
    ("radio button", None),          # how to run it
    ("push button", "Analyse"),
]

# The report's Events tab: the grid and its parts. WebKitGTK reports an ARIA grid as a table.
EXPECTED_EVENTS = [
    ("page tab list", None),
    ("page tab", "Summary"),
    ("page tab", "Indicators"),
    ("page tab", "Events"),
    ("table", "Events"),
    ("column header", "Number"),
    ("column header", "Details"),
    ("table cell", "0"),
    ("combo box", None),            # the category filter
    ("entry", "Containing text"),
    ("radio button", "A simple table, 50 events per page"),
    ("heading", "Current event"),
]


def text_of(node):
    try:
        text = node.queryText()
        return text.getText(0, text.characterCount)
    except NotImplementedError:
        return "".join(text_of(node.getChildAtIndex(i)) for i in range(node.childCount))


def attributes(node):
    try:
        return dict(a.split(":", 1) for a in node.getAttributes() if ":" in a)
    except Exception:
        return {}


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
        if not name and role in ("table cell", "column header", "row header"):
            # Cells carry their content as text, not as a name; that text is what Orca reads.
            name = text_of(node)
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
    printing = "--print" in sys.argv
    app = find_app("javabyte")
    if app is None:
        names = [a.name for a in pyatspi.Registry.getDesktop(0) if a is not None]
        print(f"FAIL the app is not on the accessibility bus; applications seen: {names}")
        return 1
    # The web content is filled in after the page loads.
    nodes = wait_for(app, "push button", "Choose a sample file…", 30)
    show(nodes, "start", printing)
    missing = check(nodes, EXPECTED)
    if "--report" not in sys.argv:
        return report(missing)

    # The sample named on the command line is picked up and inspected.
    nodes = wait_for(app, "push button", "Analyse", 60)
    show(nodes, "overview", printing)
    missing += check(nodes, EXPECTED_OVERVIEW)
    if not press(app, "push button", "Analyse"):
        missing.append(("push button", "Analyse (to press)"))
        return report(missing)
    nodes = wait_for(app, "push button", "View the report", 120)
    show(nodes, "running, finished", printing, 120)
    if not press(app, "push button", "View the report"):
        missing.append(("push button", "View the report (to press)"))
        return report(missing)
    nodes = wait_for(app, "page tab", "Events", 30)
    if not press(app, "page tab", "Events"):
        missing.append(("page tab", "Events (to press)"))
        return report(missing)
    nodes = wait_for(app, "table cell", None, 30)
    show(nodes, "report, Events tab", printing, 200)
    missing += check(nodes, EXPECTED_EVENTS)
    cells = sum(1 for _, role, _, _ in nodes if role == "table cell")
    print(f"{cells} grid cells exposed")
    if cells == 0:
        missing.append(("table cell", "any"))
    # The grid renders only rows near the view, so screen readers learn the real size from
    # aria-rowcount, which WebKitGTK passes on as an object attribute.
    table = next(find_nodes(app, "table", "Events"), None)
    rowcount = attributes(table).get("rowcount") if table else None
    print(f"grid row count exposed to assistive technology: {rowcount}")
    if not (rowcount and rowcount.isdigit() and int(rowcount) > 1):
        missing.append(("table attribute", f"rowcount above 1 (got {rowcount})"))
    return report(missing)


def wait_for(app, role, name, timeout_s):
    """Walks the tree until an object with this role (and name, unless None) appears."""
    deadline = time.time() + timeout_s
    while True:
        nodes = walk(app)
        if any(r == role and (name is None or n == name) for _, r, n, _ in nodes) or time.time() > deadline:
            return nodes
        time.sleep(0.5)


def press(app, role, name):
    """Activates an object through the bus, as a screen reader's default action does."""
    node = next(find_nodes(app, role, name), None)
    if node is None:
        return False
    node.queryAction().doAction(0)
    return True


def show(nodes, label, printing, limit=None):
    if printing:
        print(f"--- {label} ---")
        for depth, role, name, _ in nodes[:limit]:
            print(f"{'  ' * min(depth, 30)}{role}: {name[:80]!r}")


def report(missing):
    for role, name in missing:
        print(f"FAIL missing {role} {name!r}")
    return 1 if missing else 0


def check(nodes, expected):
    missing = [(role, name) for role, name in expected
               if not any(r == role and (name is None or n == name) for _, r, n, _ in nodes)]
    print(f"{len(nodes)} accessible objects; {len(expected) - len(missing)} of {len(expected)} expected found")
    return missing


def find_nodes(node, role, name):
    try:
        if node.getRoleName() == role and node.name == name:
            yield node
        for i in range(node.childCount):
            yield from find_nodes(node.getChildAtIndex(i), role, name)
    except Exception:
        return


if __name__ == "__main__":
    sys.exit(main())

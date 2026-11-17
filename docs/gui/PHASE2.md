# Phase 2: open, inspect, run, summary

Status: **built and tested automatically** on branch `claude/gui-phase2`. The plan's "done
when" for this phase is *first user sessions*, which needs people; a script for them is at the
end.

## What the app does now

The trial screens from phase 0 are gone. The app analyses real samples with the real
command-line tool, start to finish:

| Screen | What it shows and does |
| --- | --- |
| **Start** | First-time safety notice. **Choose a sample file…** and **Choose a folder of classes…** (the system picker), drag and drop, or a file named on the command line (`javabyte-gui sample.jar`, which is what "Open with" does). **Recent analyses**: the last 20 runs, each with date, outcome in words, event count and an **Open report** button |
| **Sample overview** | From `inspect --json`: what the sample is (size, SHA-256, classes, main class, native code, class loaders, resources), findings in plain words with how much they matter, and the choice of where to start (main class, other classes with a `main`, Java agents, or the class start-up sweep), each with a one-line explanation. The exact command line for the same analysis, with **Copy command** |
| **Running** | Elapsed time, instructions, current entry point, events by category (a table), latest notable events (programs, network, classes defined, native code, files written). **Stop analysis** and Control+. keep what was found. A spoken summary at most every few seconds (setting in Display), and a clear message at the end |
| **Report** | Tabs: **Summary** (a plain-language paragraph, outcome per entry point, counts, gaps and dropped events, where the files are), **Indicators** (programs, network, files, classes, native libraries, decoded text, each list copyable) and **Events** (the accessible grid from phase 0, now on the run's own `events.jsonl`, with the 50-per-page table as an alternative) |

Analyses go to `Documents/JavaByte/<date time> <sample name>/`, the same files the command
line writes. Without a Documents folder they go to the app's data folder. One analysis at a
time.

The app finds the tool through `JAVABYTE_JAR`, then `javabyte.jar` next to the app, then the
repository's `target/javabyte-*.jar` (for development); `java` must be on the path until
phase 5 bundles a runtime. If either is missing, the Start screen says so in words and the
buttons are disabled with that reason.

## How it is built

- `protocol::views` turns tool output into what the screens show: `overview()` from `inspect`,
  `report_view()` and `plain_summary()` from `report.json`. Rust unit tests cover them on real
  tool output (the drift-checked files from phase 1).
- `backend::session` holds everything for one window: the chosen sample, the run, the report,
  the recent list. Paths come only from the system picker, a drop or the command line, and the
  session checks them again before use. Tests drive it against the real tool on harmless
  fixtures: a whole run to a report, and stopping.
- The screens hold no file paths of their own and run no code from the sample; untrusted text
  is shown through the phase 0 escaping (invisible and direction-changing characters made
  visible, never turned into markup).

## Tests

| Check | Result |
| --- | --- |
| Rust unit and integration tests, including the session against the real tool | Pass |
| Browser checks (`gui/tests/a11y`): axe in light, dark, high-contrast and 200 % text on every screen; focus moves to each screen's heading; titles; the event grid's keyboard model on 300,000 events; hostile text; announcement rate; reflow at 320 px; forced colours | 92 checks pass |
| Accessibility bus (`gui/tests/atspi`, Linux): the real app opens a harmless fixture given on its command line, and the check presses **Analyse**, **View the report** and the **Events** tab through AT-SPI, as a screen reader would, checking what each screen exposes | 22 of 22 expected objects |
| Network containment (`gui/tests/egress`) | Pass: nothing left the window |

## Not in this phase

- **Change settings first** on the overview, and the settings editor: phase 3.
- Report tabs **Limits and gaps**, **Files**, **Settings used**, and **Compare runs**: phase 4.
  The Summary tab already lists gaps and dropped events.
- Moving all text into resource files for translation is not done yet; the text is in the
  screen code. It should be done before phase 3 adds many more strings.

## First user sessions

Three to five people, at least one who uses a screen reader daily and at least one who does
not use command lines. Each uses a harmless test sample (never live malware outside a virtual
machine) and is asked, without help:

1. Open the sample and say what kind of program it is and whether it has native code.
2. Analyse it, and stop an analysis part way.
3. Say from the report what the sample tried to contact and which files it wrote.
4. Find event number 200 in the event list and read its details.
5. Open the earlier analysis from the Start screen.

Note where they hesitate, what they expected instead, and anything a screen reader read
wrongly or not at all. Record the result here, as for phase 0.

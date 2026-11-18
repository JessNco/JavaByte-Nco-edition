# Phase 4: the full report

Status: **built and tested** on branch `claude/gui-phase4`. The plan's own criterion, the
second user sessions, needs people; everything automated passes. What was learned is under
"As built" at the end.

## Goal

Everything a run produced can be read, checked and kept safely from the app: why the run
stopped and what to try next, every file the sample wrote or created, the exact settings it ran
with, and how two runs differ. Plus two things the event list still lacked from the plan:
filtering by action and jumping to an event number.

## Decisions

| Question | Decision |
| --- | --- |
| Which runs can be compared? | **Any two**: from the recent list or opened from disk. The previous run of the same sample is suggested first |
| Show created classes as bytecode? | **Yes.** A new command, `javabyte disasm`, prints a class as readable instructions (it only parses; nothing runs). The Files tab shows its output |
| Open reports the app did not make? | **Yes**: **Open a report…** on the Start screen takes a `report.json` or its folder, such as a command-line run's `javabyte-out` |
| How much of a file does the viewer show? | **The first 64 KB**, then **Show more** for each further 64 KB. Copies always hold the whole file |
| Saving files (decided in the GUI plan) | **Password zip by default** (AES-256, password `infected`), with the file, its SHA-256, the run it came from and a warning. **Save as is** only behind an alert dialog with a tick box, focus on Cancel, no timer; the choice is logged in the run's folder |

## Command-line addition

`javabyte disasm FILE [--class NAME]`: the readable form of a class file, or of one class in a
jar (`--class` with dots or slashes). It uses ASM's text printer, which the tool already ships;
nothing in the class runs. Exit code 0, or 2 if the file is not a class (or the class is not in
the jar), with the reason. Damaged and hostile class files must give a clean error, never a
crash or a hang: the existing fuzzing gains a target for it.

## Report screen, as built in this phase

| Tab | What it shows |
| --- | --- |
| **Summary**, **Indicators** | As in phase 2 |
| **Events** | As in phase 2, plus a filter by action (within the category) and **Go to event number** |
| **Limits and gaps** | Why each part of the run ended and what to try next; gaps grouped by kind with what each means; refused Java features grouped by member; categories whose events were not all kept. Where a setting would help, a button opens Settings for a new run with that setting shown |
| **Files** | Every saved file in a table: name, kind (file it wrote, code it created, data it sent), size, SHA-256 and where it came from (path on the pretend disk, web address, class name). Choose one to view it as text, hex or (classes) bytecode, 64 KB at a time. Tick files and **Save a copy** (password zip), or **Save as is…** for one file behind the warning |
| **Settings used** | The settings the run used: the run's `config.yaml` as written, and the effective settings as the tool read them. **Run again with changes** opens them in Settings for the same sample |

**Compare** is a screen of its own, reached from the report and from the Start screen's recent
list: choose two runs; it shows how each ended, events per category with the difference, and
per indicator what was added and removed; settings that differ are listed by name.

## How it is built

- **protocol**: `compare(a, b)` and the limits-and-gaps view, as pure functions tested on real
  reports. Words for each gap kind and outcome in the resource files.
- **backend**: open a report from any folder (checked like a run's); list artifacts with size,
  SHA-256 and origin; read 64 KB pages of one artifact; write the AES zip; save as is (logged);
  call `disasm`. Artifact paths are checked: only names in the report's `artifacts` list,
  inside the run folder, with no `..` or links leading out.
- **Desktop commands**: all paths still come from system pickers. The save dialogs refuse the
  folder the sample is in. The app never opens a saved file.

## Tests

- `disasm`: contract tests, and fuzzing with damaged classes (clean error, bounded time).
- Zip: opened by an independent reader with the password; holds the file unchanged, its
  SHA-256 and the warning text; AES, not ZipCrypto.
- Artifact paths: a report naming `../outside` or a link out of the folder is refused.
- `compare`: unit tests on real reports (same run twice: nothing differs; a run with an extra
  answer: the new request appears as added).
- Browser checks: every new tab and screen with axe in all themes and at 200 %, the alert
  dialog's focus and tick box, hex and text views of hostile content, reflow at 320 px.
- Accessibility bus: the Files tab and the alert dialog through AT-SPI.
- Network containment test, as before.

## Done when

The plan's own criterion is the second user sessions, which need people. Everything above
passes on Linux and Windows CI.

## Not in this phase

Packaging and the bundled Java runtime (phase 5); the full manual accessibility audit (phase 6).

## As built

Everything in the plan was built. Notes:

- **The warning is a native modal dialog** (`<dialog>` opened as modal, with
  `role="alertdialog"`). On Linux the accessibility bus shows it as an alert named by its title,
  with the tick box, **Cancel** and an unavailable **Save as is**; while it is open only its 23
  objects reach screen readers, so the rest of the window is truly out of reach.
- **Zip copies** are AES-256 with the password `infected`. The tests check the raw headers (AES
  on every entry, nothing readable without the password) and decrypt them; an independent
  reader, `pyzipper`, opened one during development.
- **Origins of saved files** come from the report's events: the path the sample wrote, the
  address a request body went to, the name of a class it defined.
- **Comparing** works on any two analyses in the recent list, which now includes reports opened
  from disk. Settings that only change where results go or what the command prints are not
  counted as differences.
- `javabyte disasm` is a new command-line feature in its own right, documented in README.md and
  AGENTS.md, and fuzzed with 600 damaged class files per run.

Found and fixed on the way:

- The browser stand-in borrowed its own state twice when opening a report (a crash the browser
  checks caught at once).
- A disabled main button was dark text on dark blue, unreadable; automated contrast checks skip
  disabled controls, so it showed only in a screenshot. Disabled main buttons now look like any
  disabled button.
- The files table was cramped by full SHA-256 values; the table shows the first 12 characters
  and the viewer and the warning show the whole hash.
- Two tables lacked their scrolling wrapper and made the window scroll sideways at 320 px.

Not built: the **Tools** screen of the GUI plan (viewers for `javabyte policy` and `javabyte
defaults`) belongs to no phase yet; the same information is in **Settings** (defaults beside
each field, the policy options under Java features it may use). (Since then, built: the Tools
screen, `gui/ui/src/screens/tools.rs`, with its viewers for `javabyte policy` and `javabyte
defaults`; see [GUI-PLAN.md](../GUI-PLAN.md).)

# Phase 4: the full report

Status: **in progress** on branch `claude/gui-phase4`.

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

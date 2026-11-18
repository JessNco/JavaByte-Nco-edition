# GUI phase 6: accessibility audit

Part of [GUI-PLAN.md](../GUI-PLAN.md). The target is **WCAG 2.2 level AA**. Phase 6 is done
when this audit has been run on both systems and no AA issue is open. The resulting statement
for users is [docs/ACCESSIBILITY.md](../ACCESSIBILITY.md).

## What is checked automatically, on every change

CI runs these on every push. A person does not need to repeat them, only to judge what they
cannot.

| Check | Where | Covers |
| --- | --- | --- |
| axe-core, every screen and state, light, dark, high contrast and 200% text | `gui/tests/a11y/run.mjs` | Names, roles, contrast of text, structure, ARIA use (WCAG 2.0–2.2 A and AA rules) |
| Focus, titles and keyboard operation between and within screens | `run.mjs` | 2.4.2, 2.4.3, 2.1.1; tabs, radio groups, grid keys, dialogs |
| Reflow at 320 px wide | `run.mjs` | 1.4.10 |
| Announcements during a run, at the chosen interval | `run.mjs` | 4.1.3 |
| Target size with the spacing exception | `gui/tests/a11y/criteria.mjs` | 2.5.8 |
| Focus ring of at least 2 px, never covered; tabbing comes back round | `criteria.mjs` | 2.4.7, 2.4.11, 2.1.2 |
| Text spacing (line 1.5, letters 0.12, words 0.16, paragraphs 2): nothing clipped, no sideways scroll | `criteria.mjs` | 1.4.12 |
| Each field, list and button stands out from its surroundings at 3:1 | `criteria.mjs` | 1.4.11 |
| Accessible names contain the visible words | `criteria.mjs` | 2.5.3 |
| One level-1 heading, no skipped levels | `criteria.mjs` | 1.3.1 |
| What the Linux accessibility bus receives from the real window, through a whole analysis | `gui/tests/atspi` | Roles and names as Orca gets them; actions work without a mouse |
| No words for people outside the resource files | `ui_text` test | 3.1.5 groundwork: plain language is reviewable in one place |

## What a person checks

Automated tools cannot tell whether what a screen reader *says* makes sense, whether focus goes
where a person expects, or whether the screen is understandable. Run each part below on:

- **Windows 11** with **NVDA** (latest) and the installed app, and with **Narrator** for a
  quick second opinion;
- **Ubuntu 24.04** with **Orca** and the installed `.deb`.

Use a harmless sample: a test fixture packed into a jar, as in [gui/README.md](../../gui/README.md#testing).
Mark each step **Pass**, **Fail** (with what happened), or **N/A**, in the results table at
the end. A step fails if a person relying on that technology could not do the task, or would be
misled.

### A. Getting around (all screens)

1. Start the app. The window title says the screen ("Start – JavaByte"), and the screen reader
   reads the heading "Analyse a sample".
2. Press Tab once: "Skip to main content" is read and visible. Enter moves to the screen's
   content.
3. Tab through the navigation. Each item is read with its name; unavailable ones say why
   ("Choose a sample first.").
4. Move by headings (H in browse mode, in NVDA and in Orca). Every screen has one clear
   heading, then its sections in order.
5. Go to Preferences, Tools, then Start. Each time, focus lands on the screen's heading and it
   is read.

### B. Start and Sample overview

1. The first-run notice is read in full, including the line about network isolation for this
   computer. "I understand" moves focus to "Choose a sample file…".
2. Choose a sample with the system's file picker, by keyboard only. The overview heading is read
   with the sample's name.
3. The findings ("Important: …") are read in words, not only as colours or icons.
4. The start options are a radio group: arrow keys move between them, and the recommended one
   is chosen and says so.
5. The equivalent command line can be reached and read.

### C. Settings

1. Forms and YAML are tabs: arrow keys move between them.
2. Each field is read with its label, its hint and its default. "More settings" says whether it
   is open.
3. Enter a wrong value (Time limit: `soon`). After the check, the problem summary is read; its
   link moves focus to the field, which is marked invalid and says why.
4. Add a web answer: the new item's first field gets focus. "Move up: web answer 2" and
   "Remove" are read with the item they act on.
5. "Reset to default: …" puts the default back and says so.
6. Presets: applying one is announced, and says what it changed.
7. In the YAML tab, the editor is read as an editable multi-line text area; line numbers are not
   read as part of the text.

### D. Running

1. Starting is announced. Progress is announced at the interval set in Preferences (15 s by
   default), and never more often.
2. Stop is reachable and announced. The end ("Analysis finished. …") is announced once.
3. On Linux with the AppImage on Ubuntu 24.04 (no isolation): the question "This analysis will
   not be isolated from the network" is read in full, with focus on Cancel. Escape cancels and
   focus returns to Analyse.

### E. Report

1. The tabs are read with their names; the summary is plain words.
2. Events grid: one Tab stop. Arrow keys move by cell, with the column header read when the
   column changes; Page Up/Down, Home/End and Ctrl+Home/End work. The row count is the real one
   (not only the rows drawn). The details of the event in focus are shown below the grid and
   can be reached with Tab.
3. Filtering by category and action, and "Go to event", say how many events match and where
   focus went.
4. "Show events as: A simple table, 50 events per page" (for screen readers that prefer
   tables): row and column headers are read, and moving between pages says which page.
5. Files: each file is read with its name, kind and size. The viewer (text, hex, bytecode) is
   readable line by line; "Show more" says what was added.
6. "Save as is…": the warning dialog is read in full as an alert, focus is on Cancel, and Save
   stays unavailable until the box is ticked. Escape closes it.
7. Limits and gaps: each limit is read with what to try next; "Run again with changes" focuses
   the matching field in Settings.
8. Compare: the two runs are chosen with labelled lists; the differences are tables with
   headers.

### F. Tools and Preferences

1. Tools: the policy and options tables are read with caption, column and row headers. Typing in
   a search field announces the count once typing pauses, without interrupting.
2. Preferences: every choice is a labelled control; changes are announced ("Analyses will be
   saved in the chosen folder."). Choosing a folder or a Java uses the system's picker by
   keyboard.

### G. Vision, without a screen reader

1. **Text size**: set Windows "Text size" to 200% (Settings, Accessibility), or GNOME "Large
   text". Then Preferences, Text size 200%. Nothing overlaps or is cut off; only the events grid
   and wide tables scroll sideways, and each can be scrolled by keyboard.
2. **Zoom**: Ctrl and + to 400% on a 1280 px wide window. Everything stays usable by scrolling
   up and down only.
3. **High contrast**: Windows "Contrast themes" (Aquatic, Desert). Every control, focus ring,
   selected tab and grid cell stays visible. On Linux, Preferences, Colours, High contrast.
4. **Reduced motion**: turn off animation effects in the system. Nothing moves.
5. **Colour**: with a greyscale filter (Windows: Colour filters), every state is still clear:
   blocked rules say "Blocked", outcomes are in words, errors have text.

### H. Keyboard only, no screen reader

1. Do a whole analysis, from choosing the sample to saving a file as a password zip, without a
   mouse. Focus is always visible and never lost (never on nothing after a screen changes).
2. No key combination is needed that requires holding two keys apart from the usual Shift+Tab.
3. Dragging a file onto the window is never the only way: the picker always works.

## Results

Copy this table for each system and fill it in. Note the versions of the system, the screen
reader and JavaByte.

| Step | NVDA / Windows 11 | Orca / Ubuntu 24.04 | Notes |
| --- | --- | --- | --- |
| A1 | | | |
| A2 | | | |
| … | | | |

Each failure becomes an issue on GitHub labelled `accessibility`, quoting the step and what was
heard or seen. Phase 6 is done when the table has no open failure at level AA.

## Found and fixed while preparing this audit

The new automated checks (`criteria.mjs`) found, and the app now fixes:

- **2.5.3 Label in name.** Buttons whose visible words were not in their names: "Reset to
  default" was named "Reset Time limit in seconds to its default", "Move up" was named "Move web
  answer 2 up", "Choose…" was named "Choose a file for Body". Voice control users saying the
  visible words could not press them. The names now start with the visible words ("Reset to
  default: Time limit in seconds").
- **Scrollable tables.** The Tools tables scroll sideways at 200% text; they can now be focused
  and scrolled by keyboard (axe `scrollable-region-focusable`).

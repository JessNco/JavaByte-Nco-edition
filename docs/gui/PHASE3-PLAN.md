# Phase 3: the settings editor

Status: **in progress** on branch `claude/gui-phase3`.

## Goal

Everything a config file can say, the app can say, without typing YAML. People who prefer YAML
keep it, comments and all. Settings move freely between the app and the command line.

## Decisions

| Question | Decision |
| --- | --- |
| Forms and YAML tab both edit the config; what happens to comments? | **Kept.** The YAML text is the source of truth. A form edit changes only the lines it concerns, so comments, order and layout survive, including in imported files |
| 78 options is a lot; how are they shown? | **Common first, the rest folded.** Each section shows its everyday settings; the others are in a clearly labelled, closed **More settings** group. Opened groups are ordinary content for screen readers and search |
| What settings does a new sample start with? | **Fresh each time:** the tool's defaults plus that sample's own suggestions (`javabyte init`). Nothing carries over from the previous sample unless a preset is applied on purpose |
| Can people save their own presets? | **Yes.** **Save as preset…** stores the settings under a name. Presets are ordinary YAML files in a folder the app can open, so they can be shared and used with `javabyte run -c` |
| Text in resource files | Done first in this phase, before the editor adds many new strings (the plan's rule: nothing in the code holds user-visible text) |

## Where the editor fits

- **Sample overview** gains **Change settings first**, next to **Analyse**. The start choice on
  the overview edits `entryPoints` in the same config, so both stay in step.
- **Settings** is a new screen with its own heading and navigation entry, with two tabs:
  **Forms** and **YAML**.
- **Analyse** always runs the config as shown, through `javabyte run -c`. The config is saved
  in the run's folder as `config.yaml`, so every run can be repeated from the command line.

## Forms tab

- Sections follow docs/CONFIG.md: Sample and entry points, Limits, Environment, Network,
  Programs, Policy, Hooks, Trace, Threads, Output, Plugins. A jump list at the top links to each.
- Every field is generated from `javabyte schema --json`: a visible label, the one-line
  description, the default ("Default: 40"), and its own error message tied to it with
  `aria-describedby`.
  - integer: a number field; boolean: a checkbox; a choice: radio buttons (3 or fewer) or a
    select; string: a text field; free value (`hooks[].value`): a text field read as YAML.
  - list of values (`classpath`, `args`, `policy.allow`): one item per line in a text area.
  - map (`environment.properties`, `env`, `network.dns`, headers): a table of name and value
    with **Add** and **Remove**.
  - list of objects (entry points, files, HTTP rules, sockets, programs, hooks): a table with
    **Add**, **Edit**, **Move up**, **Move down** and **Remove**. **Edit** opens the item's
    fields below the table, labelled "Editing HTTP rule 2 of 3", with **Done**. Nothing is
    reordered by dragging.
  - Paths on the host (`environment.files[].hostFile`, `network.http[].bodyFile`, …) have a
    **Choose…** button using the system picker, as well as the text field.
- A setting that is not in the config shows its default and stays out of the YAML until
  changed. **Reset to default** removes it again.
- If the YAML cannot be read, the forms are replaced by a message naming the line, with a link
  to it in the YAML tab.

## YAML tab

A plain text area (no editor component, so screen readers treat it as ordinary text), with line
numbers in a column beside it that screen readers skip. Changes reach the forms after a short
pause. The tab's name says "YAML (changed)" while there are edits not yet checked.

## Checking

- `javabyte validate --json` checks the config on request (**Check settings**) and about a
  second after typing stops; one check at a time, a newer one replaces an older one.
- Problems are listed in a summary at the top, each linking to its field (or its YAML line),
  and the count is announced politely ("2 problems in the settings").
- **Analyse** with known problems shows the summary instead of starting.

## Presets

- **Built-in**, from the follow-up table in AGENTS.md. Each one only fills in or adds form
  fields and then moves focus to them, so the person sees and can change everything:
  - relaunch with the arguments the sample used (from the last report's `process` events);
  - act as the copy the sample installed (`environment.samplePath`);
  - supply the files it looked for (from `file.read-missing` events);
  - answer the web addresses it asked for (from `net.http` events with no matching rule);
  - let a beacon loop run longer (`limits.maxSleeps`);
  - force the result of a check (a new `hooks` entry);
  - collect hidden text from start-up code (an extra `clinit-sweep` entry point);
  - make two runs comparable (`environment.clockStart`);
  - run its Windows native code (the native bridge plugin).
  Presets that need a report are offered only when there is one for this sample.
- **Saved presets**: **Save as preset…** asks for a name and writes
  `<app data>/presets/<name>.yaml`, without the `sample` line. **Apply** replaces the settings
  with the preset's and keeps the sample; **Undo** puts the previous settings back.
- **Open presets folder** shows the folder in the file manager.

## Moving settings between app and command line

| Action | What it does |
| --- | --- |
| **Import…** | Opens a YAML or JSON config. JSON becomes YAML. The sample in the file is kept only if it is the sample already chosen; otherwise the person is asked which to use |
| **Export…** | Saves the YAML as shown |
| **Copy as command line** | `javabyte run SAMPLE -s KEY=VALUE …`, one `-s` per changed setting (lists and maps in YAML's one-line form) |
| Run folder | `config.yaml` beside the report |

## Plugins

`schema --json` lists settings of built-in plugins too (today: the native bridge's `python`),
so the Plugins section is generated like the rest. A plugin with no settings still gets a
checkbox, since a plugin runs only if it has an entry.

## How it is built

- **`protocol::schema`**: the schema's types, and which settings are "common" and which section
  each belongs to. A test fails if the list names a setting the schema does not have.
- **`backend::config`**: a config document over a lossless YAML parser (the `yaml-edit` crate):
  read a value by path, set it, remove it, and add, move or remove list items, leaving every
  other line as it was. Compiled into the screens as well, so form edits need no round trip.
- **`backend::session`**: the config for the current sample (from `init`), checking, running with
  `-c`, presets and import/export.

## Tests

- **Lossless editing**: randomised sequences of edits on real `init` output and on hand-written
  configs with comments; after each, the text parses, holds exactly the expected values, and
  every comment line is still there. Strings that need quoting (Windows paths, `#`, `: `,
  leading spaces, quotes, non-ASCII, a value that looks like a number or `true`) included.
- **Parity**: every option in `schema --json` has a form field (fails if one is added without).
- **Command-line equivalence**: for configs touching every section, the effective `config` in
  the report is the same whether the run used the file or the copied `-s` command line (real
  tool, harmless fixtures).
- **Screens**: axe on both tabs in every theme and at 200 % text, error summary links move
  focus to the field, list tables work by keyboard alone, reflow at 320 px, and the accessibility
  bus check edits a setting and runs with it.
- **Text**: a test fails if a screen contains user-visible text that is not from a resource.

## Done when

The parity test passes for every option, and the equivalence test passes.

## Not in this phase

Report tabs **Settings used** and **Run again with changes** (phase 4, which will reuse this
editor), packaging (phase 5).

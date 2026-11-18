# Accessibility of the JavaByte desktop app

JavaByte's desktop app is built to be usable by everyone who analyses Java malware, including
people who use a screen reader, a keyboard instead of a mouse, magnification, high contrast or
voice control.

## Target

**WCAG 2.2 level AA.** The app is designed and tested against it. That is the same standard
most public bodies are required to meet.

## Status

*Last reviewed: November 2026, version 0.2.0.*

- **Checked automatically on every change:** every screen and state, in the light, dark and
  high-contrast themes and at 200% text, by axe-core and by checks for the WCAG 2.2 criteria
  axe does not cover (target size, focus visible and not hidden, text spacing, contrast of
  controls, label in name, headings). On Linux, a whole analysis is also driven through the
  accessibility interface Orca uses, in the real window. All of these pass.
- **Checked by hand:** not yet. The manual audit with NVDA on Windows and Orca on Linux is
  written ([docs/gui/PHASE6-AUDIT.md](gui/PHASE6-AUDIT.md)) and will be run before the first
  wide release. Until then, the app is believed to meet the target but this has not been
  confirmed by people using assistive technology. This section will say when it has.

## What works

- Everything can be done with a keyboard, in a logical order, with a visible focus ring that is
  never hidden. No time limits. Nothing needs keys held together: the one shortcut, Control
  and full stop to stop an analysis, is optional (the Stop button does the same) and is made
  known to screen readers.
- Each screen has a title and a heading that receives focus when the screen opens. A "Skip to
  main content" link comes first.
- Progress during an analysis is announced to screen readers at an interval you choose
  (Preferences), and its start and end always are.
- Text follows the system's text size and can be made larger in Preferences; the app works at
  400% zoom without scrolling sideways, except inside the event grid and wide tables, which
  scroll on their own and by keyboard.
- Light, dark and high-contrast themes, Windows contrast themes, and reduced motion are
  followed. Nothing is shown by colour alone: outcomes, severities and "Blocked" are in words.
- Every event can also be read as a simple paged table, for screen readers that handle tables
  better than grids.
- Dragging a file is never the only way: a file picker always works.

## Known limitations

- **Long text in the event grid is shortened** with "…" in each cell. The whole text is in the
  details of the selected event and in the table view.
- **Text from a sample is shown as it is**, apart from control characters, which are shown as
  visible escapes such as `\x07`. Obfuscated names and encoded data can be hard to listen to;
  that is the nature of the sample, not something the app can make clearer.
- **The hex and bytecode views** are technical by nature. They are plain text, read line by
  line.
- **English only** for now. All the app's words are in resource files, so translations can be
  added.

## Tell us about a problem

If something in JavaByte is hard or impossible to use with your setup, please open an issue at
<https://github.com/JessNco/JavaByte-Nco-edition/issues> and add the label `accessibility`. Say
what you were trying to do, what you use (for example "NVDA 2026.3 on Windows 11"), and what
happened. Accessibility problems are treated as bugs, not feature requests.

# Windows sandbox add-on

Part of [GUI-PLAN.md](../GUI-PLAN.md) ("Future: Windows sandbox add-on"). On Linux each analysis
already runs with no network. On Windows that needs either administrator rights or a separate
helper; this add-on is that helper, downloaded separately, so the main installer stays simple.

## Decisions

| Question | Decision |
| --- | --- |
| Which isolation | **AppContainer** on every edition of Windows 10 and 11, **plus Windows Sandbox** where Windows offers it (Pro, Enterprise, Education, with virtualisation on and the feature enabled) |
| Default | AppContainer: no network, starts in about a second |
| Windows Sandbox | A Preferences choice, "Windows Sandbox: strongest isolation, a fresh Windows for each analysis; slower to start" |
| Each analysis | A fresh Windows Sandbox every time; nothing carries over |
| The slow start | The Running screen shows "Starting Windows Sandbox (usually 20 to 40 seconds)", announced to screen readers; Stop works during start-up |
| If the sandbox fails | No silent fallback: the app says why and asks before using AppContainer for that analysis |
| Distribution | A separate download (`…-windows-x86_64-sandbox-addon.msi`, and a zip for the portable edition). Signed later, with the main installer |

## The helper

`javabyte-sandbox.exe` (crate `gui/sandbox`) starts one program isolated and passes its standard
input, output and exit code through, so the app drives it exactly like Java itself.

```text
javabyte-sandbox check
javabyte-sandbox appcontainer [--read PATH]... [--write PATH]... -- PROGRAM ARGS...
javabyte-sandbox windows-sandbox [--map HOST=GUEST[:rw]]... [--stage FILE]... -- PROGRAM ARGS...
```

- **`check`** prints JSON: whether AppContainer works here (by starting a harmless process in
  one) and whether Windows Sandbox is present, each with a reason when not.
- **`appcontainer`** starts the program in an AppContainer of its own,
  `JavaByte.Analysis.<unique>`, which has no capabilities and is deleted when the run ends: no
  network, no access to the user's files. The files the analysis needs are granted for the run
  only (`--read` for the sample, `--write` for the run's folder) and the grants are removed
  afterwards. The write grant can create, change and delete files but not change their access
  lists. Afterwards, any entry under the run's folder that still names the container, or every
  container, is removed. Each run writes its container and grants to a journal in
  `%LOCALAPPDATA%\JavaByte\sandbox` before it makes them, and holds it locked. If the app has to
  end the helper, the next `check` or run undoes whatever an unlocked journal lists. If the job
  object cannot be set up, or the program put in it, the program is ended before it runs and the
  helper exits with code 70. The Java runtime and the add-on are under Program Files, which
  Windows already lets every AppContainer read. A job object ends the whole process tree when
  the run ends, and limits it to a few processes (Java, and the native bridge's Python). The
  program does not inherit the helper's environment: it gets a short list of variables
  (Windows' own and the person's folders; not the ones a JVM reads options from), with `TEMP` and `TMP` naming the
  container's folder, so tokens in the person's environment are not there to be read.
- **`windows-sandbox`** writes a `.wsb` file with networking off, the folders mapped (`--map`;
  read-only unless `:rw`) and the sample copied into a folder of its own (`--stage`) under a
  safe name: characters other than ASCII letters, digits, space and `. - _ ( ) [ ] + ,` become
  `_`, so no name a sample's author chooses can break the script. It then starts Windows
  Sandbox. Inside, a script runs the program with its output going to a file, which the helper
  copies to its own output as it grows. Stop is relayed through a file: when the helper's input
  says `stop` or ends, it creates the file, and a small loop inside the sandbox turns that into
  `stop` on the program's input. The sandbox shuts itself down when the program ends, and the
  helper exits with the program's exit code. If the exit code has not arrived 10 seconds after
  Stop, the helper gives up, deletes its folders and exits with code 70. Each run's folder in
  `TEMP` has a unique name and a lock, and the next run deletes folders left by a helper that
  was ended. If Windows Sandbox cannot start, the helper exits with code 70 and says why on its
  error output. An argument (or the native bridge's path) that still holds `%`, `"` or a line
  break once mapped folders are replaced by their sandbox paths cannot be passed safely to the
  script inside the sandbox, so such a run is refused rather than started.

Folder paths in the arguments, and `JAVABYTE_BRIDGE_PYTHON`, are rewritten from the host's to
the sandbox's. The sample is the exception: the app names it as the sandbox will see it
(`C:\JavaByteStage\<safe name>` for a file, `C:\JavaByteSample` for a folder), so that
nothing of a name its author chose has to be found and rewritten.

## In the app

- `setup.rs` finds the helper (in the app's resources, in `Program Files\JavaByte\sandbox`, or
  next to the app) and asks it `check` once at start.
- Preferences on Windows: without the add-on, the existing explanation and where to get it;
  with it, "Turn off the network for each analysis" and the choice between AppContainer and
  Windows Sandbox, each saying whether it is available here and why not.
- The Running screen shows the starting stage while Windows Sandbox starts.
- A failed sandbox start ends the run with the reason, and offers "Analyse in an AppContainer
  instead".

## Tests

- The helper's argument parsing, `.wsb` and script generation and path rewriting are unit tests,
  run on every system.
- On the Windows CI runner: `check`; a program in the AppContainer cannot reach the network
  while the same program outside it can; a whole analysis of a harmless fixture through the
  helper writes its report; grants are gone afterwards. Windows Sandbox needs nested
  virtualisation, which CI runners do not have, so it is tested by hand: see the steps below,
  and steps D4 to D6 and F3 to F4 of the accessibility checklist in
  [PHASE6-AUDIT.md](PHASE6-AUDIT.md), which cover what a screen reader says at each stage.

### Checking Windows Sandbox by hand

On Windows 11 Pro with the "Windows Sandbox" feature on: install the app and the add-on, choose
Windows Sandbox in Preferences, analyse a harmless fixture. The Running screen shows the
starting stage; the analysis completes; Stop during start-up and during the run both end it
with a report. Then turn virtualisation off (or the feature) and analyse again: the app explains
and offers AppContainer.

### Checking the clean-up by hand

On Windows, run an AppContainer analysis and end `javabyte-sandbox.exe` from Task Manager part
way through. Then run `javabyte-sandbox check`. The journal in `%LOCALAPPDATA%\JavaByte\sandbox`
should be gone, `icacls <run folder>` should no longer list the `S-1-15-2-…` container SID, and
`%LOCALAPPDATA%\Packages\JavaByte.Analysis.*` should be gone. Repeat with Windows Sandbox and
check that no `%TEMP%\javabyte-sandbox-*` folder is left.

If the Windows Sandbox window is closed without pressing Stop, the helper waits until the app's
own run limit ends it; the next run then tidies up.

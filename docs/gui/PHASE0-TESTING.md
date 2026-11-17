# Phase 0: accessibility trial

Phase 0 answers one question before the real GUI is built: **can people using a screen reader,
a keyboard, large text or high contrast use the four hardest parts of the design**, on Windows
and on Linux? Those parts are a form with error messages, a set of tabs, a list of 300,000
events, and live progress announcements.

The trial app contains one of each and nothing else. It analyses nothing; all its data is made
up, including deliberately hostile text of the kind real malware produces.

## Getting the trial app

- **Windows and Linux builds** are produced by the `GUI trial` workflow in GitHub Actions on
  every push to the `claude/gui-phase0` branch. Download the artifact for your system from the
  workflow run's page. The builds are unsigned: on Windows, choose "More info" and then "Run
  anyway" at the SmartScreen prompt. The Windows build needs WebView2, which Windows 10 and 11
  already have.
- **To build it yourself**, see `gui/README.md`.

Linux needs WebKitGTK 4.1 (`libwebkit2gtk-4.1-0` on Debian and Ubuntu) and a running
accessibility bus, which desktop sessions have.

## What has already been checked automatically

| Check | How | Result |
| --- | --- | --- |
| WCAG 2.0–2.2 A and AA rules on every screen, in light, dark and high-contrast themes and at 200% text | axe-core in Chromium (`gui/tests/a11y`) | See the latest run |
| Keyboard: skip link, tabs, error summary, error links, grid navigation including Control+End over 300,000 rows | Playwright in Chromium | See the latest run |
| Reflow at 320 px wide with no sideways scrolling | Playwright | See the latest run |
| Focus outline in Windows high-contrast (forced colours) | Playwright | See the latest run |
| Hostile text shown as text, bidirectional overrides shown as codes | Playwright | See the latest run |
| Number of progress announcements during a run | Playwright | See the latest run |
| What the Linux accessibility bus receives from the real desktop window, including the grid's cells and its full row count | `gui/tests/atspi` with WebKitGTK | 19 of 19 expected objects |
| The window cannot send anything to the network | `gui/tests/egress` (below) | Nothing left the window |

## Network containment

The question: if an attacker ever got script running inside the app's window, could it send
data out? (Text from samples is only ever shown as text, so this should never happen; this test
assumes it has.)

`gui/tests/egress/run.sh` starts a test-only build of the real app (`--features egress-probe`)
whose page runs hostile JavaScript, `gui/app/src/egress_probe.js`. The app runs in a private
network namespace whose only network is a fake DNS server and listeners that log every name
lookup and connection (`listener.py`). Each attempt uses its own host name
(`v-<attempt>.leak.test`), so a leak names its route.

The probe tries 38 routes from inside the page and 7 navigations: `fetch` (GET, POST,
no-cors, HTTPS, bare IP, `localhost`), XMLHttpRequest, WebSocket, EventSource, `sendBeacon`, dynamic
`import()`, workers (URL and blob), service workers, `FontFace`, WebRTC, images (element, bare
IP, CSS property, `<style>`, stylesheet), `<link>` with `prefetch`, `preload`, `dns-prefetch`,
`preconnect`, `modulepreload` and `icon`, a plain link's presence, scripts, iframes (URL and
`srcdoc`), `<object>`, `<embed>`, audio, video and its poster, SVG images, `<base>`, then
`window.open`, a clicked link with `ping`, meta refresh, a submitted form and `location.href`
(name, bare IP and `localhost`).

The verdict comes from the listeners, not from what the page reports.

**Results on Linux (WebKitGTK 2.52), 17 November 2026**

| Build | Reached the network | Caught by the black-hole proxy | Navigations refused |
| --- | --- | --- | --- |
| Control: all protections removed | 30 routes, with DNS lookups and connections | – | 0 |
| Policy only, before the proxy was added | 2: `preconnect` and the clicked link (DNS lookup and an empty connection each) | – | 4 |
| Guard and proxy, policy removed | nothing | 26 | 8 |
| **As shipped: policy, guard and proxy** | **nothing** | 2 | 5 |

The control run is what makes the others meaningful: the same probe, with nothing in the way,
is seen leaking through both name lookups and connections. The second row is why the proxy
exists: those two leaks happen before any policy is checked.

Two lessons from building the test, both kept in the harness:
- A proxy set in the environment made WebKit fail requests for host names before looking them
  up, which hid leaks. The app is now started with no proxy settings.
- With no default route, WebKit considers itself offline and fails host-name requests early,
  hiding leaks the same way. The private network has a default route that goes nowhere.

`cargo test -p javabyte-gui` also fails if the policy, the proxy setting or the window's
permissions are loosened in configuration.

**Windows (WebView2)** runs the same probe in CI on a GitHub-hosted runner, where the window
does start. There is no private network there, so only connections to the local listeners can
be seen, not name lookups, and port 80 cannot be watched (Windows reserves it).

The first Windows run found a leak that Linux does not have: a page navigation to
`http://127.0.0.1:8099/...?secret=stolen` reached the listener. Two things let it through.
Chromium, on which WebView2 is built, sends loopback addresses past any proxy unless told not
to; and the navigation guard did not refuse that navigation, although it refused three others.
Loopback cannot reach the internet, but it can reach any program on the same machine. The
window now passes `--proxy-bypass-list=<-loopback>` to WebView2, so loopback also goes to the
black hole, and the probe now also attacks `localhost` by name.

**Results on Windows (WebView2, GitHub-hosted Windows runner), 17 November 2026, after the
fix**

| Build | Reached the listeners | Caught by the black-hole proxy | Navigations refused |
| --- | --- | --- | --- |
| Control: all protections removed | `fetch` and images to `127.0.0.1` and `localhost` with the data; 5 WebRTC STUN packets (UDP) | – | 0 |
| **As shipped** | **nothing** | 19 | 4 (including `127.0.0.1` and `localhost`) |

WebRTC was available to page script in the control and absent in the shipped build, so its
removal works on WebView2 as well. In this run the guard refused the `127.0.0.1` navigation
that got out in the first run; why it missed it then is not known. It no longer matters for
containment, because a request the guard misses now goes to the black hole. Host-name routes
cannot be checked on Windows (no private DNS), and port 80 is not watched (Windows reserves
it); the Linux run covers both.

**Not covered:** the operating system's own traffic from the web engine (for example WebView2
updates), which no page script controls; and a compromised backend, which is outside the
window and covered by the emulator's own containment.

## What automated checks cannot tell you

Automated checks find perhaps a third of real accessibility problems. They cannot tell whether
NVDA or Orca reads things sensibly. That is what the manual script below is for.

## Manual test script

Run each part with **NVDA on Windows** (latest version, default settings) and **Orca on Linux**
(GNOME or MATE session). Record the result for each step: **Pass**, **Fail** (what happened),
or **Awkward** (works, but hard to use; say why). Testers who use a screen reader daily are the
most valuable; the steps are written so that a sighted tester can follow them with the screen
turned off too.

Before starting: open the app, then press the screen reader's "read title" command. You should
hear "JavaByte".

### A. Getting around

1. Press **Tab** once. Expect "Skip to main content, link". Press **Enter**; focus moves to the
   main area.
2. Press **Tab** to reach the tabs. Expect "Run settings, tab, selected, 1 of 4" (wording varies).
3. Press **Right arrow** three times, then **Home**. Each tab is announced and its section shows
   at once.
4. Use heading navigation (NVDA: **H**; Orca: **H** in browse mode). Expect the headings "JavaByte",
   then the current section's headings in order.
5. Use landmark or region navigation (NVDA: **D**; Orca: **M**). Expect "main" and "search"
   (on the Events section).

### B. Form and errors (Run settings)

1. Tab through the form. Each field's label is read, then its hint ("Path to a .jar file…").
   "Sample (required)" says that it is required.
2. Leave every field as it is and activate **Check settings**. Expect focus to move to "There is
   a problem, heading level 3", followed by the list of problems.
3. Activate the first problem link. Expect focus in the Sample field, and the error ("Error:
   Enter the path of the sample to analyse") read with the field, and "invalid entry".
4. Choose "A method I name". Expect two new fields, Class and Method, to appear after the
   choice. Check that they are found by tabbing forward.
5. Type a path in Sample, fill in Class and Method, and check again. Expect the announcement
   "These settings are valid…". Focus should not move.
6. Read the "Same settings as a command" block. Activate **Copy command**. Expect "Command copied"
   or an explanation of how to copy.

### C. Events grid (Events)

1. Expect the count "Showing all 300,000 events" to be readable as text below the filters.
2. Tab into the grid. Expect the grid name "Events", the number of rows (300,001 including the
   header) and the first cell, for example "Number 0" or "0, column 1, row 2".
3. Move with **Down arrow** and **Right arrow**. Each cell should be read with its column name
   at least when the column changes, and its row number.
4. Press **Control+End**. Expect the last event (number 299,999, row 300,001) within a second.
   Press **Control+Home** to return.
5. Press **Page Down** several times. Expect large jumps, focus never lost, no silence longer
   than a second.
6. Leave the grid with **Tab** and find "Current event" by heading. Expect the event you were on,
   in full, with labels Number, Category, Action, Location, Details.
7. Choose the category "Network (net)". Expect "N of 300,000 events match" to be announced
   without moving focus.
8. Type `fdp.exe` in "Containing text" and apply. In the grid and in "Current event", the text
   should read "invoice backslash u 202e fdp.exe" or similar: the hidden reversing character is
   spoken as a code, not acted on.
9. Switch "Show events as" to "A simple table, 50 events per page". Use the screen reader's table
   commands (NVDA: **Control+Alt+arrows**; Orca: **Alt+Shift+arrows** or table navigation). Expect
   column headers to be read when changing columns. Activate **Next page**: expect "Page 2 of …".
10. **Most important question for this section:** in the grid, could you work as fast as in the
    simple table? Did the screen reader's browse mode (NVDA) or flat review (Orca) get confused
    by rows that are added and removed as you scroll? Note anything odd.

### D. Live progress (Running)

1. Under Display, set "Progress announcements" to "Every 5 seconds".
2. On Running, activate **Start pretend analysis**. Expect "Analysis started…".
3. Wait 20 seconds without touching anything. Expect a short progress sentence about every
   5 seconds, and nothing else. The changing numbers on screen must not be read on their own.
4. Press **Control+.** (full stop). Expect "Analysis finished. Outcome: stopped…".
5. Set the frequency to "Only start and finish", start again and wait. Expect no progress
   sentences.
6. Was the frequency right? Too chatty, or too quiet to know it was still working?

### E. Vision settings (no screen reader needed)

1. Windows: turn on a contrast theme (Settings → Accessibility → Contrast themes). The app's
   colours should follow it, with focus clearly visible everywhere. Linux: choose "High contrast"
   under Display.
2. Set Windows text size (Settings → Accessibility → Text size) or the GNOME "Large text"
   setting to its largest. Then also choose 200% under Display. Nothing should be cut off or
   overlap, except long text in grid cells, which is shortened with "…".
3. Make the window as narrow as it goes (320 pixels). The page should scroll only up and down;
   only the grid may scroll sideways.
4. Press **Control** and **+** several times (zoom). Same expectations.
5. Turn on "reduce animation" (Windows: Settings → Accessibility → Visual effects → Animation
   effects off). Nothing in the app moves anyway; check that nothing does.

## Results

Fill in one copy per tester and platform.

| Part | Step | Windows + NVDA | Linux + Orca | Notes |
| --- | --- | --- | --- | --- |
| A | 1–5 | | | |
| B | 1–6 | | | |
| C | 1–9 | | | |
| C | 10 | | | |
| D | 1–6 | | | |
| E | 1–5 | | | |

### Recorded result

17 November 2026: the project owner reported that the manual test **passes**. Per-step results
for each platform were not recorded; fill in the table above on the next round of testing.

**Decision: go.** Tauri with the system web view is kept on Windows and Linux, and phase 2
builds on the patterns tested here.

## Go or no-go

The trial passes, and phase 1 starts with the chosen toolkit, if:

- every step in A, B and D passes on both platforms; and
- either the grid (C 1–8) passes, or the simple table (C 9) passes and grid problems are
  fixable within the grid's own code; and
- E passes on both platforms.

If Linux with Orca fails parts that pass on Windows with NVDA, and the cause is WebKitGTK rather
than the app, the fallback in the plan applies: native Linux screens (Slint or GTK 4) sharing
the same backend crates. That decision is recorded here with the evidence.

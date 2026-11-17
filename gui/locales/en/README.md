# English text

Every word the desktop app shows or says is here, in [Fluent](https://projectfluent.org/)
files: one per screen, plus `app.ftl` (shared), `outcomes.ftl` (how runs end) and `errors.ftl`.
Code names a message with `t!("message-id")`, or `t!("message-id", count = 3)` with arguments.
A test (`crates/protocol/tests/ui_text.rs`) fails if code holds text of its own, names a message
that does not exist, or a message here is no longer used.

## Writing guide

- **Plain words, short sentences.** Write for someone who has never used a command line.
  "The sample kept waiting" rather than "sleep budget exhausted".
- **Say what happened, then what to do.** "The settings have a problem, so nothing was run.
  Check the settings and try again."
- **Explain a term the first time it appears** on a screen, or avoid it.
- **The same word for the same thing everywhere:** *sample* (the file analysed), *analysis* (one
  run of JavaByte), *report*, *settings*, *starting point*, *event*.
- **Numbers are arguments, never part of the text**, so plurals work: use a `$count ->` selector
  rather than writing "1 file(s)".
- **Text from a sample is only ever an argument** (`{ $name }`), never pasted into a message.
- **Buttons say what they do:** "Choose a sample file…" (with an ellipsis when another window
  opens), "Stop analysis", "Copy the list".
- Leave room: translations and large text sizes can make text twice as long.

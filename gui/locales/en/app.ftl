## Shared across the app.

app-name = JavaByte
# A list of two or more: "a, b and c". $first is everything before the last item, already
# joined with list-separator.
list-and = { $first } and { $last }
list-separator = {", "}
# The window title: the screen's name, then the app's.
window-title = { $screen } – JavaByte
skip-link = Skip to main content
nav-label = Main
screen-start = Start
screen-overview = Sample overview
screen-running = Running
screen-report = Report
screen-display = Display settings
nav-overview-unavailable = Choose a sample first.
nav-running-unavailable = Start an analysis first.
nav-report-unavailable = No report is open yet.
error-heading = Something went wrong
copied = Copied.
copy-unavailable = Copying is not available here. Select the text and press Control and C.
size-bytes = { $count ->
        [one] 1 byte
       *[other] { $count } bytes
    }
# $size is already formatted, with one decimal place.
size-kb = { $size } KB
size-mb = { $size } MB
screen-settings = Settings
nav-settings-unavailable = Choose a sample first.

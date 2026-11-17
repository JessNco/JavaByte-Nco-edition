## Running: one analysis in progress.

run-heading-running = Analysing { $name }
run-heading-done = Analysis of { $name }
run-finished-announce = Analysis finished. { $ended }
run-stopping = Stopping. What was found so far is kept.
run-stop-failed = The analysis could not be stopped. { $detail }
run-stop = Stop analysis
run-one-at-a-time = Only one analysis runs at a time. Stop it with the button or Control and full stop; what was found so far is kept.
run-finished-heading = Finished
run-view-report = View the report
run-no-report = No report was written. The analysis results folder may still hold a trace file.
run-another = Analyse another sample
run-progress-heading = Progress
run-state = State
run-state-starting = Starting
run-state-running = Running
run-state-finished = Finished: { $outcome }
run-start-point = Starting point
run-time = Time
# $seconds is already formatted, with one decimal place.
run-seconds = { $seconds } seconds
run-instructions = Instructions run
run-events = Events
run-by-category = Events by category
run-category = Category
run-notable = Latest notable events
run-notable-none = None yet.
# Spoken every few seconds while a run goes on (the interval is a display setting).
run-progress = { $seconds } seconds, { $events ->
        [one] 1 event
       *[other] { $events } events
    }, including { $network } network, { $programs } programs started and { $classes } classes created.

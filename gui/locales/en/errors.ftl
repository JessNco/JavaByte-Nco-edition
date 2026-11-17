## Problems, as shown to people. $detail is the technical reason, in English.

error-unreadable-line = Unreadable line from the tool: { $detail }
error-jar-missing = The JavaByte program was not found at { $path }.
error-java-start = Java could not be started ({ $path }): { $detail }
error-java-missing = Java 17 or later is needed and was not found. Install it, or set JAVA_HOME to it. (The installer will include it.)
error-tool-failed = The JavaByte tool failed (exit code { $code }): { $detail }
error-tool-output = Unexpected output from the JavaByte tool: { $detail }
error-stop-not-sent = The stop request could not be sent: { $detail }
error-not-found = { $path } could not be found.
error-already-running = An analysis is already running. Stop it or wait for it to finish.
error-results-folder = The results folder could not be created: { $detail }
error-no-report = No report is open.
error-run-gone = That run is no longer in the list.
error-report-unreadable = The report could not be read: { $detail }
error-not-a-report = This is not a JavaByte report: { $detail }
error-report-version = This report uses layout version { $found }, but this version of the app reads version { $supported }.

## How a run ended, as one or two sentences on the Running screen.

run-forced = The analysis did not stop when asked, so it was ended. Results may be incomplete; the trace file has everything recorded until then.
run-finished = The analysis finished.
# $outcome is one of the outcome-… sentences, or run-finished.
run-ended-events = { $outcome } { $count ->
        [one] 1 event was
       *[other] { $count } events were
    } recorded.
run-bad-settings = The settings have a problem, so nothing was run. Check the settings and try again.
run-tool-fault = The analysis tool itself failed. This is a fault in the tool, not something the sample did.
run-ended-code = The analysis tool ended unexpectedly (exit code { $code }).
run-ended = The analysis tool ended unexpectedly.
# Problems talking to the app's own backend. They point to a fault in the app.
error-backend-missing = The desktop backend is not available.
error-backend-call = The app could not call its backend: { $detail }
error-backend-reply = Unexpected reply from the backend: { $detail }
error-engine-missing = The analysis engine (javabyte.jar) was not found. Set JAVABYTE_JAR to its location, or build it with 'mvn package' in the JavaByte folder.

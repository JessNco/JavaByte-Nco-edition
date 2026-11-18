## Problems, as shown to people. $detail is the technical reason, in English.

error-unreadable-line = Unreadable line from the tool: { $detail }
error-jar-missing = The JavaByte program was not found at { $path }.
error-java-start = Java could not be started ({ $path }): { $detail }
error-java-missing = Java did not start. Reinstall JavaByte to restore the Java that comes with it, or choose a Java 17 or later in Preferences.
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
error-engine-missing = The analysis engine (javabyte.jar) was not found. Reinstall JavaByte. (In a build from source: set JAVABYTE_JAR, or run 'mvn package' in the JavaByte folder.)

## Problems editing the settings. $path is a setting's name, such as network.http[2].url.

config-no-item = { $path } has no item { $index }.
config-wrong-shape = { $path } does not fit how the settings are laid out there. Check it in the YAML tab.
config-not-a-list = { $path } is not a list.
config-single-value = { $path } holds a single value, not a group of settings.
config-not-a-mapping = The settings must be a set of named options, not a list or a single value.
error-no-sample = Choose a sample first.
error-preset-name = Give the preset a name, using letters, numbers and spaces.
error-preset-unreadable = These settings cannot be saved as a preset until the YAML can be read.
error-preset-save = The preset could not be saved: { $detail }
error-preset-read = The preset could not be read: { $detail }
error-preset-delete = The preset could not be deleted: { $detail }
error-import = The file could not be read: { $detail }
error-import-too-big = That file is too big to be a JavaByte settings file.
error-export = The settings could not be saved: { $detail }
error-artifact-refused = { $path } is not one of this run's saved files.
error-artifact-read = The file could not be read: { $detail }
error-save = The copy could not be saved: { $detail }
error-save-sample-folder = Choose another folder: copies are never saved next to the sample.
error-disasm = The class could not be shown as instructions: { $detail }
error-sample-moved = The sample is no longer at { $path }. Choose it again from the Start screen.
error-java-chosen-start = The Java at { $path } did not start. Choose the 'java' program of a Java 17 or later installation (in its 'bin' folder).
error-java-chosen-old = That Java is version { $version }. JavaByte needs Java 17 or later.
error-preferences-save = The preferences could not be saved: { $detail }
error-isolation-unconfirmed = This run cannot be isolated from the network, and that needs your agreement first. Start it again from the Analyse button.

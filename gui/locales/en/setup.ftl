## Preferences for how analyses run, the isolation question, and --self-check.

picker-java = Choose the 'java' program of a Java 17 or later
picker-programs = Programs
picker-output-folder = Choose where analyses are saved

isolation-unavailable = This system does not let JavaByte turn off the network for an analysis ({ $detail }). Installing the .deb package adds the permission Ubuntu 24.04 asks for.
isolation-unavailable-appimage = The AppImage cannot turn off the network for an analysis on this system ({ $detail }). Install the .deb package instead: it adds the permission Ubuntu 24.04 asks for.
isolation-not-on-this-system = Windows has no way to turn off the network for one program without administrator rights. Run JavaByte inside a virtual machine without network when you analyse live malware.

self-check-yes = yes
self-check-no = no
self-check-version = JavaByte { $version } on { $os }
self-check-java = Java: { $path } (version { $version }; bundled: { $bundled })
self-check-jar = Engine: { $path }
self-check-output = Analyses are saved in: { $path }
self-check-tool-works = The engine works.
self-check-tool-fails = The engine does not work: { $detail }
self-check-bridge = Native bridge: { $path } (Speakeasy imports: { $works })
self-check-no-bridge = Native bridge: not installed.
self-check-isolation-on = Network isolation for analyses: available.
self-check-isolation-off = Network isolation for analyses: not available. { $detail }

## The Preferences screen.

prefs-heading = Preferences
prefs-analyses-heading = Analyses
prefs-saved = Saved.
prefs-version = JavaByte { $version }. Free software under the GNU General Public License, version 3 or later, with no warranty.
prefs-output-heading = Where analyses are saved
prefs-output-current = Each analysis gets its own folder in:
prefs-output-choose = Choose another folder…
prefs-output-changed = Analyses will be saved in the chosen folder.
prefs-output-reset = Use Documents, JavaByte
prefs-output-reset-done = Analyses will be saved in Documents, JavaByte.
prefs-java-heading = Java
prefs-java-bundled = The Java that came with JavaByte (version { $version }):
prefs-java-chosen = The Java you chose (version { $version }):
prefs-java-system = The Java on this computer (version { $version }):
prefs-java-no-version = unknown: it did not start
prefs-java-choose = Choose another Java…
prefs-java-hint = Only needed to try another Java version. Choose the 'java' program in the 'bin' folder of a Java 17 or later.
prefs-java-changed = JavaByte will use the chosen Java.
prefs-java-reset = Use the Java that came with JavaByte
prefs-java-reset-done = JavaByte will use the Java that came with it.
prefs-isolation-heading = Network isolation
prefs-isolation-available = Available: each analysis can run with the network turned off.
prefs-isolate = Turn off the network for each analysis
prefs-isolate-hint = A second layer of protection. JavaByte already imitates every network call a sample makes; with this on, the analysis also has no network at all, in case of a flaw.
prefs-isolate-on = Analyses will run with the network turned off.
prefs-isolate-off = Analyses will run without network isolation.
prefs-isolation-ask = Ask me before an analysis that cannot be isolated
prefs-bridge-heading = Native bridge
prefs-bridge-installed = Installed. Turn on the native-bridge plugin in Settings to analyse a sample's Windows native libraries (DLLs).
prefs-bridge-missing = Not installed. To analyse a sample's Windows native libraries (DLLs), install the "with native bridge" version of JavaByte.

## The question before an analysis that cannot be isolated.

isolation-question-title = This analysis will not be isolated from the network
isolation-question-still-safe = JavaByte still imitates every network call the sample makes, so the sample cannot reach the network through JavaByte. Isolation is a second, independent layer in case of a flaw. Without it, run JavaByte in a virtual machine with no network when you analyse live malware.
isolation-question-dont-ask = Don't ask me again (you can change this in Preferences)
isolation-question-cancel = Cancel
isolation-question-run = Analyse without isolation

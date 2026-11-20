## The report's Limits and gaps tab.

limits-heading-endings = How each part ended
limits-heading-gaps = Where the analysis could not imitate the real thing
limits-heading-refused = Java features it refused
limits-heading-dropped = Events not kept
limits-none-gaps = None. Everything the sample did was imitated faithfully.
limits-none-refused = None.
limits-none-dropped = None. Every event was kept.
limits-gaps-intro = After a gap, the real sample might have behaved differently.
limits-refused-intro = The analysis refuses Java features that could reach outside it, such as the screen or real files. This is information about the sample, not an error.
limits-dropped-intro = Each category keeps a limited number of events. Further ones are counted but not stored.
limits-next = What to try: { $text }
limits-show-setting = Change this setting for a new run
limits-show-setting-label = Change { $setting } for a new run
limits-count = { $count ->
        [one] 1 time
       *[other] { $count } times
    }
limits-first = first at event { $seq }
limits-examples = Examples
limits-member = Feature
limits-action = What happened
limits-times = How often
limits-first-event = First event
limits-category = Category
limits-not-kept = Not kept
refusal-blocked = Refused
refusal-phantom-call = Answered with a harmless value
refusal-blocked-field-write = Change refused

## Gap kinds. Each has a title and what it means.

gap-other = { $kind }
gap-other-meaning = The analysis could not imitate this part faithfully.
gap-missing-class = Code it needs is missing
gap-missing-class-meaning = The sample uses classes that are not in it, usually from a library it expects the computer to have.
gap-invalid-bytecode = Code a real Java would refuse
gap-invalid-bytecode-meaning = A method holds code a real Java runtime would refuse to load. The sample was given the same error. It is either deliberately broken or was unpacked wrongly.
gap-unparseable-class = Files that could not be read
gap-unparseable-class-meaning = Some entries look like classes but are damaged or decoys. If the archive itself is damaged, classes after the damage are missing.
gap-unsupported = Something JavaByte does not imitate
gap-unsupported-meaning = The sample used a Java feature JavaByte does not imitate yet. It was given an error instead.
gap-stub-exception = A pretend Java feature failed
gap-stub-exception-meaning = One of JavaByte's stand-ins for a Java feature failed on what the sample gave it. The sample received the error.
gap-phantom-superclass = A parent class is missing
gap-phantom-superclass-meaning = The sample builds on a class that is not available, so that part of it could not be set up as on a real computer.
gap-phantom-superclass-call = A call into a missing parent class
gap-phantom-superclass-call-meaning = The sample called a method of a parent class that is not available. A harmless value was given back.
gap-shell-failed = A Java type could not be imitated
gap-shell-failed-meaning = One of the sample's classes extends a Java type JavaByte cannot imitate. It was treated as a plain object, so Java features may not work with it.
gap-thread-not-started = A thread never ran
gap-thread-not-started-meaning = The sample started more threads or timers than the limit allows, or one could not be started.
gap-native-bridge-unavailable = The native bridge could not start
gap-native-bridge-unavailable-meaning = The native bridge was turned on but its helper did not start, so native code was only reported, not run.
gap-native-bridge-load-failed = A native library could not be loaded
gap-native-bridge-load-failed-meaning = The native bridge could not load one of the sample's native libraries.
gap-native-bridge-not-an-array = Native code passed something that is not an array
gap-native-bridge-not-an-array-meaning = Native code asked about an array, but the value it passed is something else. The answer was "an empty array", so what the native code did next may differ.
gap-native-bridge-call-failed = A native call failed
gap-native-bridge-call-failed-meaning = The native bridge could not complete a call into native code.
gap-nested-native-call = Native code called back into native code
gap-nested-native-call-meaning = The native bridge does not follow native code that calls further native code through Java.
gap-unsupported-jni-callback = Native code used an unsupported Java call
gap-unsupported-jni-callback-meaning = Native code asked Java for something the native bridge does not offer.
gap-finish-failed = Saving results failed partly
gap-finish-failed-meaning = Some results could not be saved at the end of the run. What was saved is kept.
gap-plugin-close-failed = A plugin did not close cleanly
gap-plugin-close-failed-meaning = A plugin failed while finishing. Results are kept.

## What to try next.

next-timeout = Allow more time.
next-instructions = Allow more instructions.
next-silent-loop = If the sample was busy with real work, such as decrypting something large, allow more silent instructions under Limits.
next-sleeps = Give it the answer it waits for (a web answer), or allow more sleeps under Limits.
next-blocked-wait = Supply what it waits for, often a reply from a server.
next-entry = Choose where to start.
next-policy-abort = Let refused features be answered harmlessly instead of ending the run.
next-report-fault = This is a limit of JavaByte. If it matters, report it with the trace file from the results folder.
next-classpath = Add the library it needs under Libraries it needs.
next-native-bridge-python = Check the native bridge's Python interpreter.
next-tasks = Allow more threads and timers.
next-max-events = Keep more events per category.
next-allow = Allow a feature only if it has no effect outside the program.

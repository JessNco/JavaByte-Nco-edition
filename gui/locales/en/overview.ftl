## Sample overview: what the sample is, and where to start it.

overview-heading = Before analysing: { $name }
overview-started = Analysis of { $name } started.
overview-what-heading = What it is
overview-file = File
overview-size = Size
overview-classes = Classes
overview-start-point = Starting point
overview-start-agent = none; it is a Java agent ({ $class })
overview-start-none = none named
overview-native = Native code
overview-native-none = None
overview-native-some = { $methods ->
        [one] 1 native method
       *[other] { $methods } native methods
    }, { $libraries ->
        [one] 1 library
       *[other] { $libraries } libraries
    }
overview-hidden-calls = Hidden calls
overview-loaders = Own class loaders
overview-meaning-heading = What this means
# Before a finding's title, by how much it matters.
finding-important = Important: { $title }.
finding-note = Note: { $title }.
overview-start-heading = Where to start
overview-start-hint = The analysis runs the sample's code from this point.
overview-other = Another class with a main method
overview-other-help = { $count } more classes have one. Choose below.
overview-other-class = Class to start from
overview-command-heading = Same analysis on the command line
overview-copy-command = Copy command
overview-analyse = Analyse
overview-different-sample = Choose a different sample

## Where to start: options and their explanations.

entry-main = The sample's own starting point ({ $class })
entry-main-help = Runs the main method the sample names, as double-clicking it would.
entry-agent = Start it as an agent ({ $class }.premain)
entry-agent-help = Calls premain as the Java runtime would, with no options.
entry-class = Start from { $class }
entry-class-help = Runs this class's main method.
entry-sweep = Collect hidden text from every class's start-up code
entry-sweep-help = Runs only the code each class runs when it is first used. Most obfuscators decrypt their hidden text there, so this often reveals addresses and names without running the program itself.

## Findings: what the inspection means.

finding-agent = This is a Java agent
finding-agent-detail = It has no main method. It is meant to be loaded into another Java program, which starts it through its premain method. The analysis can start it the same way.
finding-no-entry = The sample names no starting point
finding-no-entry-none = No class has a main method. Plugins and mods are started by the program that loads them; collecting hidden text from every class's start-up code is the best first step.
finding-no-entry-choose = Choose a class to start from below. Classes with a main method are listed.
finding-native = Part of it is native code
finding-native-detail = { $methods ->
        [one] 1 method
       *[other] { $methods } methods
    } and { $libraries ->
        [one] 1 bundled library
       *[other] { $libraries } bundled libraries
    } are machine code, not Java. Without the native bridge their behaviour is reported but not run.
finding-hidden-calls = Calls are hidden by an obfuscator
finding-hidden-calls-detail = { $count ->
        [one] 1 call goes
       *[other] { $count } calls go
    } through the sample's own lookup code. The analysis follows them as it runs.
finding-loaders = It loads code of its own
finding-loaders-detail = The sample defines class loaders, a common way to unpack hidden stages. Classes it creates while running are saved and analysed too.
finding-damaged = Some files could not be read
finding-damaged-detail = { $count ->
        [one] 1 entry looks
       *[other] { $count } entries look
    } like classes but { $count ->
        [one] is
       *[other] are
    } damaged or decoys. This is common in protected samples.
overview-change-settings = Change settings first

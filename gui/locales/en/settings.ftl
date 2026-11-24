## Settings: forms and YAML for the next analysis.

settings-heading = Settings for { $name }
settings-intro = These settings are used when you analyse this sample. They start from what JavaByte suggests for it; change anything you need.
settings-tabs = Ways to edit the settings
settings-tab-forms = Forms
settings-tab-yaml = YAML
settings-tab-yaml-changed = YAML (not yet checked)
settings-jump = Sections
settings-more = More settings
settings-default = Default: { $value }
settings-reset = Reset to default
settings-reset-label = Reset to default: { $name }
settings-choose-file = Choose…
settings-choose-file-label = Choose…: a file for { $name }
settings-lines-hint = One per line.
settings-pairs-name = Name
settings-pairs-value = Value
settings-pairs-add = Add
settings-pairs-add-label = Add to { $name }
settings-pairs-remove = Remove
settings-pairs-remove-label = Remove { $key }
settings-pairs-empty = None.
settings-value-hint = Written as YAML: true, false, a number, or text.
settings-sample-hint = Chosen on the Start screen.

## Lists of items (HTTP answers, files, hooks, …).

items-empty = None yet.
items-add = Add { $item }
items-edit = Edit
items-edit-label = Edit { $item } { $number }
items-up = Move up
# Names start with the visible words (WCAG 2.5.3), so voice control can say "Move up".
items-up-label = Move up: { $item } { $number }
items-down = Move down
items-down-label = Move down: { $item } { $number }
items-remove = Remove
items-remove-label = Remove { $item } { $number }
items-number = Number
items-editing = Editing { $item } { $number } of { $count }
items-done = Done
items-added = { $item } { $number } added. Its fields are below.
items-moved = { $item } moved to position { $number }.
items-removed = { $item } { $number } removed.
items-actions = Actions

## Checking.

check-button = Check settings
check-checking = Checking the settings…
check-ok = The settings are ready.
check-problems = { $count ->
        [one] There is 1 problem in the settings
       *[other] There are { $count } problems in the settings
    }
check-failed = The settings could not be checked: { $detail }
check-before-analyse = Fix these before analysing.
syntax-heading = The YAML cannot be read
syntax-problem = Line { $line }, column { $column }: { $message }
syntax-go = Go to line { $line }
syntax-forms-off = The forms are hidden until the YAML can be read.
yaml-label = Settings as YAML
yaml-hint = The same settings as text. Changes appear in the forms after a short pause. Comments are kept.

## Actions.

settings-analyse = Analyse with these settings
settings-back = Back to the overview
settings-share-heading = Share these settings
settings-import = Import…
settings-export = Export…
settings-copy-command = Copy as command line
settings-command-heading = The same analysis on the command line
settings-imported = Settings imported from the file.
settings-import-other-sample = The file was made for another sample ({ $sample }). Its settings are used with the sample you chose.
settings-exported = Settings saved.

## Presets.

presets-heading = Presets
presets-intro = A preset fills in settings for a common next step. You see every change it makes, and can undo it.
presets-builtin = Common next steps
presets-saved = Your presets
presets-saved-none = None yet. Save the current settings as a preset to use them for other samples.
presets-apply = Apply
presets-apply-label = Apply preset { $name }
presets-delete = Delete
presets-delete-label = Delete preset { $name }
presets-applied = Preset { $name } applied. Undo is available.
presets-deleted = Preset { $name } deleted.
presets-undo = Undo the last preset
presets-undone = The settings are back as they were.
presets-save-name = Name for a new preset
presets-save = Save as preset
presets-saved-done = Saved as preset { $name }.
presets-folder = Presets are kept as YAML files in this folder, so they can be shared and used with javabyte run -c:
presets-copy-folder = Copy folder path

# Built-in presets, from the next steps in AGENTS.md. Some use the last report of this sample.
preset-relaunch = Use the arguments it started itself with
preset-relaunch-help = It started itself again with: { $args }. This runs it that way.
preset-sample-path = Act as the copy it installed
preset-sample-path-help = Set where the sample believes it is stored, for droppers that copy themselves.
preset-files = Supply the files it looked for
preset-files-help = { $count ->
        [one] Adds 1 file it tried to read
       *[other] Adds { $count } files it tried to read
    } to the pretend disk, for you to fill in.
preset-urls = Answer the web addresses it asked for
preset-urls-help = { $count ->
        [one] Adds an answer for 1 address
       *[other] Adds answers for { $count } addresses
    } that got none.
preset-sleeps = Let a waiting loop run longer
preset-sleeps-help = Raises the number of sleeps from { $from } to { $to }, for samples that wait for a server.
preset-hook = Force the result of a check
preset-hook-help = Adds a hook, for a method that checks for analysis tools.
preset-sweep = Also collect hidden text from start-up code
preset-sweep-help = Adds a run of every class's start-up code after the main one.
preset-clock = Make two runs comparable
preset-clock-help = Fixes the pretend clock, so two runs see the same time.
preset-native = Run its Windows native code
preset-native-help = Turns on the native bridge, which runs 64-bit Windows DLLs in a CPU emulator.

## Sections.

section-start = Sample and starting point
section-limits = Limits
section-environment = Pretend computer
section-network = Network
section-processes = Programs it starts
section-policy = Java features it may use
section-hooks = Hooks
section-trace = Extra recording
section-threads = Threads
section-output = Output
section-plugins = Plugins

## Every option. Names follow the option's path: opt-network-http-url is network.http[].url.

opt-sample = Sample
opt-classpath = Libraries it needs
opt-entryPoints = Starting points
opt-entryPoints-type = What to run
opt-entryPoints-class = Class
opt-entryPoints-method = Method
opt-entryPoints-descriptor = Method descriptor
opt-entryPoints-args = Arguments
opt-entryPoints-include = Classes to include
opt-limits = Limits
opt-limits-maxInstructions = Most instructions
opt-limits-maxSilentInstructions = Most instructions without any activity
opt-limits-timeoutSeconds = Time limit in seconds
opt-limits-maxCallDepth = Deepest call nesting
opt-limits-maxSleeps = Sleeps per thread
opt-limits-maxTasks = Most threads and timers
opt-limits-maxEventsPerCategory = Events kept per category
opt-limits-maxWrittenBytes = Most bytes written to disk
opt-environment = Pretend computer
opt-environment-os = Operating system
opt-environment-userName = User name
opt-environment-hostName = Computer name
opt-environment-samplePath = Where the sample is stored
opt-environment-properties = Java system properties
opt-environment-env = Environment variables
opt-environment-files = Files on the pretend disk
opt-environment-files-path = Path
opt-environment-files-text = Content as text
opt-environment-files-base64 = Content as base64
opt-environment-files-hostFile = Content from a file on this computer
opt-environment-directories = Folders on the pretend disk
opt-environment-clockStart = Clock start
opt-environment-timeZone = Time zone
opt-environment-locale = Language and region
opt-environment-randomSeed = Random seed
opt-environment-manufacturer = Manufacturer
opt-environment-model = Model
opt-environment-processors = Processors
opt-environment-memoryMegabytes = Memory (MB)
opt-environment-screenWidth = Screen width
opt-environment-screenHeight = Screen height
opt-environment-processId = Process id
opt-environment-jvmArguments = JVM options
opt-environment-builtInCommands = Answer common system commands
opt-network = Network
opt-network-mode = Network behaviour
opt-network-http = Web answers
opt-network-http-url = Address pattern
opt-network-http-status = Status code
opt-network-http-headers = Headers
opt-network-http-body = Body as text
opt-network-http-bodyBase64 = Body as base64
opt-network-http-bodyFile = Body from a file on this computer
opt-network-sockets = Connection answers
opt-network-sockets-endpoint = Host and port pattern
opt-network-sockets-text = Data as text
opt-network-sockets-base64 = Data as base64
opt-network-sockets-file = Data from a file on this computer
opt-network-dns = Host name lookups
opt-network-defaultAddress = Address for other host names
opt-network-localAddress = This computer's address
opt-network-macAddress = Network card address
opt-processes = Program results
opt-processes-command = Command pattern
opt-processes-stdout = Output
opt-processes-stderr = Error output
opt-processes-exitCode = Exit code
opt-policy = Java features
opt-policy-allow = Also allow
opt-policy-deny = Also refuse
opt-policy-onBlocked = When a feature is refused
opt-hooks = Hooks
opt-hooks-method = Method pattern
opt-hooks-action = Action
opt-hooks-value = Value
opt-trace = Extra recording
opt-trace-strings = Text returned by methods
opt-trace-methods = Methods to record
opt-trace-instructions = Methods to record instruction by instruction
opt-trace-hostCalls = Record every call into Java itself
opt-threads = Threads
opt-threads-mode = When new threads run
opt-threads-periodicRuns = Runs of repeating timers
opt-threads-runShutdownHooks = Run the sample's shutdown code at the end
opt-output = Output
opt-output-directory = Results folder (command line only)
opt-output-dumpDefinedClasses = Save code it creates
opt-output-dumpDroppedFiles = Save files it writes
opt-output-console = What the command prints
opt-plugins = Plugins
output-directory-note = The app keeps each analysis in its own folder under Documents/JavaByte; this folder is used when the settings are run on the command line.

## Choices. Each shows the word the settings file uses.

choice-entryPoints-type-main = Its main method (main)
choice-entryPoints-type-method = One named method (method)
choice-entryPoints-type-clinit-sweep = Every class's start-up code (clinit-sweep)
choice-environment-os-windows = Windows (windows)
choice-environment-os-linux = Linux (linux)
choice-network-mode-respond = Connections work and get made-up answers (respond)
choice-network-mode-offline = Connections fail, as with no network (offline)
choice-policy-onBlocked-phantom = Pretend it worked and carry on (phantom)
choice-policy-onBlocked-throw = Give the sample an error (throw)
choice-policy-onBlocked-abort = End the analysis (abort)
choice-hooks-action-return = Give back a value (return)
choice-hooks-action-skip = Do nothing (skip)
choice-hooks-action-throw = Raise an exception (throw)
choice-hooks-action-trace = Only record the call (trace)
choice-trace-strings-static = From static methods, where decryption usually is (static)
choice-trace-strings-all = From all methods (all)
choice-trace-strings-none = None (none)
choice-threads-mode-deferred = When the current thread waits (deferred)
choice-threads-mode-inline = At once (inline)
choice-output-console-text = A readable summary (text)
choice-output-console-json = The report as JSON (json)
choice-output-console-quiet = Nothing (quiet)

## Items of lists, as in "Editing web answer 2 of 3".

item-entryPoints = starting point
item-environment-files = file
item-network-http = web answer
item-network-sockets = connection answer
item-processes = program result
item-hooks = hook

## Plugins.

plugin-use = Use { $name }
plugin-native-bridge = The native bridge
opt-plugins-native-bridge-python = Python interpreter
settings-loading = Loading the settings…
# Before a field's own problem, so it is not shown by colour alone.
field-problem = Problem: { $message }

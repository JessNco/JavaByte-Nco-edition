# Configuration reference

A config is one YAML or JSON document. Every key is optional except that a sample must be given
either as `sample` or on the command line. Unknown keys are rejected.

Print the built-in defaults at any time:

```bash
java -jar target/javabyte-0.2.0.jar defaults
```

Override single values without editing the file, using dotted paths. Values are parsed as YAML,
so lists and numbers keep their types:

```bash
java -jar target/javabyte-0.2.0.jar run -c sample.yaml -s limits.maxSleeps=5 -s 'entryPoints=[{type: clinit-sweep}]'
```

Class names may be written with dots or slashes. Method patterns use slashes, as in bytecode:
`com/example/Main.run()V`.

## Top level

| Key | Default | Meaning |
| --- | --- | --- |
| `sample` | none | Jar, zip, single `.class` file, or directory of classes |
| `classpath` | `[]` | More jars or directories to interpret alongside the sample |
| `entryPoints` | manifest `Main-Class` | What to execute, in order |
| `limits` | see below | Budgets that end a run with a named reason; [details](#limits) |
| `environment` | see below | The pretend machine; [details](#environment) |
| `network` | see below | The simulated network; [details](#network) |
| `processes` | `[]` | Made-up results for programs the sample starts; [details](#processes) |
| `policy` | see below | Changes to the JDK allow-list; [details](#policy) |
| `hooks` | `[]` | Overrides for chosen methods; [details](#hooks) |
| `trace` | see below | Extra recording; [details](#trace) |
| `threads` | see below | Thread scheduling; [details](#threads) |
| `output` | see below | Where results go; [details](#output) |
| `plugins` | `{}` | Settings per plugin id; a plugin runs only if it has an entry here |

`javabyte schema` prints every option with its type, default, allowed values and meaning, from
the same descriptions this page is checked against; `--json` also lists the plugins and their
settings. `javabyte validate -c FILE` checks a config
and lists every problem at once.

## `entryPoints[]`

| Key | Meaning |
| --- | --- |
| `type` | `main`, `method` or `clinit-sweep` |
| `class` | Class to run. For `main`, defaults to the manifest's `Main-Class` |
| `method` | Method name (`method` type only) |
| `descriptor` | JVM descriptor; needed only when `method` is overloaded |
| `args` | For `main`: program arguments. For `method`: one value per parameter |
| `include` | For `clinit-sweep`: class-name globs to include; empty means all |

- `main` runs `public static void main(String[])`.
- `method` runs any method; `class` and `method` are required. An instance method is called on
  an object made with the no-argument constructor. Arguments are converted by parameter type; a
  `byte[]` parameter takes base64 and a `String[]` parameter takes a list.
- `clinit-sweep` runs every class's static initialiser, each isolated from the others. Most
  string-encrypting obfuscators decrypt their tables there, so this harvests strings without
  running program logic.

After each entry point returns, threads and timers it started are run.

## `limits`

| Key | Default | Meaning |
| --- | --- | --- |
| `maxInstructions` | 2000000000 | Total bytecode instructions before the run stops |
| `maxSilentInstructions` | 500000000 | Instructions one task may run without recording anything before it is stopped (outcome `silent-loop`); 0 never stops one |
| `timeoutSeconds` | 120 | Wall-clock limit for the whole run |
| `maxCallDepth` | 1500 | Guest call depth before a guest `StackOverflowError` |
| `maxSleeps` | 40 | Sleeps one task may perform before it is stopped |
| `maxTasks` | 512 | Threads, timers and executor jobs one run may queue |
| `maxEventsPerCategory` | 20000 | Events stored per category; the rest are only counted |
| `maxWrittenBytes` | 268435456 | Total bytes the sample may write to the virtual disk. The files saved while it runs (classes it defines, data it sends, libraries it loads) are held to the same total; past it they are not saved and the run ends with a `gap.artifacts-not-saved` event |

`maxSleeps` is what ends beacon and retry loops. Sleeping takes no real time: the virtual clock
jumps forward. Every timed wait (`wait(ms)`, `join(ms)`, `parkNanos`, `Future.get(timeout)`,
delayed and repeating executor or timer tasks) also passes in virtual time; a notify or a
finished task still ends it early.

`maxSilentInstructions` is what ends a spin loop: a task waiting on a flag that nothing will
ever set, often because the code that should set it failed. "Silent" means the report records
nothing at all: no output, no file, network, process or reflection activity. Halfway to the
limit (or after 100 million instructions, if that comes first) a `lifecycle.silent-loop` event
names where the guest is looping; at the limit only that task is stopped, and the rest of the
run carries on. Raise it, or set 0, for a sample that computes for a long time without
touching anything, such as a slow key derivation.

Every run limit's message says where the guest was when it stopped, as a short stack.

All limits must be more than 0, except `maxSleeps`, `maxTasks` and `maxSilentInstructions`,
which may be 0.

## `environment`

| Key | Default | Meaning |
| --- | --- | --- |
| `os` | `windows` | `windows` or `linux`: path syntax, properties, variables |
| `userName` | `admin` | `user.name` and the home directory |
| `hostName` | `DESKTOP-4QK7B2M` | Computer name |
| `samplePath` | Downloads folder | Where the sample believes its own file is |
| `properties` | `{}` | Extra or overriding `System.getProperty` values |
| `env` | `{}` | Extra or overriding environment variables |
| `files` | `[]` | Files that exist before the run |
| `directories` | `[]` | Directories that exist before the run |
| `clockStart` | now | Start of the virtual clock, ISO-8601 |
| `timeZone` | `America/New_York` | The guest's time zone (`TimeZone.getDefault()`, `user.timezone`, `Date.toString()`, `Calendar`, date formats). The host's own zone is never shown |
| `locale` | `en-US` | The guest's language and region as a language tag (`Locale.getDefault()`, `user.language`, `user.country`, `String.format`, number and date formats) |
| `randomSeed` | `1247901281` | Seed for `Math.random`, `new Random()`, `SecureRandom`, `ThreadLocalRandom` and `UUID.randomUUID`. `null` uses real entropy |
| `manufacturer` | `Dell Inc.` | Computer maker shown by wmic, systeminfo and /sys/class/dmi |
| `model` | `OptiPlex 7050` | Computer model shown by wmic, systeminfo and /sys/class/dmi |
| `processors` | `4` | Logical processors: `Runtime.availableProcessors`, `NUMBER_OF_PROCESSORS`, the management beans, wmic and /proc/cpuinfo |
| `memoryMegabytes` | `16384` | Installed memory: the management beans, `Runtime` heap defaults, wmic and systeminfo |
| `screenWidth` | `1920` | Screen width reported by `java.awt.Toolkit` and `GraphicsEnvironment` |
| `screenHeight` | `1080` | Screen height reported by `java.awt.Toolkit` and `GraphicsEnvironment` |
| `processId` | `6732` | The sample's own process id; programs it starts get higher ids |
| `jvmArguments` | `[]` | Options `RuntimeMXBean.getInputArguments` reports, such as `-agentlib:jdwp` to trigger debugger checks |
| `builtInCommands` | `true` | Answer whoami, hostname, ver, echo, tasklist, wmic, systeminfo, ipconfig and getmac (whoami, hostname, uname, id and nproc on Linux) from the pretend machine when no `processes` rule matches |

With `clockStart` and `randomSeed` both fixed, two runs of the same sample with the same config
give the same report. By default only the clock differs between runs: it starts at the real
current time, because samples with date checks should see today's date.

The guest's time zone and locale come from `timeZone` and `locale`, never from the analysis
computer. `timeZone` must be a zone the JDK knows, such as `Europe/London` (a fixed offset such
as `+02:00` is accepted too). `locale` must be a language tag with at least a language, such as
`de-DE`. `clockStart` must be an ISO-8601 time. `processors`, `memoryMegabytes`, `screenWidth`,
`screenHeight` and `processId` must be more than 0.

Each `files[]` entry has `path` and one of `text`, `base64`, or `hostFile` (a real file whose
bytes back the virtual one).

The emulated Java runtime is Oracle Java 1.8.0_361 on Windows and Ubuntu's OpenJDK 1.8.0_392 on
Linux, with the full set of standard Java 8 system properties. The disk looks like a used
desktop: system binaries under `C:\Windows` (dated to the install date, about 13 months before
`clockStart`), Program Files and Program Files (x86) with common software, shortcuts and files
on the Desktop and in Recent, and `java.home` with `bin\java.exe`. On Linux, /etc/hostname,
/etc/os-release, /sys/class/dmi/id and /proc/cpuinfo describe the same machine. The sample file
itself is present at `samplePath`.

## `network`

| Key | Default | Meaning |
| --- | --- | --- |
| `mode` | `respond` | `respond`: connections succeed. `offline`: they fail as on an unplugged machine |
| `http` | `[]` | Canned HTTP answers |
| `sockets` | `[]` | Bytes a raw socket receives after connecting |
| `dns` | `{}` | Host name to IPv4 address |
| `defaultAddress` | `203.0.113.10` | Address for unlisted names |
| `localAddress` | `192.168.1.23` | The machine's own address; its router (gateway, DHCP and DNS) is .1 of the same /24 |
| `macAddress` | `D4:BE:D9:5E:3A:9C` | Reported by network interface queries, ipconfig and getmac |

`defaultAddress`, `localAddress` and every `dns` value must be IPv4 dotted quads (four parts,
0-255). `macAddress` must be six two-digit hex octets separated by `:` or `-`. A run with any
other value stops before it starts, naming the key, and `javabyte validate` lists it.

`http[]`: `url` is a regular expression searched for in the full URL; the first matching rule
wins. Set `status`, `headers`, and one of `body`, `bodyBase64`, `bodyFile`. With no matching rule
the answer is an empty 200. An `http:` URL gives a plain `HttpURLConnection` and an `https:` URL
an `HttpsURLConnection`, as on a real JVM.

`sockets[]`: `endpoint` is a regular expression matched against `host:port`. Set one of `text`,
`base64`, `file`. With no matching rule the peer sends nothing and reads reach end of stream.

TLS sockets and HTTPS use the same rules. No handshake takes place, so what the sample sends is
recorded in clear.

## `processes[]`

No process is ever started. Each rule supplies the output for launches whose joined command
line contains a match for `command` (a regular expression): `stdout`, `stderr`, `exitCode`.
When no rule matches and `environment.builtInCommands` is on, common system commands are
answered from the simulated machine and the event's `matchedRule` is `built-in:<program>`.
Anything else prints nothing and exits with 0.

What the sample writes to a launched process's standard input is recorded as `process.stdin`
events, with the bytes saved under `processes/`.

`command` (like `http[].url` and `sockets[].endpoint`) is required and must be a valid regular
expression.

## `policy`

| Key | Default | Meaning |
| --- | --- | --- |
| `allow` | `[]` | Extra allow rules |
| `deny` | `[]` | Extra deny rules; a deny always wins |
| `onBlocked` | `phantom` | `phantom`: record and continue with a neutral value. `throw`: raise a guest `SecurityException`. `abort`: stop the run |

Rule syntax (the same as the built-in list shown by `javabyte policy`):

```text
java/lang/String                  every member of one class
java/util/**                      every class in a package tree
java/lang/*Exception              * matches within one name segment
java/lang/Thread#getName,setName  only the listed members
java/io/PrintStream#<init>(Ljava/lang/String;)V   one exact overload
```

Allow a member only if it cannot read or change anything outside the emulator's own memory.
Anything that reaches the disk, the network, another process or a thread needs a stub instead.

## `hooks[]`

| Key | Meaning |
| --- | --- |
| `method` | Glob over `owner.name` or `owner.name(descriptor)`; `*` matches any text. Required |
| `action` | `return`, `skip`, `throw` or `trace` |
| `value` | For `return`: the value. For `throw`: an exception class name |

- `return` makes the method return `value` without running.
- `skip` makes it return zero, false or null.
- `throw` makes it throw a new instance of the named exception.
- `trace` logs each call with arguments and result to `trace.log`.

In `method`, `*` matches any text and `?` matches exactly one character. The same pattern
syntax is used by plugins (`vm.hooks.add`, `vm.natives.register`). A plugin hook with action
`trace` records calls like a config trace hook; any action other than return, skip, throw or
trace is rejected.

Hooks apply to interpreted methods, including methods of classes defined at run time, and to
JDK methods.

## `trace`

| Key | Default | Meaning |
| --- | --- | --- |
| `strings` | `static` | Record `String` results of `static` methods, `all` methods, or `none` |
| `methods` | `[]` | Method globs whose calls are logged |
| `instructions` | `[]` | Method globs to single-step into `trace.log` |
| `hostCalls` | `false` | Log every call into the real JDK |
| `previewLength` | `2000` | How much of a value an event shows: characters of a recorded string, bytes of cipher output and of what was hashed, MACed or fed to a key derivation |

## `threads`

| Key | Default | Meaning |
| --- | --- | --- |
| `mode` | `deferred` | `deferred`: a started thread first runs when the current one blocks or its time slice ends. `inline`: it runs first, at `start()` |
| `periodicRuns` | 1 | Times a repeating timer or scheduled task body is run; at least 1 |
| `runShutdownHooks` | `true` | Run registered shutdown hooks at the end |

Guest threads are interleaved one at a time. A thread is switched out when it blocks and after
a fixed number of instructions, so the order is the same on every run with the same config.

## `output`

| Key | Default | Meaning |
| --- | --- | --- |
| `directory` | `javabyte-out` | Where the report, trace and artifacts go |
| `dumpDefinedClasses` | `true` | Save classes defined at run time |
| `dumpDroppedFiles` | `true` | Save files the sample created |
| `console` | `text` | `text` summary, `json` report on stdout, or `quiet` |

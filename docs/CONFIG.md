# Configuration reference

A config is one YAML or JSON document. Every key is optional except that a sample must be given
either as `sample` or on the command line. Unknown keys are rejected.

Print the built-in defaults at any time:

```bash
java -jar target/javabyte-0.1.0.jar defaults
```

Override single values without editing the file, using dotted paths. Values are parsed as YAML,
so lists and numbers keep their types:

```bash
java -jar target/javabyte-0.1.0.jar run -c sample.yaml -s limits.maxSleeps=5 -s 'entryPoints=[{type: clinit-sweep}]'
```

Class names may be written with dots or slashes. Method patterns use slashes, as in bytecode:
`com/example/Main.run()V`.

## Top level

| Key | Default | Meaning |
| --- | --- | --- |
| `sample` | none | Jar, zip, single `.class` file, or directory of classes |
| `classpath` | `[]` | More jars or directories to interpret alongside the sample |
| `entryPoints` | manifest `Main-Class` | What to execute, in order |
| `plugins` | `{}` | Settings per plugin id; a plugin runs only if it has an entry here |

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
- `method` runs any method. An instance method is called on an object made with the no-argument
  constructor. Arguments are converted by parameter type; a `byte[]` parameter takes base64 and a
  `String[]` parameter takes a list.
- `clinit-sweep` runs every class's static initialiser, each isolated from the others. Most
  string-encrypting obfuscators decrypt their tables there, so this harvests strings without
  running program logic.

After each entry point returns, threads and timers it started are run.

## `limits`

| Key | Default | Meaning |
| --- | --- | --- |
| `maxInstructions` | 2000000000 | Total bytecode instructions before the run stops |
| `timeoutSeconds` | 120 | Wall-clock limit for the whole run |
| `maxCallDepth` | 1500 | Guest call depth before a guest `StackOverflowError` |
| `maxSleeps` | 40 | Sleeps one task may perform before it is stopped |
| `maxTasks` | 512 | Threads, timers and executor jobs one run may queue |
| `maxEventsPerCategory` | 20000 | Events stored per category; the rest are only counted |
| `maxWrittenBytes` | 268435456 | Total bytes the sample may write to the virtual disk |

`maxSleeps` is what ends beacon and retry loops. Sleeping takes no real time: the virtual clock
jumps forward.

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

Each `files[]` entry has `path` and one of `text`, `base64`, or `hostFile` (a real file whose
bytes back the virtual one).

The emulated Java runtime reports version 1.8. A default set of directories exists (user
profile, Desktop, Downloads, AppData, Startup, Temp, and `java.home` with `bin\java.exe`). The
sample file itself is present at `samplePath`.

## `network`

| Key | Default | Meaning |
| --- | --- | --- |
| `mode` | `respond` | `respond`: connections succeed. `offline`: they fail as on an unplugged machine |
| `http` | `[]` | Canned HTTP answers |
| `sockets` | `[]` | Bytes a raw socket receives after connecting |
| `dns` | `{}` | Host name to IPv4 address |
| `defaultAddress` | `203.0.113.10` | Address for unlisted names |
| `localAddress` | `192.168.56.101` | The machine's own address |
| `macAddress` | `00:1B:44:11:3A:B7` | Reported by network interface queries |

`http[]`: `url` is a regular expression searched for in the full URL; the first matching rule
wins. Set `status`, `headers`, and one of `body`, `bodyBase64`, `bodyFile`. With no matching rule
the answer is an empty 200.

`sockets[]`: `endpoint` is a regular expression matched against `host:port`. Set one of `text`,
`base64`, `file`. With no matching rule the peer sends nothing and reads reach end of stream.

TLS sockets and HTTPS use the same rules. No handshake takes place, so what the sample sends is
recorded in clear.

## `processes[]`

No process is ever started. Each rule supplies the output for launches whose joined command
line contains a match for `command` (a regular expression): `stdout`, `stderr`, `exitCode`.
Without a rule a launch "succeeds" with empty output and exit code 0.

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
| `method` | Glob over `owner.name` or `owner.name(descriptor)`; `*` matches any text |
| `action` | `return`, `skip`, `throw` or `trace` |
| `value` | For `return`: the value. For `throw`: an exception class name |

- `return` makes the method return `value` without running.
- `skip` makes it return zero, false or null.
- `throw` makes it throw a new instance of the named exception.
- `trace` logs each call with arguments and result to `trace.log`.

Hooks apply to interpreted methods, including methods of classes defined at run time, and to
JDK methods.

## `trace`

| Key | Default | Meaning |
| --- | --- | --- |
| `strings` | `static` | Record `String` results of `static` methods, `all` methods, or `none` |
| `methods` | `[]` | Method globs whose calls are logged |
| `instructions` | `[]` | Method globs to single-step into `trace.log` |
| `hostCalls` | `false` | Log every call into the real JDK |

## `threads`

| Key | Default | Meaning |
| --- | --- | --- |
| `mode` | `deferred` | `deferred`: a started thread first runs when the current one blocks or its time slice ends. `inline`: it runs first, at `start()` |
| `periodicRuns` | 1 | Times a repeating timer or scheduled task body is run |
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

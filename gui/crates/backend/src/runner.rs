//! Starts and watches the command-line tool: the only way the GUI analyses anything.
//!
//! The emulator always runs in a separate Java process. That keeps emulation speed exactly what
//! it is on the command line, keeps a runaway or crashing analysis away from the window, and
//! means a run can always be ended: politely with `stop` on its standard input, and if that is
//! ignored, by ending the process.
//!
//! Everything a run reports arrives through the tool's JSON Lines event stream
//! (`run --events jsonl`), parsed with the types in `javabyte_gui_protocol`. Text meant for people
//! is never parsed.

use javabyte_gui_protocol::inspect::Inspection;
use javabyte_gui_protocol::report::describe_outcome;
use javabyte_gui_protocol::schema::Schema;
use javabyte_gui_protocol::stream::{self, Finish, Progress, RunResult, Start, StreamRecord};
use javabyte_gui_protocol::validate::Validation;
use std::collections::BTreeMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, ExitStatus, Stdio};
use std::sync::mpsc::{Receiver, RecvTimeoutError, channel};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

/// Exit codes of the tool, as documented in its README.
pub mod exit_code {
    /// The command did its job (for `run`: the report says what the sample did).
    pub const OK: i32 = 0;
    /// The tool itself failed: a fault in JavaByte.
    pub const TOOL_FAULT: i32 = 1;
    /// Bad usage or invalid settings; nothing was run.
    pub const BAD_SETTINGS: i32 = 2;
    /// The run reached its instruction or time limit; the report is still written.
    pub const LIMIT_REACHED: i32 = 3;
}

/// How an analysis is kept from the network and from the person's files
/// (docs/gui/PHASE5-PLAN.md, WINDOWS-SANDBOX.md).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub enum RunIsolation {
    /// Not isolated: the tool's own simulation of the network is the only layer.
    #[default]
    None,
    /// Linux: a new user, network and mount namespace, with the file system read-only except
    /// for the run's folder and a private temporary folder, and host service sockets hidden
    /// (`crate::isolation`).
    Namespace,
    /// Windows: an AppContainer with no capabilities, through the sandbox add-on at this path.
    AppContainer(PathBuf),
    /// Windows: a fresh Windows Sandbox, through the sandbox add-on at this path.
    WindowsSandbox(PathBuf),
}

/// Where the Windows Sandbox add-on shows the run's folders inside the sandbox.
const SANDBOX_RUNTIME: &str = r"C:\JavaByteRuntime";
const SANDBOX_TOOL: &str = r"C:\JavaByteTool";
const SANDBOX_BRIDGE: &str = r"C:\JavaByteBridge";
const SANDBOX_RUN: &str = r"C:\JavaByteRun";
const SANDBOX_SAMPLE: &str = r"C:\JavaByteSample";

/// How to start the tool: which Java, which JavaByte jar, and how.
#[derive(Clone, Debug)]
pub struct Launcher {
    /// The Java runtime to start.
    pub java: PathBuf,
    /// The JavaByte tool's jar.
    pub jar: PathBuf,
    /// How runs are kept from the network. Short commands such as `inspect` and `schema` only
    /// read the sample as data and always start without it.
    pub isolation: RunIsolation,
    /// The native bridge's Python, passed as `JAVABYTE_BRIDGE_PYTHON` so the `native-bridge`
    /// plugin uses it when the settings name no other.
    pub bridge_python: Option<PathBuf>,
}

/// What to analyse and how.
#[derive(Clone, Debug, Default)]
pub struct RunRequest {
    /// The sample to analyse.
    pub sample: PathBuf,
    /// The folder the report and saved files go to.
    pub output: PathBuf,
    /// A YAML or JSON config file, if any.
    pub config: Option<PathBuf>,
    /// `key.path=value` overrides, applied after the file.
    pub overrides: Vec<String>,
}

impl Launcher {
    /// Uses the given Java and jar, without isolation or bridge.
    pub fn new(java: impl Into<PathBuf>, jar: impl Into<PathBuf>) -> Self {
        Self { java: java.into(), jar: jar.into(), isolation: RunIsolation::None, bridge_python: None }
    }

    /// Uses the given jar, and finds Java: `JAVABYTE_JAVA`, then `JAVA_HOME`, then `java` on the
    /// path. For development and tests; the installed app uses the runtime it bundles.
    pub fn find(jar: impl Into<PathBuf>) -> Result<Self, String> {
        let jar = jar.into();
        if !jar.is_file() {
            return Err(javabyte_gui_protocol::t!("error-jar-missing", path = jar.display().to_string()));
        }
        let executable = if cfg!(windows) { "java.exe" } else { "java" };
        let java = std::env::var_os("JAVABYTE_JAVA")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("JAVA_HOME").map(|home| PathBuf::from(home).join("bin").join(executable)))
            .filter(|path| path.is_file())
            .unwrap_or_else(|| PathBuf::from(executable));
        Ok(Self::new(java, jar))
    }

    /// Whether runs go through Windows Sandbox, which takes a while to start.
    pub fn uses_windows_sandbox(&self) -> bool {
        matches!(self.isolation, RunIsolation::WindowsSandbox(_))
    }

    /// The same engine with an AppContainer instead of Windows Sandbox, for when the sandbox
    /// could not start and the person agreed to the lighter isolation.
    pub fn without_windows_sandbox(&self) -> Launcher {
        let mut lighter = self.clone();
        if let RunIsolation::WindowsSandbox(helper) = &self.isolation {
            lighter.isolation = RunIsolation::AppContainer(helper.clone());
        }
        lighter
    }

    /// The folder holding Java's `bin` folder.
    fn java_home(&self) -> PathBuf {
        self.java.parent().and_then(Path::parent).map_or_else(|| self.java.clone(), Path::to_path_buf)
    }

    /// `java -jar <jar> <args>`, without a console window on Windows.
    fn command(&self, args: &[&std::ffi::OsStr]) -> Command {
        let mut command = Command::new(&self.java);
        command.arg("-jar").arg(&self.jar).args(args);
        self.prepare(&mut command);
        command
    }

    /// `java -jar <jar> <args>` for an analysis, isolated as the launcher says. Through the
    /// sandbox add-on, the files the run needs are named for it: the runtime, the tool and the
    /// bridge to read, the sample to read (copied into Windows Sandbox when it is a file), and
    /// the run's folder to write.
    ///
    /// An error means the isolation could not be prepared, and the run must not start.
    fn run_command(&self, request: &RunRequest, args: &[&std::ffi::OsStr]) -> std::io::Result<Command> {
        let (helper, sandbox) = match &self.isolation {
            RunIsolation::AppContainer(helper) => (helper, false),
            RunIsolation::WindowsSandbox(helper) => (helper, true),
            #[cfg(target_os = "linux")]
            RunIsolation::Namespace => {
                // Java's temporary files go to the run's private folder, the one place it may
                // write besides the run's folder.
                let mut command = Command::new(&self.java);
                command.arg(format!("-Djava.io.tmpdir={}", crate::isolation::PRIVATE_TEMP)).arg("-jar").arg(&self.jar).args(args);
                self.prepare(&mut command);
                // The bridge's Python is at <home>/bin/python3, and needs the rest of its home.
                let bridge_home = self.bridge_python.as_ref().and_then(|p| p.parent()).and_then(Path::parent).map(Path::to_path_buf);
                let readable = [Some(self.java_home()), Some(self.jar.clone()), bridge_home, Some(request.sample.clone()), request.config.clone()];
                let access = crate::isolation::Access { writable: Some(request.output.clone()), readable: readable.into_iter().flatten().collect() };
                crate::isolation::isolate(&mut command, &access)?;
                return Ok(command);
            }
            _ => return Ok(self.command(args)),
        };
        let mut command = Command::new(helper);
        let bridge_root = self.bridge_python.as_ref().and_then(|p| p.parent()).map(Path::to_path_buf);
        let tool_dir = self.jar.parent().map_or_else(|| self.jar.clone(), Path::to_path_buf);
        if sandbox {
            let map = |host: &Path, guest: &str, writable: bool| {
                let mut value = host.as_os_str().to_owned();
                value.push(format!("={guest}{}", if writable { ":rw" } else { "" }));
                value
            };
            command.arg("windows-sandbox");
            command.arg("--map").arg(map(&self.java_home(), SANDBOX_RUNTIME, false));
            command.arg("--map").arg(map(&tool_dir, SANDBOX_TOOL, false));
            if let Some(root) = &bridge_root {
                command.arg("--map").arg(map(root, SANDBOX_BRIDGE, false));
            }
            command.arg("--map").arg(map(&request.output, SANDBOX_RUN, true));
            if request.sample.is_dir() {
                command.arg("--map").arg(map(&request.sample, SANDBOX_SAMPLE, false));
            } else {
                command.arg("--stage").arg(&request.sample);
            }
        } else {
            command.arg("appcontainer");
            for path in [Some(self.java_home()), Some(self.jar.clone()), bridge_root, Some(request.sample.clone())].into_iter().flatten() {
                command.arg("--read").arg(path);
            }
            command.arg("--write").arg(&request.output);
        }
        command.arg("--").arg(&self.java).arg("-jar").arg(&self.jar).args(args);
        self.prepare(&mut command);
        Ok(command)
    }

    /// The environment and window settings every command shares. The tool gets a short list
    /// of the app's environment variables ([`passes_to_the_tool`]) and no others: whatever
    /// else the person's environment holds, such as access tokens for other services, is not
    /// there to be read if a sample ever got out of the emulator.
    fn prepare(&self, command: &mut Command) {
        command.env_clear();
        command.envs(std::env::vars_os().filter(|(name, _)| passes_to_the_tool(name)));
        if let Some(python) = &self.bridge_python {
            command.env("JAVABYTE_BRIDGE_PYTHON", python);
        }
        no_console(command);
    }

    /// Runs a short command that prints one JSON document, and reads it.
    fn json_command<T: serde::de::DeserializeOwned>(&self, args: &[&std::ffi::OsStr], accept: &[i32]) -> Result<T, String> {
        let output = self.text_command(args, accept)?;
        serde_json::from_str(&output).map_err(|e| javabyte_gui_protocol::t!("error-tool-output", detail = e.to_string()))
    }

    /// Runs a short command and returns what it printed.
    fn text_command(&self, args: &[&std::ffi::OsStr], accept: &[i32]) -> Result<String, String> {
        let output = self
            .command(args)
            .stdin(Stdio::null())
            .output()
            .map_err(|e| javabyte_gui_protocol::t!("error-java-start", path = self.java.display().to_string(), detail = e.to_string()))?;
        let code = output.status.code().unwrap_or(-1);
        if !accept.contains(&code) {
            return Err(javabyte_gui_protocol::t!("error-tool-failed", code = code, detail = last_lines(&String::from_utf8_lossy(&output.stderr), 5)));
        }
        String::from_utf8(output.stdout).map_err(|e| javabyte_gui_protocol::t!("error-tool-output", detail = e.to_string()))
    }

    /// A class file as readable instructions (`javabyte disasm`). Parses only.
    pub fn disasm(&self, class_file: &Path) -> Result<String, String> {
        self.text_command(&["disasm".as_ref(), class_file.as_os_str()], &[exit_code::OK])
            .map_err(|detail| javabyte_gui_protocol::t!("error-disasm", detail = detail))
    }

    /// A starter config for a sample: the tool's suggestions, with comments, ready to edit.
    pub fn init(&self, sample: &Path) -> Result<String, String> {
        self.text_command(&["init".as_ref(), sample.as_os_str()], &[exit_code::OK])
    }

    /// Describes a sample without running it.
    pub fn inspect(&self, sample: &Path) -> Result<Inspection, String> {
        self.json_command(&["inspect".as_ref(), "--json".as_ref(), sample.as_os_str()], &[exit_code::OK])
    }

    /// Every config option, for building forms.
    pub fn schema(&self) -> Result<Schema, String> {
        self.json_command(&["schema".as_ref(), "--json".as_ref()], &[exit_code::OK])
    }

    /// The built-in host policy, rule by rule (`javabyte policy --json`).
    pub fn policy(&self) -> Result<javabyte_gui_protocol::tools::PolicyListing, String> {
        self.json_command(&["policy".as_ref(), "--json".as_ref()], &[exit_code::OK])
    }

    /// Every problem with a config file and overrides. An invalid config is an answer, not an error.
    pub fn validate(&self, config: Option<&Path>, overrides: &[String]) -> Result<Validation, String> {
        let mut args: Vec<&std::ffi::OsStr> = vec!["validate".as_ref(), "--json".as_ref()];
        if let Some(config) = config {
            args.push("-c".as_ref());
            args.push(config.as_os_str());
        }
        for override_ in overrides {
            args.push("-s".as_ref());
            args.push(override_.as_ref());
        }
        self.json_command(&args, &[exit_code::OK, exit_code::BAD_SETTINGS])
    }

    /// Starts an analysis. It runs in the background; watch it through the returned [`Run`].
    pub fn start(&self, request: &RunRequest) -> Result<Run, String> {
        let mut args: Vec<&std::ffi::OsStr> = vec![
            "run".as_ref(),
            request.sample.as_os_str(),
            "-o".as_ref(),
            request.output.as_os_str(),
            "--events".as_ref(),
            "jsonl".as_ref(),
            "--progress".as_ref(),
            "--control".as_ref(),
            "stdin".as_ref(),
            // The stream already carries the results; the text summary would only fill stderr.
            "-s".as_ref(),
            "output.console=quiet".as_ref(),
        ];
        if let Some(config) = &request.config {
            args.push("-c".as_ref());
            args.push(config.as_os_str());
        }
        for override_ in &request.overrides {
            args.push("-s".as_ref());
            args.push(override_.as_ref());
        }
        let start_failed = |e: std::io::Error| javabyte_gui_protocol::t!("error-java-start", path = self.java.display().to_string(), detail = e.to_string());
        let command = self.run_command(request, &args).map_err(start_failed)?;
        Run::spawn(command).map_err(|e| javabyte_gui_protocol::t!("error-java-start", path = self.java.display().to_string(), detail = e.to_string()))
    }
}

impl Run {
    /// Starts `command` with its three standard streams piped, and the threads that read its
    /// output. Separate from [`Launcher::start`] so the ending can be tested with any program.
    fn spawn(mut command: Command) -> std::io::Result<Run> {
        let mut child = command.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn()?;

        // One thread per output pipe, so a full pipe can never stall the tool.
        let (sender, records) = channel();
        let stdout = child.stdout.take().expect("stdout was piped");
        thread::spawn(move || {
            for line in BufReader::new(stdout).lines() {
                let Ok(line) = line else { break };
                if line.trim().is_empty() {
                    continue;
                }
                if sender.send(stream::parse_line(&line)).is_err() {
                    break;
                }
            }
        });
        let stderr_bytes = Arc::new(Mutex::new(Vec::new()));
        let stderr = child.stderr.take().expect("stderr was piped");
        let collected = Arc::clone(&stderr_bytes);
        // Dropped by the thread when it reaches the end of stderr, which tells `ending` that
        // the text is complete.
        let (stderr_sender, stderr_done) = channel::<()>();
        thread::spawn(move || {
            collect_bytes(stderr, STDERR_LIMIT, &collected);
            drop(stderr_sender);
        });

        let stdin = child.stdin.take();
        Ok(Run { child, stdin, records, stderr: stderr_bytes, stderr_done, tally: Tally::default(), stream_ended: false, ended: None })
    }
}

/// Starts `command` without a console window on Windows, so none flashes up behind the app
/// for each run or check. Does nothing on other systems. Every process the backend starts goes
/// through here, so the flag is written once.
/// The environment variables the tool, Java and the sandbox add-on are started with: where
/// programs and the person's folders are, the language, the time zone, and Java's own options.
/// Names are compared without regard to case on Windows, as Windows does.
const PASSED_VARIABLES: &[&str] = if cfg!(windows) {
    &[
        "SystemRoot",
        "SystemDrive",
        "windir",
        "ComSpec",
        "Path", // not-ui-text: a variable's name
        "PATHEXT",
        "TEMP",
        "TMP",
        "USERPROFILE",
        "USERNAME",
        "USERDOMAIN",
        "COMPUTERNAME",
        "HOMEDRIVE",
        "HOMEPATH",
        "LOCALAPPDATA",
        "APPDATA",
        "ProgramData",
        "ALLUSERSPROFILE",
        "PUBLIC",
        "ProgramFiles",
        "ProgramFiles(x86)",
        "ProgramW6432",
        "CommonProgramFiles",
        "CommonProgramFiles(x86)",
        "CommonProgramW6432",
        "NUMBER_OF_PROCESSORS",
        "PROCESSOR_ARCHITECTURE",
        "PROCESSOR_IDENTIFIER",
        "PROCESSOR_LEVEL",
        "PROCESSOR_REVISION",
        "OS",
        "JAVA_TOOL_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "_JAVA_OPTIONS",
        "JAVABYTE_BRIDGE_PYTHON",
    ]
} else {
    &["PATH", "HOME", "USER", "LOGNAME", "LANG", "LANGUAGE", "TZ", "TMPDIR", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVABYTE_BRIDGE_PYTHON"]
};

/// Whether the tool is started with this variable of the app's environment
/// ([`PASSED_VARIABLES`], and the `LC_` language settings).
fn passes_to_the_tool(name: &std::ffi::OsStr) -> bool {
    let Some(name) = name.to_str() else { return false };
    if cfg!(windows) { PASSED_VARIABLES.iter().any(|p| p.eq_ignore_ascii_case(name)) } else { PASSED_VARIABLES.contains(&name) || name.starts_with("LC_") }
}

pub(crate) fn no_console(command: &mut Command) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        /// Windows' `CREATE_NO_WINDOW` process creation flag.
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    #[cfg(not(windows))]
    let _ = command;
}

/// The most of the tool's error output kept for a run: enough for any real error, and a limit
/// on what a run that floods stderr can make the app hold.
const STDERR_LIMIT: u64 = 1 << 20;

/// How long, once the process has gone, the app waits for the reader threads to deliver the
/// last of its output. They normally finish at once; the limit is for a process that left a
/// child of its own holding the pipes open.
const DRAIN_LIMIT: Duration = Duration::from_secs(2);

/// How long a run that is being ended is given to exit by itself, once it has been asked to
/// stop and its input has been closed, before its process is ended. The sandbox add-on uses
/// this time to remove its grants, delete its container and delete the staged sample.
const EXIT_GRACE: Duration = Duration::from_secs(2);

/// Reads `source` to its end, appending the first `limit` bytes to `into` as they arrive and
/// dropping the rest. Appending as it goes means that if the reader is still blocked when the
/// run is summarised, everything that came before is kept. Reading on past the limit means a
/// process that floods its error output never blocks on a full pipe.
fn collect_bytes(mut source: impl Read, limit: u64, into: &Mutex<Vec<u8>>) {
    let limit = usize::try_from(limit).unwrap_or(usize::MAX);
    let mut buffer = [0u8; 4096];
    loop {
        match source.read(&mut buffer) {
            Ok(0) => break,
            Ok(read) => {
                if let Ok(mut into) = into.lock() {
                    let room = limit.saturating_sub(into.len());
                    into.extend_from_slice(&buffer[..read.min(room)]);
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
            Err(_) => break,
        }
    }
}

/// A run in progress. Dropping it ends the process.
pub struct Run {
    child: Child,
    stdin: Option<ChildStdin>,
    records: Receiver<Result<StreamRecord, String>>,
    /// The tool's error output so far, filled by its reader thread.
    stderr: Arc<Mutex<Vec<u8>>>,
    /// Disconnects when the stderr reader thread has read to the end, so the text is complete.
    stderr_done: Receiver<()>,
    tally: Tally,
    stream_ended: bool,
    ended: Option<Ended>,
}

/// What is known about a run so far, kept up to date as records arrive.
#[derive(Clone, Debug, Default)]
pub struct Tally {
    /// The stream's start record, once it has arrived.
    pub start: Option<Start>,
    /// Events received so far.
    pub events: u64,
    /// Events per category, counted from the event records.
    pub by_category: BTreeMap<String, u64>,
    /// The latest progress record.
    pub progress: Option<Progress>,
    /// How each part has ended so far.
    pub entries: Vec<RunResult>,
    /// The stream's finish record, once it has arrived.
    pub finish: Option<Finish>,
    /// Lines that could not be read, with the reason. Should stay empty.
    pub unreadable: Vec<String>,
}

impl Tally {
    /// Updates the counts from one stream record.
    fn absorb(&mut self, record: &StreamRecord) {
        match record {
            StreamRecord::Start(start) => self.start = Some(start.clone()),
            StreamRecord::Event(event) => {
                self.events += 1;
                *self.by_category.entry(event.category.clone()).or_default() += 1;
            }
            StreamRecord::Progress(progress) => self.progress = Some(progress.clone()),
            StreamRecord::Entry(entry) => self.entries.push(entry.clone()),
            StreamRecord::Finish(finish) => self.finish = Some((**finish).clone()),
            StreamRecord::Unknown => {}
        }
    }
}

/// How a run ended, for people.
#[derive(Clone, Debug)]
pub struct Ended {
    /// None if the process was ended because it did not stop when asked.
    pub exit_code: Option<i32>,
    /// True if the process did not stop when asked and was ended.
    pub forced: bool,
    /// Everything the stream said.
    pub tally: Tally,
    /// The tool's error output, for the "copy details" button when something went wrong.
    pub stderr: String,
    /// A plain-language account of the ending.
    pub summary: String,
}

impl Run {
    /// Takes every record that has arrived, without waiting.
    pub fn poll(&mut self) -> Vec<StreamRecord> {
        let mut taken = Vec::new();
        loop {
            match self.records.try_recv() {
                Ok(record) => self.accept(record, &mut taken),
                Err(std::sync::mpsc::TryRecvError::Empty) => break,
                Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                    self.stream_ended = true;
                    break;
                }
            }
        }
        taken
    }

    /// Waits up to `timeout` for the next record. None if none came or the stream has ended.
    pub fn next(&mut self, timeout: Duration) -> Option<StreamRecord> {
        let mut taken = Vec::new();
        match self.records.recv_timeout(timeout) {
            Ok(record) => self.accept(record, &mut taken),
            Err(RecvTimeoutError::Disconnected) => self.stream_ended = true,
            Err(RecvTimeoutError::Timeout) => {}
        }
        taken.pop()
    }

    /// Takes one line's record (or why it could not be read) into the tally.
    fn accept(&mut self, record: Result<StreamRecord, String>, taken: &mut Vec<StreamRecord>) {
        match record {
            Ok(record) => {
                self.tally.absorb(&record);
                taken.push(record);
            }
            Err(reason) => self.tally.unreadable.push(reason),
        }
    }

    /// What is known about the run so far.
    pub fn tally(&self) -> &Tally {
        &self.tally
    }

    /// Asks the run to stop. It ends at its next checkpoint, normally well within a second, and
    /// still writes its report. Asking twice does no harm.
    pub fn stop(&mut self) -> Result<(), String> {
        match self.stdin.as_mut() {
            Some(stdin) => stdin
                .write_all(b"stop\n")
                .and_then(|_| stdin.flush())
                .map_err(|e| javabyte_gui_protocol::t!("error-stop-not-sent", detail = e.to_string())),
            None => Ok(()),
        }
    }

    /// Checks, without waiting, whether the run has ended. Takes any records that have arrived.
    /// Returns the ending once the process has exited and its last records are in; after that
    /// the run is over and further calls return the same answer.
    pub fn try_end(&mut self) -> Option<Ended> {
        if let Some(ended) = &self.ended {
            return Some(ended.clone());
        }
        self.poll();
        let Ok(Some(status)) = self.child.try_wait() else { return None };
        self.drain_remaining();
        let ended = self.ending(status.code(), false);
        self.ended = Some(ended.clone());
        Some(ended)
    }

    /// After the process has gone, takes the last records its reader thread is still
    /// delivering, for up to [`DRAIN_LIMIT`].
    fn drain_remaining(&mut self) {
        let deadline = Instant::now() + DRAIN_LIMIT;
        while !self.stream_ended && Instant::now() < deadline {
            let _ = self.next(Duration::from_millis(20));
        }
    }

    /// How the run ended, for people. Called once the process has gone: first waits, for up to
    /// [`DRAIN_LIMIT`], for the stderr reader to reach the end, so the error output (such as
    /// the sandbox add-on's reason for an isolation failure) is complete. The wait is bounded
    /// because a process the tool started may still hold stderr open.
    fn ending(&mut self, exit_code: Option<i32>, forced: bool) -> Ended {
        let _ = self.stderr_done.recv_timeout(DRAIN_LIMIT);
        let stderr = self.stderr.lock().map(|bytes| String::from_utf8_lossy(&bytes).into_owned()).unwrap_or_default();
        let summary = summarise(exit_code, forced, &self.tally);
        Ended { exit_code, forced, tally: self.tally.clone(), stderr, summary }
    }

    /// Ends the process, for when it ignored a stop request. Takes the records it wrote
    /// before it was ended, as `try_end` does. The process is first given a last chance to
    /// exit by itself ([`Run::end_process`]).
    pub fn force_end(&mut self) -> Ended {
        self.end_process();
        self.drain_remaining();
        let ended = self.ending(None, true);
        self.ended = Some(ended.clone());
        ended
    }

    /// Waits for the run to end by itself, taking every record on the way. After `limit`, asks
    /// it to stop; if it still has not ended `grace` later, ends the process.
    pub fn wait(mut self, limit: Duration, grace: Duration) -> Ended {
        let started = Instant::now();
        let mut asked = false;
        let mut forced = false;
        let status: Option<ExitStatus> = loop {
            self.poll();
            if let Ok(Some(status)) = self.child.try_wait() {
                break Some(status);
            }
            let elapsed = started.elapsed();
            if !asked && elapsed >= limit {
                let _ = self.stop();
                asked = true;
            }
            if asked && elapsed >= limit + grace {
                let _ = self.child.kill();
                forced = true;
                break self.child.wait().ok();
            }
            let _ = self.next(Duration::from_millis(50));
        };
        self.drain_remaining();
        let exit_code = if forced { None } else { status.and_then(|s| s.code()) };
        self.ending(exit_code, forced)
    }
}

impl Run {
    /// Ends the process if it is still running. It is asked to stop and its input is closed,
    /// then given [`EXIT_GRACE`] to exit by itself, and only then ended. The grace lets the
    /// sandbox add-on undo what it set up (its grants, its container, the staged sample)
    /// rather than leave that to its next start.
    fn end_process(&mut self) {
        if !matches!(self.child.try_wait(), Ok(None)) {
            return;
        }
        let _ = self.stop();
        // The end of input also asks the tool and the add-on to stop.
        self.stdin = None;
        let deadline = Instant::now() + EXIT_GRACE;
        while Instant::now() < deadline {
            if !matches!(self.child.try_wait(), Ok(None)) {
                return;
            }
            thread::sleep(Duration::from_millis(20));
        }
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

impl Drop for Run {
    /// Ends the process, gracefully if it allows (see [`Run::end_process`]): a run must never
    /// outlive the window that started it.
    fn drop(&mut self) {
        self.end_process();
    }
}

/// One or two sentences about how a run ended, for the screen and for screen readers.
pub fn summarise(exit_code: Option<i32>, forced: bool, tally: &Tally) -> String {
    if forced {
        return javabyte_gui_protocol::t!("run-forced");
    }
    let outcome = tally.entries.first().map(|entry| describe_outcome(&entry.outcome));
    match exit_code {
        Some(exit_code::OK) | Some(exit_code::LIMIT_REACHED) => {
            let events = tally.finish.as_ref().map_or(tally.events, |f| f.summary.event_counts.values().sum());
            javabyte_gui_protocol::t!("run-ended-events", outcome = outcome.unwrap_or_else(|| javabyte_gui_protocol::t!("run-finished")), count = events)
        }
        Some(exit_code::BAD_SETTINGS) => javabyte_gui_protocol::t!("run-bad-settings"),
        Some(exit_code::TOOL_FAULT) => javabyte_gui_protocol::t!("run-tool-fault"),
        Some(other) => javabyte_gui_protocol::t!("run-ended-code", code = other),
        None => javabyte_gui_protocol::t!("run-ended"),
    }
}

/// The last lines of the tool's error output, without Java's own notices.
fn last_lines(text: &str, count: usize) -> String {
    let java_notice = "Picked up JAVA_TOOL_OPTIONS"; // not-ui-text: matches Java's own output
    let lines: Vec<_> = text.lines().filter(|l| !l.starts_with(java_notice)).collect();
    lines[lines.len().saturating_sub(count)..].join(" / ")
}

#[cfg(all(test, unix))]
mod ending_tests {
    use super::*;

    /// Waits, as the Running screen does, until `try_end` says the run is over.
    fn end_by_polling(run: &mut Run) -> Ended {
        let started = Instant::now();
        loop {
            if let Some(ended) = run.try_end() {
                return ended;
            }
            assert!(started.elapsed() < Duration::from_secs(20), "the run did not end");
            thread::sleep(Duration::from_millis(5));
        }
    }

    fn shell(script: &str) -> Run {
        let mut command = Command::new("/bin/sh");
        command.args(["-c", script]);
        Run::spawn(command).unwrap()
    }

    /// A process that writes its reason and exits at once, as the sandbox add-on does when
    /// isolation fails: the reason must be in the ending, every time.
    #[test]
    fn the_ending_has_all_of_stderr_even_after_a_fast_exit() {
        for _ in 0..20 {
            let ended = end_by_polling(&mut shell("echo 'javabyte-sandbox: AppContainer refused' >&2; exit 70"));
            assert_eq!(ended.exit_code, Some(70));
            assert_eq!(ended.stderr.trim(), "javabyte-sandbox: AppContainer refused");
        }
    }

    /// A process that leaves a child of its own holding stderr open: the ending still comes,
    /// within the drain limit, and keeps what was written before.
    #[test]
    fn a_child_holding_stderr_open_does_not_hold_up_the_ending() {
        let started = Instant::now();
        let ended = end_by_polling(&mut shell("echo before >&2; (sleep 6 >/dev/null &); exit 3"));
        assert!(started.elapsed() < Duration::from_secs(5), "took {:?}", started.elapsed());
        assert_eq!(ended.exit_code, Some(3));
        assert_eq!(ended.stderr.trim(), "before");
    }

    /// `force_end` keeps the error output written before the process was ended.
    #[test]
    fn a_forced_ending_keeps_what_came_before() {
        let mut run = shell("echo stuck >&2; exec sleep 30");
        thread::sleep(Duration::from_millis(300));
        let ended = run.force_end();
        assert!(ended.forced);
        assert_eq!(ended.stderr.trim(), "stuck");
        assert_eq!(run.try_end().map(|e| e.forced), Some(true), "the ending is kept");
    }

    /// `collect_bytes` keeps no more than its limit, and reads on to the end so the writer
    /// never blocks.
    #[test]
    fn stderr_is_kept_up_to_its_limit() {
        let into = Mutex::new(Vec::new());
        let mut source = std::io::Cursor::new(vec![b'x'; 10_000]);
        collect_bytes(&mut source, 5000, &into);
        assert_eq!(into.lock().unwrap().len(), 5000);
        assert_eq!(source.position(), 10_000, "the excess is read and dropped");
    }

    /// A process that writes more than the limit to stderr still ends: the reader keeps the
    /// pipe empty.
    #[test]
    fn a_flood_of_stderr_does_not_block_the_process() {
        let ended = end_by_polling(&mut shell("head -c 3000000 /dev/zero | tr '\\0' x >&2; exit 4"));
        assert_eq!(ended.exit_code, Some(4));
        assert_eq!(ended.stderr.len() as u64, STDERR_LIMIT);
    }

    /// A process that stops when its input ends is given the chance to: `force_end` and
    /// dropping the run close its input and wait before ending it.
    #[test]
    fn ending_a_run_lets_it_exit_by_itself_first() {
        let mut run = shell("cat >/dev/null; echo tidied >&2; exit 5");
        thread::sleep(Duration::from_millis(200));
        let ended = run.force_end();
        assert!(ended.forced);
        assert_eq!(ended.stderr.trim(), "tidied");

        let marker = std::env::temp_dir().join(format!("javabyte-drop-{}", std::process::id()));
        let _ = std::fs::remove_file(&marker);
        let run = shell(&format!("cat >/dev/null; touch '{}'", marker.display()));
        thread::sleep(Duration::from_millis(200));
        drop(run);
        assert!(marker.exists(), "the process tidied up before it was ended");
        let _ = std::fs::remove_file(&marker);
    }

    /// A process that ignores both stop and the end of its input is still ended, soon after
    /// the grace.
    #[test]
    fn a_run_that_ignores_stop_is_ended_after_the_grace() {
        let mut run = shell("exec sleep 30 </dev/null");
        let started = Instant::now();
        let ended = run.force_end();
        assert!(ended.forced);
        assert!(started.elapsed() < EXIT_GRACE + DRAIN_LIMIT + Duration::from_secs(2), "took {:?}", started.elapsed());
    }
}

#[cfg(test)]
mod isolation_tests {
    use super::*;

    fn launcher(isolation: RunIsolation) -> Launcher {
        let mut launcher = Launcher::new("/opt/jb/runtime/bin/java", "/opt/jb/javabyte.jar");
        launcher.bridge_python = Some(PathBuf::from("/opt/jb/bridge/python/python.exe"));
        launcher.isolation = isolation;
        launcher
    }

    fn args(command: &Command) -> Vec<String> {
        command.get_args().map(|a| a.to_string_lossy().into_owned()).collect()
    }

    fn request() -> RunRequest {
        RunRequest { sample: PathBuf::from("/samples/x.jar"), output: PathBuf::from("/runs/one"), config: None, overrides: vec![] }
    }

    #[test]
    fn an_appcontainer_run_names_what_it_may_read_and_write() {
        let command = launcher(RunIsolation::AppContainer("/opt/jb/sandbox/javabyte-sandbox.exe".into())).run_command(&request(), &["run".as_ref()]).unwrap();
        assert_eq!(command.get_program(), "/opt/jb/sandbox/javabyte-sandbox.exe");
        let args = args(&command);
        let reads: Vec<&str> = args.windows(2).filter(|w| w[0] == "--read").map(|w| w[1].as_str()).collect();
        assert_eq!(reads, ["/opt/jb/runtime", "/opt/jb/javabyte.jar", "/opt/jb/bridge/python", "/samples/x.jar"]);
        assert!(args.windows(2).any(|w| w == ["--write", "/runs/one"]));
        let program = &args[args.iter().position(|a| a == "--").unwrap() + 1..];
        assert_eq!(program, ["/opt/jb/runtime/bin/java", "-jar", "/opt/jb/javabyte.jar", "run"]);
    }

    #[test]
    fn a_windows_sandbox_run_maps_folders_and_stages_the_sample() {
        let sandbox = launcher(RunIsolation::WindowsSandbox("/opt/jb/sandbox/javabyte-sandbox.exe".into()));
        let args = args(&sandbox.run_command(&request(), &["run".as_ref()]).unwrap());
        assert_eq!(args[0], "windows-sandbox");
        let maps: Vec<&str> = args.windows(2).filter(|w| w[0] == "--map").map(|w| w[1].as_str()).collect();
        assert_eq!(maps, [r"/opt/jb/runtime=C:\JavaByteRuntime", r"/opt/jb=C:\JavaByteTool", r"/opt/jb/bridge/python=C:\JavaByteBridge", r"/runs/one=C:\JavaByteRun:rw"]);
        assert!(args.windows(2).any(|w| w == ["--stage", "/samples/x.jar"]), "a sample file is copied in, not mapped");
        assert!(sandbox.uses_windows_sandbox());
        let lighter = sandbox.without_windows_sandbox();
        assert_eq!(lighter.isolation, RunIsolation::AppContainer("/opt/jb/sandbox/javabyte-sandbox.exe".into()));
    }

    #[test]
    fn without_isolation_java_starts_directly() {
        let command = launcher(RunIsolation::None).run_command(&request(), &["run".as_ref()]).unwrap();
        assert_eq!(command.get_program(), "/opt/jb/runtime/bin/java");
    }

    /// The tool sees where programs and the home folder are, the language and Java's options,
    /// and nothing else of the app's environment: a token there is not passed on.
    #[test]
    fn the_tool_gets_a_short_list_of_environment_variables() {
        let secret = "JAVABYTE_TEST_ACCESS_TOKEN";
        // SAFETY: no other test reads or writes this variable.
        unsafe { std::env::set_var(secret, "s3cret") };
        for isolation in [RunIsolation::None, RunIsolation::AppContainer("/opt/jb/sandbox/javabyte-sandbox.exe".into())] {
            let command = launcher(isolation).run_command(&request(), &["run".as_ref()]).unwrap();
            let passed: Vec<String> = command.get_envs().filter(|(_, value)| value.is_some()).map(|(name, _)| name.to_string_lossy().into_owned()).collect();
            assert!(!passed.iter().any(|name| name == secret), "{passed:?}");
            assert!(passed.iter().all(|name| passes_to_the_tool(name.as_ref())), "{passed:?}");
            assert!(passed.iter().any(|name| name == "JAVABYTE_BRIDGE_PYTHON"));
            if std::env::var_os("PATH").is_some() {
                assert!(passed.iter().any(|name| name.eq_ignore_ascii_case("PATH")), "{passed:?}");
            }
        }
        for name in ["AWS_SECRET_ACCESS_KEY", "GITHUB_TOKEN", "SSH_AUTH_SOCK", "LD_PRELOAD", "PYTHONPATH", "DBUS_SESSION_BUS_ADDRESS"] {
            assert!(!passes_to_the_tool(name.as_ref()), "{name}");
        }
        assert!(passes_to_the_tool(if cfg!(windows) { "systemroot" } else { "LC_ALL" }.as_ref()));
    }

    /// An isolated run on Linux names its folder as the one place to write, and tells Java to
    /// keep its temporary files in the run's private folder. What cannot be prepared is an
    /// error, so the run does not start.
    #[cfg(target_os = "linux")]
    #[test]
    fn a_namespace_run_uses_the_private_temporary_folder_or_does_not_start() {
        let folder = std::env::temp_dir().join(format!("javabyte-namespace-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&folder);
        let mut request = request();
        request.output = folder.join("out");
        let command = launcher(RunIsolation::Namespace).run_command(&request, &["run".as_ref()]).unwrap();
        assert_eq!(args(&command), ["-Djava.io.tmpdir=/tmp", "-jar", "/opt/jb/javabyte.jar", "run"]);
        let temp = command.get_envs().find(|(name, _)| *name == "TMPDIR").and_then(|(_, value)| value);
        assert_eq!(temp, Some("/tmp".as_ref()));
        assert!(request.output.is_dir(), "the run's folder is made before the run, to be mounted");

        std::fs::write(folder.join("file"), "").unwrap();
        request.output = folder.join("file/out");
        assert!(launcher(RunIsolation::Namespace).run_command(&request, &["run".as_ref()]).is_err());
        let _ = std::fs::remove_dir_all(&folder);
    }
}

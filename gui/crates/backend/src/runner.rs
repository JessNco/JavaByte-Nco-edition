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

/// How an analysis is kept from the network (docs/gui/PHASE5-PLAN.md, WINDOWS-SANDBOX.md).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub enum RunIsolation {
    /// Not isolated: the tool's own simulation of the network is the only layer.
    #[default]
    None,
    /// Linux: a new user and network namespace (`crate::isolation`).
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
    fn run_command(&self, request: &RunRequest, args: &[&std::ffi::OsStr]) -> Command {
        let (helper, sandbox) = match &self.isolation {
            RunIsolation::AppContainer(helper) => (helper, false),
            RunIsolation::WindowsSandbox(helper) => (helper, true),
            #[cfg_attr(not(target_os = "linux"), allow(unused_variables))]
            other => {
                #[cfg_attr(not(target_os = "linux"), allow(unused_mut))]
                let mut command = self.command(args);
                #[cfg(target_os = "linux")]
                if *other == RunIsolation::Namespace {
                    crate::isolation::isolate(&mut command);
                }
                return command;
            }
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
        command
    }

    /// The environment and window settings every command shares.
    fn prepare(&self, command: &mut Command) {
        if let Some(python) = &self.bridge_python {
            command.env("JAVABYTE_BRIDGE_PYTHON", python);
        }
        #[cfg(windows)]
        {
            // No console window flashing up behind the app for each run.
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            command.creation_flags(CREATE_NO_WINDOW);
        }
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
        let mut command = self.run_command(request, &args);
        let mut child = command
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|e| javabyte_gui_protocol::t!("error-java-start", path = self.java.display().to_string(), detail = e.to_string()))?;

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
        let stderr_text = Arc::new(Mutex::new(String::new()));
        let stderr = child.stderr.take().expect("stderr was piped");
        let collected = Arc::clone(&stderr_text);
        thread::spawn(move || {
            let mut text = String::new();
            let _ = BufReader::new(stderr).take(1 << 20).read_to_string(&mut text);
            *collected.lock().expect("stderr lock") = text;
        });

        let stdin = child.stdin.take();
        Ok(Run { child, stdin, records, stderr: stderr_text, tally: Tally::default(), stream_ended: false, ended: None })
    }
}

/// A run in progress. Dropping it ends the process.
pub struct Run {
    child: Child,
    stdin: Option<ChildStdin>,
    records: Receiver<Result<StreamRecord, String>>,
    stderr: Arc<Mutex<String>>,
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
        // The process has gone; the reader thread may still be delivering its last lines.
        let deadline = Instant::now() + Duration::from_secs(2);
        while !self.stream_ended && Instant::now() < deadline {
            let _ = self.next(Duration::from_millis(20));
        }
        let ended = self.ending(status.code(), false);
        self.ended = Some(ended.clone());
        Some(ended)
    }

    /// How the run ended, for people.
    fn ending(&mut self, exit_code: Option<i32>, forced: bool) -> Ended {
        let stderr = self.stderr.lock().map(|s| s.clone()).unwrap_or_default();
        let summary = summarise(exit_code, forced, &self.tally);
        Ended { exit_code, forced, tally: self.tally.clone(), stderr, summary }
    }

    /// Ends the process now, for when it ignored a stop request.
    pub fn force_end(&mut self) -> Ended {
        let _ = self.child.kill();
        let _ = self.child.wait();
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
        // The reader thread may still be delivering the last lines.
        let deadline = Instant::now() + Duration::from_secs(2);
        while !self.stream_ended && Instant::now() < deadline {
            let _ = self.next(Duration::from_millis(50));
        }
        let exit_code = if forced { None } else { status.and_then(|s| s.code()) };
        thread::sleep(Duration::from_millis(20));
        let stderr = self.stderr.lock().map(|s| s.clone()).unwrap_or_default();
        let summary = summarise(exit_code, forced, &self.tally);
        Ended { exit_code, forced, tally: std::mem::take(&mut self.tally), stderr, summary }
    }
}

impl Drop for Run {
    fn drop(&mut self) {
        // A run must never outlive the window that started it.
        if let Ok(None) = self.child.try_wait() {
            let _ = self.child.kill();
            let _ = self.child.wait();
        }
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
        let command = launcher(RunIsolation::AppContainer("/opt/jb/sandbox/javabyte-sandbox.exe".into())).run_command(&request(), &["run".as_ref()]);
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
        let args = args(&sandbox.run_command(&request(), &["run".as_ref()]));
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
        let command = launcher(RunIsolation::None).run_command(&request(), &["run".as_ref()]);
        assert_eq!(command.get_program(), "/opt/jb/runtime/bin/java");
    }
}

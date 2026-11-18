//! The app's state between screens: the chosen sample, the run in progress, the open report and
//! the list of recent runs. No window code, so it is tested on its own against the real tool.
//!
//! One analysis runs at a time (a decision recorded in docs/GUI-PLAN.md). Every run writes to a
//! folder of its own under the runs folder, named by time and sample, so results are never
//! overwritten and people can find them in their file manager.
//!
//! This file holds the analysis itself: choosing and inspecting a sample, starting, watching and
//! stopping a run, and the recent list. [`settings`] holds the chosen sample's settings and
//! presets; [`reports`] holds opening, comparing and the files a run saved.

use crate::config::{self, ConfigDoc, ConfigPath};
use crate::events::EventStore;
use crate::runner::{Ended, Launcher, Run, RunRequest};
use javabyte_gui_protocol::report::describe_outcome;
use javabyte_gui_protocol::stream::StreamRecord;
use javabyte_gui_protocol::schema::Schema;
use javabyte_gui_protocol::t;
use javabyte_gui_protocol::views::{self, EngineStatus, Overview, RecentRun, ReportView, SampleChoice};
use javabyte_gui_protocol::{EventRow, Page, PageRequest, RunState, RunStatus};
use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime};

mod reports;
mod settings;

pub use settings::{Checker, with_sample, without_host_programs};
use reports::open_report;

/// Categories whose events are shown live while a run goes on.
const NOTABLE: &[&str] = &["process", "net", "class", "native"];
/// Fixed order for the live per-category table, so rows do not jump around.
const CATEGORY_ORDER: &[&str] = &["process", "net", "file", "class", "native", "crypto", "string", "reflect", "thread", "console", "policy", "gap"];
const RECENT_LIMIT: usize = 20;

/// How long a run gets to honour a stop request before its process is ended.
const STOP_GRACE: Duration = Duration::from_secs(15);

/// One window's state: the engine, the chosen sample and its settings, the analysis
/// running or last run, the open report, and the recent list (kept in a file).
pub struct Session {
    launcher: Result<Launcher, String>,
    runs_root: PathBuf,
    recent_file: PathBuf,
    active: Option<Active>,
    report: Option<OpenReport>,
    /// The chosen sample and the settings for its next run.
    settings: Option<(SampleChoice, ConfigDoc)>,
    schema: Option<Schema>,
}


struct Active {
    run: Run,
    /// Started through Windows Sandbox, which takes a while before the analysis begins.
    sandboxed: bool,
    sample: SampleChoice,
    output: PathBuf,
    started: Instant,
    stop_requested: Option<Instant>,
    notable: VecDeque<EventRow>,
    ended: Option<Ended>,
}

struct OpenReport {
    view: ReportView,
    events: EventStore,
    #[cfg_attr(target_arch = "wasm32", allow(dead_code))]
    folder: PathBuf,
    /// The report's `artifacts` list: the only files the app will read or copy.
    #[cfg_attr(target_arch = "wasm32", allow(dead_code))]
    artifacts: Vec<String>,
    /// Where the report says the sample was. None when that is not a path on this computer
    /// (see [`is_local_path`]): the report may come from someone else, and its path is then
    /// never looked at.
    sample_path: Option<PathBuf>,
}

impl Session {
    /// `launcher` is the engine, or why it cannot be used; `runs_root` holds one folder per run.
    pub fn new(launcher: Result<Launcher, String>, runs_root: PathBuf, recent_file: PathBuf) -> Self {
        Self { launcher, runs_root, recent_file, active: None, report: None, settings: None, schema: None }
    }

    /// Uses another engine and output folder, after the preferences changed. A run in
    /// progress keeps the engine it started with.
    pub fn reconfigure(&mut self, launcher: Result<Launcher, String>, runs_root: PathBuf) {
        self.launcher = launcher;
        self.runs_root = runs_root;
    }

    /// Whether analyses can run, and if not, why.
    pub fn engine(&self) -> EngineStatus {
        match &self.launcher {
            Ok(_) => EngineStatus { ready: true, problem: None },
            Err(problem) => EngineStatus { ready: false, problem: Some(problem.clone()) },
        }
    }

    /// The engine, or why it cannot be used.
    fn launcher(&self) -> Result<&Launcher, String> {
        self.launcher.as_ref().map_err(Clone::clone)
    }

    /// Checks a path someone picked or dropped. A path on another computer is refused before
    /// anything looks at it (see [`is_local_path`]).
    pub fn choose(path: &Path) -> Result<SampleChoice, String> {
        if !is_local_path(path) || !path.exists() {
            return Err(javabyte_gui_protocol::t!("error-not-found", path = path.display().to_string()));
        }
        let name = path.file_name().map_or_else(|| path.display().to_string(), |n| n.to_string_lossy().into_owned());
        Ok(SampleChoice { path: path.display().to_string(), name })
    }

    /// Describes a sample without running it, and starts its settings afresh from the tool's
    /// suggestions for it (`javabyte init`). Nothing carries over from the previous sample.
    pub fn inspect(&mut self, sample: SampleChoice) -> Result<Overview, String> {
        let launcher = self.launcher()?;
        let inspection = launcher.inspect(Path::new(&sample.path))?;
        let mut doc = ConfigDoc::new(launcher.init(Path::new(&sample.path))?);
        // The app gives every run a folder of its own, so a directory here would mislead.
        let _ = doc.remove(&ConfigPath::parse("output.directory"));
        self.settings = Some((sample.clone(), doc));
        Ok(views::overview(sample, inspection))
    }

    /// Starts an analysis of the chosen sample with its settings. Refused while another runs.
    pub fn start(&mut self) -> Result<(), String> {
        self.start_with(false)
    }

    /// Starts the analysis again in an AppContainer, after Windows Sandbox could not start and
    /// the person agreed to the lighter isolation for this run.
    pub fn start_without_sandbox(&mut self) -> Result<(), String> {
        self.start_with(true)
    }

    fn start_with(&mut self, without_sandbox: bool) -> Result<(), String> {
        if self.active.as_ref().is_some_and(|a| a.ended.is_none()) {
            return Err(t!("error-already-running"));
        }
        let (sample, doc) = self.settings.clone().ok_or_else(|| t!("error-no-sample"))?;
        let output = self.runs_root.join(format!("{} {}", timestamp(SystemTime::now(), true), safe_name(&sample.name)));
        std::fs::create_dir_all(&output).map_err(|e| t!("error-results-folder", detail = e.to_string()))?;
        // Saved beside the report, so the run can be repeated with `javabyte run -c`.
        let config_file = output.join("config.yaml");
        std::fs::write(&config_file, doc.text()).map_err(|e| t!("error-results-folder", detail = e.to_string()))?;
        let request = RunRequest {
            sample: PathBuf::from(&sample.path),
            output: output.clone(),
            config: Some(config_file),
            // The chosen sample, whatever the settings say: they may come from another sample.
            overrides: vec![sample_override(&sample)],
        };
        let launcher = if without_sandbox { self.launcher()?.without_windows_sandbox() } else { self.launcher()?.clone() };
        let run = launcher.start(&request)?;
        self.active = Some(Active {
            run,
            sandboxed: launcher.uses_windows_sandbox(),
            sample,
            output,
            started: Instant::now(),
            stop_requested: None,
            notable: VecDeque::new(),
            ended: None,
        });
        Ok(())
    }

    /// The app's own data folder (where the recent list and presets live).
    fn data_dir(&self) -> PathBuf {
        self.recent_file.parent().map_or_else(|| self.runs_root.clone(), Path::to_path_buf)
    }

    /// Asks the running analysis to stop; it keeps what it found.
    pub fn stop(&mut self) -> Result<(), String> {
        match self.active.as_mut() {
            Some(active) if active.ended.is_none() => {
                active.stop_requested.get_or_insert_with(Instant::now);
                active.run.stop()
            }
            _ => Ok(()),
        }
    }

    /// The running analysis as the Running screen shows it. Called a few times a second.
    pub fn status(&mut self) -> RunStatus {
        let Some(active) = self.active.as_mut() else { return RunStatus::default() };
        if active.ended.is_none() {
            for record in active.run.poll() {
                if let StreamRecord::Event(event) = record
                    && (NOTABLE.contains(&event.category.as_str()) || (event.category == "file" && event.action == "write")) {
                        active.notable.push_front(EventRow {
                            seq: event.seq,
                            details: event.details(),
                            category: event.category,
                            action: event.action,
                            caller: event.caller,
                        });
                        active.notable.truncate(5);
                    }
            }
            if let Some(ended) = active.run.try_end() {
                active.ended = Some(ended);
            } else if active.stop_requested.is_some_and(|asked| asked.elapsed() > STOP_GRACE) {
                active.ended = Some(active.run.force_end());
            }
            if active.ended.is_some() {
                self.finish();
            }
        }
        let active = self.active.as_ref().expect("still present");
        let tally = active.ended.as_ref().map_or_else(|| active.run.tally().clone(), |e| e.tally.clone());
        let progress = tally.progress.clone();
        let by_category = CATEGORY_ORDER
            .iter()
            .map(|c| (c.to_string(), tally.by_category.get(*c).copied().unwrap_or(0)))
            .chain(tally.by_category.iter().filter(|(c, _)| !CATEGORY_ORDER.contains(&c.as_str())).map(|(c, n)| (c.clone(), *n)))
            .collect();
        RunStatus {
            state: if active.ended.is_some() { RunState::Finished } else { RunState::Running },
            elapsed_ms: progress.as_ref().map_or_else(|| active.started.elapsed().as_millis() as u64, |p| p.elapsed_ms),
            instructions: progress.as_ref().map_or(0, |p| p.instructions),
            events: tally.events,
            by_category,
            notable: active.notable.iter().cloned().collect(),
            outcome: tally.entries.first().map(|e| e.outcome.clone()),
            sample_name: active.sample.name.clone(),
            entry: progress.map(|p| p.entry).unwrap_or_default(),
            ended: active.ended.as_ref().map(|e| e.summary.clone()),
            report_ready: self.report.is_some() && active.ended.is_some(),
            starting: (active.ended.is_none() && active.sandboxed && tally.start.is_none()).then(|| t!("running-starting-sandbox")),
            // The add-on's exit code when the isolation itself failed, before the tool began.
            isolation_failed: active.ended.as_ref().filter(|e| e.exit_code == Some(views::SANDBOX_ISOLATION_FAILED) && e.tally.start.is_none()).map(|e| {
                t!("running-isolation-failed", detail = e.stderr.lines().rev().find(|l| !l.trim().is_empty()).unwrap_or_default().trim_start_matches("javabyte-sandbox: "))
            }),
        }
    }

    /// After a run ends: open its report and remember it in the recent list.
    fn finish(&mut self) {
        let Some(active) = self.active.as_ref() else { return };
        let ended = active.ended.as_ref().expect("finish after the end");
        if let Ok(opened) = open_report(&active.output, &active.sample.name) {
            let recent = RecentRun {
                id: active.output.file_name().map_or_else(String::new, |n| n.to_string_lossy().into_owned()),
                sample_name: active.sample.name.clone(),
                when: timestamp(SystemTime::now(), false),
                outcome: ended.tally.entries.first().map(|e| describe_outcome(&e.outcome)).unwrap_or_default(),
                events: opened.view.event_count,
                output_dir: active.output.display().to_string(),
            };
            self.report = Some(opened);
            self.remember(recent);
        }
    }

    /// Puts a run first in the recent list.
    fn remember(&self, recent: RecentRun) {
        let mut list = self.recent();
        list.retain(|r| r.id != recent.id);
        list.insert(0, recent);
        list.truncate(RECENT_LIMIT);
        if let Ok(json) = serde_json::to_string_pretty(&list) {
            if let Some(parent) = self.recent_file.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            let _ = std::fs::write(&self.recent_file, json);
        }
    }

    /// Recent runs, newest first. Runs whose folders were deleted are left out.
    pub fn recent(&self) -> Vec<RecentRun> {
        std::fs::read_to_string(&self.recent_file)
            .ok()
            .and_then(|json| serde_json::from_str::<Vec<RecentRun>>(&json).ok())
            .unwrap_or_default()
            .into_iter()
            .filter(|r| Path::new(&r.output_dir).join("report.json").is_file())
            .collect()
    }

    /// The actions in one category of the open report's events.
    pub fn event_actions(&mut self, category: &str) -> Vec<String> {
        self.report.as_mut().map(|r| r.events.actions(category)).unwrap_or_default()
    }

    /// Where an event number is in the filtered list of the open report's events.
    pub fn event_position(&mut self, seq: u64, filter: &javabyte_gui_protocol::EventFilter) -> Option<u64> {
        self.report.as_mut().and_then(|r| r.events.position_of(seq, filter))
    }

    /// A page of the open report's events; empty if none is open.
    pub fn events_page(&mut self, request: &PageRequest) -> Page {
        match self.report.as_mut() {
            Some(report) => report.events.page(request),
            None => Page { total: 0, unfiltered_total: 0, offset: 0, rows: Vec::new() },
        }
    }
}

/// Whether a path is on this computer, judged from its text alone, without touching it.
///
/// On Windows, merely looking at a UNC path such as `\\host\share\x.jar` (or its other
/// spellings, `\\?\UNC\host\...`, `//host/...`) makes the system connect to that host over
/// SMB and offer the person's credentials. That connection is outside any isolation the app
/// gives a run. Device paths (`\\.\...`) and NT paths (`\??\...`) are refused too. The only
/// forms allowed among these name a drive on this computer: `\\?\C:\...` and `\??\C:\...`.
///
/// Paths are judged the same way on every system, because a report written on Windows can be
/// opened anywhere.
pub fn is_local_path(path: &Path) -> bool {
    let text = path.to_string_lossy();
    let separator = |c: char| c == '\\' || c == '/';
    let mut chars = text.chars();
    let (Some(first), Some(second)) = (chars.next(), chars.next()) else { return true };
    let double = separator(first) && separator(second);
    let nt = separator(first) && second == '?' && text[2..].starts_with('?');
    if !double && !nt {
        return true;
    }
    // `\\?\C:\` or `\??\C:\`: a drive on this computer.
    let rest = &text[2..];
    let mut rest = rest.chars();
    matches!(
        (rest.next(), rest.next(), rest.next(), rest.next()),
        (Some('?'), Some(s), Some(drive), Some(':')) if separator(s) && drive.is_ascii_alphabetic()
    )
}

/// The current time for log lines, in UTC.
pub fn now_for_log() -> String {
    timestamp(SystemTime::now(), false)
}

/// A folder-safe version of a sample name: letters, digits, dot, dash and underscore only.
fn safe_name(name: &str) -> String {
    let cleaned: String = name.chars().map(|c| if c.is_ascii_alphanumeric() || ".-_".contains(c) { c } else { '_' }).take(60).collect();
    if cleaned.trim_matches(['.', '_']).is_empty() { "sample".into() } else { cleaned }
}

/// `sample=<path>`, so a run or a check always uses the chosen sample.
fn sample_override(sample: &SampleChoice) -> String {
    format!("sample={}", config::render_scalar_str(&sample.path))
}

/// UTC time as `YYYY-MM-DD HH-MM-SS` (for folder names) or `YYYY-MM-DD HH:MM` (for people).
fn timestamp(time: SystemTime, for_folder: bool) -> String {
    let secs = time.duration_since(SystemTime::UNIX_EPOCH).map_or(0, |d| d.as_secs());
    let (days, rest) = (secs / 86_400, secs % 86_400);
    // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
    let z = days as i64 + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    let (h, m, s) = (rest / 3600, rest % 3600 / 60, rest % 60);
    if for_folder {
        format!("{year:04}-{month:02}-{day:02} {h:02}-{m:02}-{s:02}")
    } else {
        format!("{year:04}-{month:02}-{day:02} {h:02}:{m:02}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn timestamps_and_names() {
        let t = SystemTime::UNIX_EPOCH + Duration::from_secs(1_709_283_690);
        assert_eq!(timestamp(t, false), "2024-03-01 09:01");
        assert_eq!(timestamp(t, true), "2024-03-01 09-01-30");
        assert_eq!(safe_name("invoice\u{202e}fdp.exe .jar"), "invoice_fdp.exe_.jar");
        assert_eq!(safe_name("../.."), "sample");
    }


    #[test]
    fn paths_on_other_computers_are_not_local() {
        for remote in [
            r"\\attacker.example\share\x.jar",
            r"\\?\UNC\attacker.example\share\x.jar",
            r"\\.\pipe\x",
            r"\??\UNC\attacker.example\share",
            r"\\?\GLOBALROOT\Device\Mup\host\share",
            "//attacker.example/share/x.jar",
            r"/\attacker.example\share",
        ] {
            assert!(!is_local_path(Path::new(remote)), "{remote}");
            assert!(Session::choose(Path::new(remote)).is_err(), "{remote}");
        }
        for local in [r"C:\samples\x.jar", r"\\?\C:\samples\x.jar", r"\??\D:\x.jar", "/samples/x.jar", "x.jar", "", "/", r"\samples\x.jar"] {
            assert!(is_local_path(Path::new(local)), "{local}");
        }
    }

    /// The app recognises an isolation failure only by the add-on's exit code. The add-on must
    /// use the same number, whether through the shared constant or a copy of it.
    #[test]
    fn the_sandbox_add_on_agrees_on_the_isolation_failure_code() {
        let helper = include_str!("../../../../sandbox/src/main.rs");
        let shared = helper.contains("SANDBOX_ISOLATION_FAILED");
        let copied = helper.contains(&format!("ISOLATION_FAILED: i32 = {};", views::SANDBOX_ISOLATION_FAILED));
        assert!(shared || copied, "gui/sandbox/src/main.rs must use views::SANDBOX_ISOLATION_FAILED");
    }

    #[test]
    fn without_an_engine_everything_says_why() {
        let dir = std::env::temp_dir().join(format!("javabyte-session-{}", std::process::id()));
        let mut session = Session::new(Err("Java was not found.".into()), dir.join("runs"), dir.join("recent.json"));
        assert_eq!(session.engine().problem.as_deref(), Some("Java was not found."));
        let sample = SampleChoice { path: "x".into(), name: "x".into() };
        assert_eq!(session.inspect(sample.clone()).unwrap_err(), "Java was not found.");
        assert!(session.start().is_err(), "no sample chosen");
        assert_eq!(session.status().state, RunState::Idle);
        assert!(session.report().is_err());
        assert!(session.recent().is_empty());
        assert_eq!(session.events_page(&PageRequest::default()).total, 0);
    }
}

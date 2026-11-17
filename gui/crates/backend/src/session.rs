//! The app's state between screens: the chosen sample, the run in progress, the open report and
//! the list of recent runs. No window code, so it is tested on its own against the real tool.
//!
//! One analysis runs at a time (a decision recorded in docs/GUI-PLAN.md). Every run writes to a
//! folder of its own under the runs folder, named by time and sample, so results are never
//! overwritten and people can find them in their file manager.

use crate::events::EventStore;
use crate::report::ReportEventsSource;
use crate::runner::{Ended, Launcher, Run, RunRequest};
use javabyte_gui_protocol::report::{Report, describe_outcome};
use javabyte_gui_protocol::stream::StreamRecord;
use javabyte_gui_protocol::views::{self, EngineStatus, EntryChoice, Overview, RecentRun, ReportView, SampleChoice};
use javabyte_gui_protocol::{EventRow, Page, PageRequest, RunState, RunStatus};
use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime};

/// Categories whose events are shown live while a run goes on.
const NOTABLE: &[&str] = &["process", "net", "class", "native"];
/// Fixed order for the live per-category table, so rows do not jump around.
const CATEGORY_ORDER: &[&str] = &["process", "net", "file", "class", "native", "crypto", "string", "reflect", "thread", "console", "policy", "gap"];
const RECENT_LIMIT: usize = 20;
/// How long a run gets to honour a stop request before its process is ended.
const STOP_GRACE: Duration = Duration::from_secs(15);

pub struct Session {
    launcher: Result<Launcher, String>,
    runs_root: PathBuf,
    recent_file: PathBuf,
    active: Option<Active>,
    report: Option<OpenReport>,
}

struct Active {
    run: Run,
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
}

impl Session {
    /// `launcher` is the engine, or why it cannot be used; `runs_root` holds one folder per run.
    pub fn new(launcher: Result<Launcher, String>, runs_root: PathBuf, recent_file: PathBuf) -> Self {
        Self { launcher, runs_root, recent_file, active: None, report: None }
    }

    pub fn engine(&self) -> EngineStatus {
        match &self.launcher {
            Ok(_) => EngineStatus { ready: true, problem: None },
            Err(problem) => EngineStatus { ready: false, problem: Some(problem.clone()) },
        }
    }

    fn launcher(&self) -> Result<&Launcher, String> {
        self.launcher.as_ref().map_err(Clone::clone)
    }

    /// Checks a path someone picked or dropped.
    pub fn choose(path: &Path) -> Result<SampleChoice, String> {
        if !path.exists() {
            return Err(format!("{} could not be found.", path.display()));
        }
        let name = path.file_name().map_or_else(|| path.display().to_string(), |n| n.to_string_lossy().into_owned());
        Ok(SampleChoice { path: path.display().to_string(), name })
    }

    /// Describes a sample without running it.
    pub fn inspect(&self, sample: SampleChoice) -> Result<Overview, String> {
        let inspection = self.launcher()?.inspect(Path::new(&sample.path))?;
        Ok(views::overview(sample, inspection))
    }

    /// The same analysis as a command, so people can repeat or share it outside the app.
    pub fn command_line(sample: &SampleChoice, entry: &EntryChoice) -> String {
        let mut parts = vec!["javabyte".to_string(), "run".into(), quote(&sample.path)];
        if let Some(value) = entry.override_value() {
            parts.push("-s".into());
            parts.push(quote(&value));
        }
        parts.join(" ")
    }

    /// Starts an analysis. Refused while another is running.
    pub fn start(&mut self, sample: SampleChoice, entry: &EntryChoice) -> Result<(), String> {
        if self.active.as_ref().is_some_and(|a| a.ended.is_none()) {
            return Err("An analysis is already running. Stop it or wait for it to finish.".into());
        }
        let output = self.runs_root.join(format!("{} {}", timestamp(SystemTime::now(), true), safe_name(&sample.name)));
        std::fs::create_dir_all(&output).map_err(|e| format!("The results folder could not be created: {e}"))?;
        let request = RunRequest {
            sample: PathBuf::from(&sample.path),
            output: output.clone(),
            config: None,
            overrides: entry.override_value().into_iter().collect(),
        };
        let run = self.launcher()?.start(&request)?;
        self.active = Some(Active {
            run,
            sample,
            output,
            started: Instant::now(),
            stop_requested: None,
            notable: VecDeque::new(),
            ended: None,
        });
        Ok(())
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
                if let StreamRecord::Event(event) = record {
                    if NOTABLE.contains(&event.category.as_str()) || (event.category == "file" && event.action == "write") {
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
                outcome: ended.tally.entries.first().map_or("", |e| describe_outcome(&e.outcome)).to_string(),
                events: opened.view.event_count,
                output_dir: active.output.display().to_string(),
            };
            self.report = Some(opened);
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
    }

    /// The report of the last finished or opened run.
    pub fn report(&self) -> Result<ReportView, String> {
        self.report.as_ref().map(|r| r.view.clone()).ok_or_else(|| "No report is open.".to_string())
    }

    /// Opens the report of a run in the recent list.
    pub fn open_recent(&mut self, id: &str) -> Result<ReportView, String> {
        let recent = self.recent().into_iter().find(|r| r.id == id).ok_or("That run is no longer in the list.")?;
        let opened = open_report(Path::new(&recent.output_dir), &recent.sample_name)?;
        let view = opened.view.clone();
        self.report = Some(opened);
        Ok(view)
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

    /// A page of the open report's events; empty if none is open.
    pub fn events_page(&mut self, request: &PageRequest) -> Page {
        match self.report.as_mut() {
            Some(report) => report.events.page(request),
            None => Page { total: 0, unfiltered_total: 0, offset: 0, rows: Vec::new() },
        }
    }
}

fn open_report(output: &Path, sample_name: &str) -> Result<OpenReport, String> {
    let file = output.join("report.json");
    let text = std::fs::read_to_string(&file).map_err(|e| format!("The report could not be read: {e}"))?;
    let report: Report = serde_json::from_str(&text).map_err(|e| format!("The report could not be read: {e}"))?;
    let events = ReportEventsSource::read(text.as_bytes())?;
    Ok(OpenReport {
        view: views::report_view(&report, sample_name, &output.display().to_string()),
        events: EventStore::new(Box::new(events)),
    })
}

/// A folder-safe version of a sample name: letters, digits, dot, dash and underscore only.
fn safe_name(name: &str) -> String {
    let cleaned: String = name.chars().map(|c| if c.is_ascii_alphanumeric() || ".-_".contains(c) { c } else { '_' }).take(60).collect();
    if cleaned.trim_matches(['.', '_']).is_empty() { "sample".into() } else { cleaned }
}

/// Quotes for a POSIX shell when needed.
fn quote(text: &str) -> String {
    if !text.is_empty() && text.chars().all(|c| c.is_ascii_alphanumeric() || "-_./:=\\".contains(c)) {
        text.to_string()
    } else {
        format!("'{}'", text.replace('\'', r"'\''"))
    }
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
    fn command_line_matches_the_choice() {
        let sample = SampleChoice { path: "/tmp/a b.jar".into(), name: "a b.jar".into() };
        assert_eq!(Session::command_line(&sample, &EntryChoice::Main), "javabyte run '/tmp/a b.jar'");
        assert_eq!(
            Session::command_line(&sample, &EntryChoice::ClinitSweep),
            "javabyte run '/tmp/a b.jar' -s 'entryPoints=[{type: clinit-sweep}]'"
        );
    }

    #[test]
    fn without_an_engine_everything_says_why() {
        let dir = std::env::temp_dir().join(format!("javabyte-session-{}", std::process::id()));
        let mut session = Session::new(Err("Java was not found.".into()), dir.join("runs"), dir.join("recent.json"));
        assert_eq!(session.engine().problem.as_deref(), Some("Java was not found."));
        let sample = SampleChoice { path: "x".into(), name: "x".into() };
        assert_eq!(session.inspect(sample.clone()).unwrap_err(), "Java was not found.");
        assert!(session.start(sample, &EntryChoice::Main).is_err());
        assert_eq!(session.status().state, RunState::Idle);
        assert!(session.report().is_err());
        assert!(session.recent().is_empty());
        assert_eq!(session.events_page(&PageRequest::default()).total, 0);
    }
}

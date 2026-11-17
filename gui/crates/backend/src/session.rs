//! The app's state between screens: the chosen sample, the run in progress, the open report and
//! the list of recent runs. No window code, so it is tested on its own against the real tool.
//!
//! One analysis runs at a time (a decision recorded in docs/GUI-PLAN.md). Every run writes to a
//! folder of its own under the runs folder, named by time and sample, so results are never
//! overwritten and people can find them in their file manager.

use crate::config::{self, ConfigDoc, ConfigPath, Shell};
use crate::events::EventStore;
use crate::report::ReportEventsSource;
use crate::runner::{Ended, Launcher, Run, RunRequest};
use javabyte_gui_protocol::report::{Report, describe_outcome};
use javabyte_gui_protocol::stream::StreamRecord;
use javabyte_gui_protocol::schema::Schema;
use javabyte_gui_protocol::t;
use javabyte_gui_protocol::validate::Validation;
use javabyte_gui_protocol::views::{self, EngineStatus, Imported, Overview, Preset, RecentRun, ReportView, SampleChoice};
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
    /// The chosen sample and the settings for its next run.
    settings: Option<(SampleChoice, ConfigDoc)>,
    schema: Option<Schema>,
}

/// Largest config file that may be imported. Configs are small; this stops a wrong pick (a
/// sample, a disk image) from being read into memory.
const IMPORT_LIMIT: u64 = 1 << 20;

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
        Self { launcher, runs_root, recent_file, active: None, report: None, settings: None, schema: None }
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

    /// The settings for the next run, as YAML.
    pub fn settings_text(&self) -> Option<String> {
        self.settings.as_ref().map(|(_, doc)| doc.text().to_string())
    }

    /// Replaces the settings, as the settings screen sends them after each change.
    pub fn set_settings_text(&mut self, text: String) {
        if let Some((_, doc)) = self.settings.as_mut() {
            doc.set_text(text);
        }
    }

    /// Every option the tool has, for the forms. Asked once, then kept.
    pub fn schema(&mut self) -> Result<Schema, String> {
        if self.schema.is_none() {
            self.schema = Some(self.launcher()?.schema()?);
        }
        Ok(self.schema.clone().expect("just set"))
    }

    /// Every problem in some settings, as the tool sees them. The sample is the chosen one.
    pub fn check(&self, text: &str) -> Result<Validation, String> {
        self.checker()?.check(text)
    }

    /// What a check needs, so that the tool can be run without holding the session.
    pub fn checker(&self) -> Result<Checker, String> {
        Ok(Checker {
            launcher: self.launcher()?.clone(),
            folder: self.data_dir(),
            sample: self.settings.as_ref().map(|(s, _)| s.clone()),
        })
    }

    /// The same analysis as a command, so people can repeat or share it outside the app.
    pub fn command_line(&self) -> String {
        let Some((sample, doc)) = self.settings.as_ref() else { return String::new() };
        let json = doc.to_json().unwrap_or_default();
        config::command_line(&sample.path, &json, Shell::native())
    }

    /// Starts an analysis of the chosen sample with its settings. Refused while another runs.
    pub fn start(&mut self) -> Result<(), String> {
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

    fn data_dir(&self) -> PathBuf {
        self.recent_file.parent().map_or_else(|| self.runs_root.clone(), Path::to_path_buf)
    }

    /// Where saved presets are kept, for showing to people.
    pub fn presets_folder(&self) -> PathBuf {
        self.data_dir().join("presets")
    }

    /// Saved presets, by name.
    pub fn presets(&self) -> Vec<Preset> {
        let mut names: Vec<String> = std::fs::read_dir(self.presets_folder())
            .into_iter()
            .flatten()
            .flatten()
            .filter_map(|e| {
                let path = e.path();
                let stem = path.file_stem()?.to_string_lossy().into_owned();
                path.extension().is_some_and(|x| x == "yaml").then_some(stem)
            })
            .collect();
        names.sort_by_key(|n| n.to_lowercase());
        names.into_iter().map(|name| Preset { name }).collect()
    }

    /// Saves settings as a preset, without the sample. The name becomes the file name.
    pub fn save_preset(&self, name: &str, text: &str) -> Result<Preset, String> {
        let name = preset_name(name).ok_or_else(|| t!("error-preset-name"))?;
        let mut doc = ConfigDoc::new(text);
        doc.remove(&ConfigPath::parse("sample")).map_err(|_| t!("error-preset-unreadable"))?;
        std::fs::create_dir_all(self.presets_folder()).map_err(|e| t!("error-preset-save", detail = e.to_string()))?;
        std::fs::write(self.presets_folder().join(format!("{name}.yaml")), doc.text()).map_err(|e| t!("error-preset-save", detail = e.to_string()))?;
        Ok(Preset { name })
    }

    /// A preset's settings, for the chosen sample.
    pub fn load_preset(&self, name: &str) -> Result<String, String> {
        let name = preset_name(name).ok_or_else(|| t!("error-preset-name"))?;
        let text = std::fs::read_to_string(self.presets_folder().join(format!("{name}.yaml"))).map_err(|e| t!("error-preset-read", detail = e.to_string()))?;
        Ok(with_sample(&text, self.settings.as_ref().map(|(s, _)| s)))
    }

    pub fn delete_preset(&self, name: &str) -> Result<(), String> {
        let name = preset_name(name).ok_or_else(|| t!("error-preset-name"))?;
        std::fs::remove_file(self.presets_folder().join(format!("{name}.yaml"))).map_err(|e| t!("error-preset-delete", detail = e.to_string()))
    }

    /// Reads a config file someone chose: YAML as it is, JSON turned into YAML.
    pub fn import(path: &Path) -> Result<Imported, String> {
        let size = std::fs::metadata(path).map_err(|e| t!("error-import", detail = e.to_string()))?.len();
        if size > IMPORT_LIMIT {
            return Err(t!("error-import-too-big"));
        }
        let raw = std::fs::read_to_string(path).map_err(|e| t!("error-import", detail = e.to_string()))?;
        let text = match serde_json::from_str::<serde_json::Value>(&raw) {
            Ok(value @ serde_json::Value::Object(_)) => config::render_document(&value),
            _ => raw,
        };
        // A file that cannot be read as YAML is still offered: the YAML tab shows where.
        let doc = ConfigDoc::new(text.clone());
        let sample = doc.get(&ConfigPath::parse("sample")).and_then(|v| v.as_str().map(str::to_string));
        Ok(Imported { text, sample })
    }

    /// Writes the settings to a file someone chose.
    pub fn export(path: &Path, text: &str) -> Result<(), String> {
        std::fs::write(path, text).map_err(|e| t!("error-export", detail = e.to_string()))
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
                outcome: ended.tally.entries.first().map(|e| describe_outcome(&e.outcome)).unwrap_or_default(),
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
        self.report.as_ref().map(|r| r.view.clone()).ok_or_else(|| javabyte_gui_protocol::t!("error-no-report"))
    }

    /// Opens the report of a run in the recent list.
    pub fn open_recent(&mut self, id: &str) -> Result<ReportView, String> {
        let recent = self.recent().into_iter().find(|r| r.id == id).ok_or_else(|| javabyte_gui_protocol::t!("error-run-gone"))?;
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
    let text = std::fs::read_to_string(&file).map_err(|e| javabyte_gui_protocol::t!("error-report-unreadable", detail = e.to_string()))?;
    let report: Report = serde_json::from_str(&text).map_err(|e| javabyte_gui_protocol::t!("error-report-unreadable", detail = e.to_string()))?;
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

/// Checks settings with the tool. Taken from the session by [`Session::checker`].
pub struct Checker {
    launcher: Launcher,
    folder: PathBuf,
    sample: Option<SampleChoice>,
}

impl Checker {
    pub fn check(&self, text: &str) -> Result<Validation, String> {
        std::fs::create_dir_all(&self.folder).map_err(|e| t!("error-results-folder", detail = e.to_string()))?;
        // One file per check, so two checks at once cannot read each other's settings.
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let n = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let file = self.folder.join(format!("check-{}-{n}.yaml", std::process::id()));
        std::fs::write(&file, text).map_err(|e| t!("error-results-folder", detail = e.to_string()))?;
        let overrides: Vec<String> = self.sample.iter().map(sample_override).collect();
        let result = self.launcher.validate(Some(&file), &overrides);
        let _ = std::fs::remove_file(&file);
        result
    }
}

/// `sample=<path>`, so a run or a check always uses the chosen sample.
fn sample_override(sample: &SampleChoice) -> String {
    format!("sample={}", config::render_scalar_str(&sample.path))
}

/// Settings text with its `sample` set to the chosen one (or left out if none is chosen).
pub fn with_sample(text: &str, sample: Option<&SampleChoice>) -> String {
    let mut doc = ConfigDoc::new(text);
    let result = match sample {
        Some(sample) => doc.set(&ConfigPath::parse("sample"), &serde_json::Value::String(sample.path.clone())),
        None => doc.remove(&ConfigPath::parse("sample")),
    };
    if result.is_ok() { doc.text().to_string() } else { text.to_string() }
}

/// A preset name that is also a safe file name: letters, digits, spaces, dash, underscore,
/// up to 60 characters. `None` if nothing is left.
fn preset_name(name: &str) -> Option<String> {
    let cleaned: String = name.trim().chars().filter(|c| c.is_alphanumeric() || " -_".contains(*c)).take(60).collect();
    let cleaned = cleaned.trim().to_string();
    (!cleaned.is_empty()).then_some(cleaned)
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
    fn settings_take_the_chosen_sample() {
        let sample = SampleChoice { path: "C:\\samples\\b.jar".into(), name: "b.jar".into() };
        let text = "# from another sample\nsample: /old/a.jar   # was this\nlimits:\n  maxSleeps: 3\n";
        let moved = with_sample(text, Some(&sample));
        assert!(moved.contains("sample: 'C:\\samples\\b.jar'   # was this"), "{moved}");
        assert!(moved.contains("# from another sample"));
        assert!(!with_sample(text, None).contains("sample:"));
        assert_eq!(preset_name("  My preset: v2! "), Some("My preset v2".into()));
        assert_eq!(preset_name("../.."), None);
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

//! Reports: the one open now, opening others (from the recent list or from disk), comparing
//! two, running again with a report's settings, and the files a run saved.

use super::{OpenReport, Session, timestamp, with_sample, without_host_programs};
use crate::config::{self, ConfigDoc, ConfigPath};
use crate::events::EventStore;
use crate::report::ReportEventsSource;
use javabyte_gui_protocol::report::{Report, describe_outcome};
use javabyte_gui_protocol::t;
use javabyte_gui_protocol::views::{self, Overview, RecentRun, ReportView};
use std::path::{Path, PathBuf};
use std::time::SystemTime;

impl Session {
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

    /// Opens a report the app did not necessarily make: a `report.json`, or the folder holding
    /// one (such as a command-line run's `javabyte-out`). It joins the recent list.
    pub fn open_report_at(&mut self, path: &Path) -> Result<ReportView, String> {
        let folder = if path.is_dir() { path.to_path_buf() } else { path.parent().map(Path::to_path_buf).unwrap_or_default() };
        let (report, _) = read_report(&folder)?;
        let name = file_name_of(&report.sample.path);
        let opened = open_report(&folder, &name)?;
        let view = opened.view.clone();
        self.remember(RecentRun {
            id: folder.display().to_string(),
            sample_name: name,
            when: timestamp(SystemTime::now(), false),
            outcome: report.runs.first().map(|r| describe_outcome(&r.outcome)).unwrap_or_default(),
            events: view.event_count,
            output_dir: folder.display().to_string(),
        });
        self.report = Some(opened);
        Ok(view)
    }

    /// Compares two runs from the recent list; `a` is the earlier one, shown first.
    pub fn compare(&self, a: &str, b: &str) -> Result<javabyte_gui_protocol::compare::Comparison, String> {
        let find = |id: &str| self.recent().into_iter().find(|r| r.id == id).ok_or_else(|| t!("error-run-gone"));
        let (ra, rb) = (find(a)?, find(b)?);
        let (report_a, _) = read_report(Path::new(&ra.output_dir))?;
        let (report_b, _) = read_report(Path::new(&rb.output_dir))?;
        Ok(javabyte_gui_protocol::compare::compare(
            (&report_a, &ra.sample_name, &ra.output_dir),
            (&report_b, &rb.sample_name, &rb.output_dir),
        ))
    }

    /// The open report's sample, chosen again with the run's settings, for **Run again with
    /// changes**. The sample must still be where it was, on this computer. The report may
    /// come from someone else, so settings that name a program to start are left out
    /// ([`without_host_programs`]).
    pub fn run_again(&mut self) -> Result<Overview, String> {
        let open = self.report.as_ref().ok_or_else(|| t!("error-no-report"))?;
        let moved = || t!("error-sample-moved", path = open.view.sample_path.clone());
        // No local path: the report names a sample on another computer.
        let sample_path = open.sample_path.clone().ok_or_else(|| t!("error-sample-remote", path = open.view.sample_path.clone()))?;
        let text = open.view.config_text.clone().unwrap_or_else(|| config::render_document(&open.view.config));
        let sample = Session::choose(&sample_path).map_err(|_| moved())?;
        let overview = self.inspect(sample.clone())?;
        let mut doc = ConfigDoc::new(without_host_programs(&with_sample(&text, Some(&sample))));
        // The run's own folder is set per run by the app; a copied one would mislead.
        let _ = doc.remove(&ConfigPath::parse("output.directory"));
        let _ = doc.remove(&ConfigPath::parse("output.console"));
        self.set_settings_text(doc.text().to_string());
        Ok(overview)
    }

    /// The open report, or why there is none.
    #[cfg_attr(target_arch = "wasm32", allow(dead_code))]
    fn open_report(&self) -> Result<&OpenReport, String> {
        self.report.as_ref().ok_or_else(|| t!("error-no-report"))
    }

    /// The open report's saved files, with size, SHA-256 and origin.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn artifacts(&self) -> Result<Vec<javabyte_gui_protocol::views::Artifact>, String> {
        let open = self.open_report()?;
        let (report, _) = read_report(&open.folder)?;
        Ok(crate::artifacts::list(&open.folder, &report))
    }

    /// A saved file's real path, checked to be one of the open report's own.
    #[cfg(not(target_arch = "wasm32"))]
    fn artifact_path(&self, relative: &str) -> Result<PathBuf, String> {
        let open = self.open_report()?;
        crate::artifacts::resolve(&open.folder, &open.artifacts, relative)
    }

    /// One 64 KB page of a saved file.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn artifact_page(&self, relative: &str, offset: u64) -> Result<javabyte_gui_protocol::views::FilePage, String> {
        crate::artifacts::read_page(&self.artifact_path(relative)?, offset)
    }

    /// A saved class as readable instructions.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn artifact_disasm(&self, relative: &str) -> Result<String, String> {
        let path = self.artifact_path(relative)?;
        self.launcher()?.disasm(&path)
    }

    /// Where a copy may be saved: never next to the sample. When the report's sample is not
    /// on this computer there is no folder of it here to keep away from, and its path is not
    /// looked at.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn check_save_destination(&self, destination: &Path) -> Result<(), String> {
        match &self.open_report()?.sample_path {
            Some(sample) => crate::artifacts::check_destination(destination, sample),
            None => Ok(()),
        }
    }

    /// Saves the chosen files in a password zip.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn save_zip(&self, relatives: &[String], destination: &Path) -> Result<(), String> {
        self.check_save_destination(destination)?;
        let open = self.open_report()?;
        let all = self.artifacts()?;
        let files: Vec<_> = relatives
            .iter()
            .map(|r| {
                let artifact = all.iter().find(|a| a.path == *r).cloned().ok_or_else(|| t!("error-artifact-refused", path = r.clone()))?;
                Ok((artifact, self.artifact_path(r)?))
            })
            .collect::<Result<_, String>>()?;
        crate::artifacts::write_zip(destination, &open.folder, &files)
    }

    /// Saves one file as it is, after the warning; recorded in the run's folder.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn save_as_is(&self, relative: &str, destination: &Path) -> Result<(), String> {
        self.check_save_destination(destination)?;
        let open = self.open_report()?;
        let artifact = self.artifacts()?.into_iter().find(|a| a.path == relative).ok_or_else(|| t!("error-artifact-refused", path = relative))?;
        crate::artifacts::save_as_is(destination, &open.folder, &artifact, &self.artifact_path(relative)?)
    }
}

/// Reads a run folder's report, its events and its settings file.
pub(super) fn open_report(output: &Path, sample_name: &str) -> Result<OpenReport, String> {
    let (report, text) = read_report(output)?;
    let events = ReportEventsSource::read(text.as_bytes())?;
    let mut view = views::report_view(&report, sample_name, &output.display().to_string());
    view.config_text = std::fs::read_to_string(output.join("config.yaml")).ok();
    Ok(OpenReport {
        view,
        events: EventStore::new(Box::new(events)),
        folder: output.to_path_buf(),
        artifacts: report.artifacts.clone(),
        // The report may have come from someone else: a path on another computer is never used.
        sample_path: Some(PathBuf::from(&report.sample.path)).filter(|p| super::is_local_path(p)),
    })
}

/// A run folder's report, and its text.
pub(super) fn read_report(folder: &Path) -> Result<(Report, String), String> {
    let text = std::fs::read_to_string(folder.join("report.json")).map_err(|e| t!("error-report-unreadable", detail = e.to_string()))?;
    let report: Report = serde_json::from_str(&text).map_err(|e| t!("error-not-a-report", detail = e.to_string()))?;
    Ok((report, text))
}

/// The file name in a path written on any system (`C:\a\b.jar` or `/a/b.jar`).
pub(super) fn file_name_of(path: &str) -> String {
    path.rsplit(['/', '\\']).find(|p| !p.is_empty()).unwrap_or(path).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A report folder someone sent, whose `report.json` puts the sample on another computer.
    fn report_folder_with_sample(name: &str, sample: &str) -> PathBuf {
        let folder = std::env::temp_dir().join(format!("javabyte-remote-report-{}-{name}", std::process::id()));
        std::fs::create_dir_all(&folder).unwrap();
        let mut report: serde_json::Value = serde_json::from_str(include_str!("../../../protocol/tests/tool-output/report-basics.json")).unwrap();
        report["sample"]["path"] = serde_json::Value::String(sample.into());
        std::fs::write(folder.join("report.json"), report.to_string()).unwrap();
        folder
    }

    /// A sample path on another computer is kept for display only: saving a copy and running
    /// again never look at it.
    #[test]
    fn a_reports_sample_on_another_computer_is_never_looked_at() {
        let remote = r"\\attacker.example\share\x.jar";
        let folder = report_folder_with_sample("unc", remote);
        let dir = folder.join("app");
        let mut session = Session::new(Err("no engine".into()), dir.join("runs"), dir.join("recent.json"));
        let view = session.open_report_at(&folder).unwrap();
        assert_eq!(view.sample_path, remote, "the report still says where the sample was");
        assert_eq!(session.open_report().unwrap().sample_path, None);
        assert!(session.check_save_destination(&std::env::temp_dir().join("copy.zip")).is_ok());
        let refused = session.run_again().unwrap_err();
        assert_eq!(refused, t!("error-sample-remote", path = remote));
        let _ = std::fs::remove_dir_all(folder);
    }

    /// A local sample path is kept, so copies are still kept away from it.
    #[test]
    fn a_reports_local_sample_is_kept() {
        let folder = report_folder_with_sample("local", "/samples/x.jar");
        let dir = folder.join("app");
        let mut session = Session::new(Err("no engine".into()), dir.join("runs"), dir.join("recent.json"));
        session.open_report_at(&folder).unwrap();
        assert_eq!(session.open_report().unwrap().sample_path.as_deref(), Some(Path::new("/samples/x.jar")));
        let _ = std::fs::remove_dir_all(folder);
    }
}

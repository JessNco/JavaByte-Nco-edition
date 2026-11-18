//! What the screens show, prepared from the tool's output.
//!
//! The backend builds these from the tool's real output; the browser build used for automated
//! accessibility tests builds them from the committed tool-output samples. Either way the
//! screens only display them. Wording for people lives here so it can be tested in one place.

use crate::inspect::Inspection;
use crate::t;
use crate::report::{Report, describe_outcome};
use serde::{Deserialize, Serialize};

/// A sample the person chose, by picker or by dropping it on the window.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SampleChoice {
    /// The full path, as the system gave it.
    pub path: String,
    /// The file or folder name, for headings.
    pub name: String,
}

/// Where an analysis starts running the sample.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum EntryChoice {
    /// The main method named in the manifest.
    Main,
    /// The main method of a chosen class.
    MainOf {
        /// The class, with dots.
        class: String,
    },
    /// A Java agent's `premain` method, called as the JVM would with no options.
    Agent {
        /// The agent class, with dots.
        class: String,
    },
    /// Every class's start-up code, to collect hidden text.
    ClinitSweep,
}

impl EntryChoice {
    /// The `entryPoints` list that selects this choice in the settings.
    pub fn entry_points(&self) -> serde_json::Value {
        use serde_json::json;
        match self {
            EntryChoice::Main => json!([{"type": "main"}]),
            EntryChoice::MainOf { class } => json!([{"type": "main", "class": class}]),
            // premain(String options, Instrumentation inst): no options, and no instrumentation.
            EntryChoice::Agent { class } => json!([{"type": "method", "class": class, "method": "premain", "args": ["", null]}]),
            EntryChoice::ClinitSweep => json!([{"type": "clinit-sweep"}]),
        }
    }
}

/// One way to start the sample, offered on the overview screen.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct EntryOption {
    /// What the choice is called on screen. Untrusted: it names classes from the sample.
    pub label: String,
    /// One sentence on what choosing it does.
    pub help: String,
    /// The choice itself.
    pub entry: EntryChoice,
}

/// Something the overview wants the person to know before running.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Finding {
    /// `attention` for things that change what to do next; `info` otherwise. Shown in words,
    /// never by colour alone.
    pub level: String,
    /// The finding in a few words.
    pub title: String,
    /// What it means, in a sentence or two.
    pub detail: String,
}

/// Everything the overview screen shows.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Overview {
    /// The sample described.
    pub sample: SampleChoice,
    /// What `inspect` found.
    pub inspection: Inspection,
    /// What matters about it, most important first.
    pub findings: Vec<Finding>,
    /// Ways to start, the recommended one first.
    pub entries: Vec<EntryOption>,
    /// Further classes with a main method, beyond those in `entries`, dotted. Offered as a
    /// list to choose from rather than as separate options.
    #[serde(default)]
    pub other_classes: Vec<String>,
}

/// Reads an inspection and says what it means for the next step.
pub fn overview(sample: SampleChoice, inspection: Inspection) -> Overview {
    let mut findings = Vec::new();
    let mut entries = Vec::new();
    let dotted = |name: &str| name.replace('/', ".");

    match (&inspection.main_class, &inspection.agent_class) {
        (Some(main), _) => entries.push(EntryOption {
            label: t!("entry-main", class = dotted(main)),
            help: t!("entry-main-help"),
            entry: EntryChoice::Main,
        }),
        (None, Some(agent)) => {
            findings.push(Finding {
                level: "attention".into(),
                title: t!("finding-agent"),
                detail: t!("finding-agent-detail"),
            });
            entries.push(EntryOption {
                label: t!("entry-agent", class = dotted(agent)),
                help: t!("entry-agent-help"),
                entry: EntryChoice::Agent { class: dotted(agent) },
            });
        }
        (None, None) => findings.push(Finding {
            level: "attention".into(),
            title: t!("finding-no-entry"),
            detail: if inspection.main_candidates.is_empty() { t!("finding-no-entry-none") } else { t!("finding-no-entry-choose") },
        }),
    }
    /// Classes offered as their own option; the rest go in a list.
    const OFFERED: usize = 10;
    let mut other_classes = Vec::new();
    for (index, candidate) in inspection.main_candidates.iter().enumerate() {
        let class = dotted(candidate);
        if inspection.main_class.as_deref().map(dotted).as_deref() == Some(class.as_str()) {
            continue;
        }
        if index >= OFFERED {
            other_classes.push(class);
            continue;
        }
        entries.push(EntryOption {
            label: t!("entry-class", class = &class),
            help: t!("entry-class-help"),
            entry: EntryChoice::MainOf { class },
        });
    }
    entries.push(EntryOption {
        label: t!("entry-sweep"),
        help: t!("entry-sweep-help"),
        entry: EntryChoice::ClinitSweep,
    });

    if inspection.native_method_count > 0 || !inspection.native_libraries.is_empty() {
        findings.push(Finding {
            level: "attention".into(),
            title: t!("finding-native"),
            detail: t!("finding-native-detail", methods = inspection.native_method_count, libraries = inspection.native_libraries.len()),
        });
    }
    if inspection.obfuscated_call_sites > 0 {
        findings.push(Finding {
            level: "info".into(),
            title: t!("finding-hidden-calls"),
            detail: t!("finding-hidden-calls-detail", count = inspection.obfuscated_call_sites),
        });
    }
    if !inspection.class_loaders.is_empty() {
        findings.push(Finding {
            level: "info".into(),
            title: t!("finding-loaders"),
            detail: t!("finding-loaders-detail"),
        });
    }
    if !inspection.unparseable_classes.is_empty() {
        findings.push(Finding {
            level: "info".into(),
            title: t!("finding-damaged"),
            detail: t!("finding-damaged-detail", count = inspection.unparseable_classes.len()),
        });
    }
    Overview { sample, inspection, findings, entries, other_classes }
}

/// One list of indicators on the report screen.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct IndicatorSection {
    /// Stable id, for headings and links.
    pub id: String,
    /// The list's heading, such as "Web addresses it contacted".
    pub title: String,
    /// What this list means, in a sentence.
    pub explanation: String,
    /// Untrusted: values from the sample. Pass each through `display_text`.
    pub items: Vec<String>,
}

/// How one entry point ended, for people.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RunLine {
    /// The entry point, such as `a.B.main`. Untrusted.
    pub entry: String,
    /// The tool's outcome word, such as `completed`.
    pub outcome: String,
    /// The outcome in plain words.
    pub meaning: String,
    /// The tool's detail, such as an exception. Untrusted.
    pub detail: Option<String>,
}

/// Everything the report screen shows apart from the event list.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct ReportView {
    /// The sample's file name, for headings. Untrusted.
    pub sample_name: String,
    /// The sample's path as the tool was given it. Untrusted.
    pub sample_path: String,
    /// SHA-256 of the sample, in lowercase hex.
    pub sha256: String,
    /// The plain-language paragraph that opens the report.
    pub summary: String,
    /// How each part of the run ended.
    pub runs: Vec<RunLine>,
    /// Indicator lists that have something in them.
    pub sections: Vec<IndicatorSection>,
    /// Events recorded, in all categories.
    pub event_count: u64,
    /// Places the analysis could not imitate faithfully.
    pub gaps: u64,
    /// Categories whose events were not all kept, and how many were dropped.
    pub dropped: Vec<(String, u64)>,
    /// Where the full report and saved files are.
    pub output_dir: String,
    /// What the next run could supply, for the settings presets.
    #[serde(default)]
    pub follow_ups: FollowUps,
    /// The Limits and gaps tab.
    #[serde(default)]
    pub limits: crate::limits::LimitsView,
    /// The settings as the tool read them.
    #[serde(default)]
    pub config: serde_json::Value,
    /// The run's `config.yaml` as written, comments and all, when the run has one.
    #[serde(default)]
    pub config_text: Option<String>,
    /// How many files the run saved.
    #[serde(default)]
    pub artifact_count: usize,
}

/// A file a run saved: something the sample wrote, code it created, or data it sent.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Artifact {
    /// Relative to the run's folder, as the report lists it (`dropped/C_/x/stage.bin`).
    pub path: String,
    /// The file's own name. Untrusted.
    pub name: String,
    /// `dropped` (a file it wrote), `class` (code it created), `network` (data it sent), `other`.
    pub kind: String,
    /// Size in bytes.
    pub size: u64,
    /// SHA-256 of the file, in lowercase hex.
    pub sha256: String,
    /// Where it came from: the path on the pretend disk, the web address, or the class name.
    /// Untrusted.
    pub origin: Option<String>,
}

/// One page of a saved file, for the viewer.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct FilePage {
    /// Where this page starts, in bytes from the start of the file.
    pub offset: u64,
    /// The file's whole size.
    pub total: u64,
    /// This page's bytes: at most `PAGE_BYTES`.
    pub bytes: Vec<u8>,
}

/// Bytes per viewer page (a decision in docs/gui/PHASE4-PLAN.md).
pub const PAGE_BYTES: u64 = 64 * 1024;

/// The kind of a saved file from where the tool put it.
pub fn artifact_kind(path: &str) -> &'static str {
    // Reports from older versions on Windows use '\\'.
    match path.split(['/', '\\']).next() {
        Some("dropped") => "dropped",
        Some("defined-classes") => "class",
        Some("network") => "network",
        _ => "other",
    }
}

/// Where each saved file came from, from the report's events: the file path the sample wrote,
/// the address a request body was sent to, or the name of a class it defined.
pub fn artifact_origins(report: &Report) -> std::collections::BTreeMap<String, String> {
    let mut out = std::collections::BTreeMap::new();
    let text = |e: &crate::stream::Event, k: &str| e.data.get(k).and_then(|v| v.as_str()).map(str::to_string);
    for event in &report.events {
        match (event.category.as_str(), event.action.as_str()) {
            ("net", _) => {
                if let (Some(file), Some(url)) = (text(event, "requestBodyFile"), text(event, "url").or_else(|| text(event, "endpoint"))) {
                    out.entry(file).or_insert(url);
                }
            }
            ("file", "write") => {
                if let Some(path) = text(event, "path") {
                    // The tool saves C:\a\b as dropped/C_/a/b and /a/b as dropped/a/b.
                    let saved = format!("dropped/{}", path.replace(':', "_").replace('\\', "/").trim_start_matches('/'));
                    out.entry(saved).or_insert(path);
                }
            }
            ("class", _) => {
                if let Some(name) = text(event, "name").or_else(|| text(event, "class")) {
                    let saved = format!("defined-classes/{}.class", name.replace('.', "/"));
                    out.entry(saved).or_insert(name);
                }
            }
            _ => {}
        }
    }
    out
}

/// A config read from a file someone chose to import.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Imported {
    /// As YAML: a JSON file is turned into YAML.
    pub text: String,
    /// The sample the file names, if any. Untrusted.
    pub sample: Option<String>,
}

/// A saved preset: settings without a sample, kept as a YAML file.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Preset {
    /// The preset's name, which is also its file name without `.yaml`.
    pub name: String,
}

/// Things a report shows the sample wanted, which settings can give it next time
/// (the follow-up table in AGENTS.md). Untrusted: values come from the sample.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct FollowUps {
    /// Files it tried to read that were not there (`file.read-missing`).
    pub missing_files: Vec<String>,
    /// Web addresses it asked for that no rule answered (`net.http`, no matching rule).
    pub unanswered_urls: Vec<String>,
    /// Arguments it gave when starting itself again as a Java program (`process.exec`).
    pub relaunch_args: Vec<Vec<String>>,
}

/// Most follow-up values offered of each kind.
const FOLLOW_UP_LIMIT: usize = 50;

/// What the run's events show the sample wanted, for the presets.
pub fn follow_ups(report: &Report) -> FollowUps {
    let mut out = FollowUps::default();
    let text = |e: &crate::stream::Event, key: &str| e.data.get(key).and_then(|v| v.as_str()).map(str::to_string);
    for event in &report.events {
        match (event.category.as_str(), event.action.as_str()) {
            ("file", "read-missing") => {
                if let Some(path) = text(event, "path")
                    && !out.missing_files.contains(&path) && out.missing_files.len() < FOLLOW_UP_LIMIT {
                        out.missing_files.push(path);
                    }
            }
            ("net", "http") if text(event, "matchedRule").is_some_and(|r| r.starts_with("none")) => {
                if let Some(url) = text(event, "url")
                    && !out.unanswered_urls.contains(&url) && out.unanswered_urls.len() < FOLLOW_UP_LIMIT {
                        out.unanswered_urls.push(url);
                    }
            }
            ("process", "exec") => {
                let argv: Vec<String> = event
                    .data
                    .get("argv")
                    .and_then(|v| v.as_array())
                    .map(|a| a.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
                    .unwrap_or_default();
                if let Some(args) = java_program_args(&argv)
                    && !out.relaunch_args.contains(&args) && out.relaunch_args.len() < FOLLOW_UP_LIMIT {
                        out.relaunch_args.push(args);
                    }
            }
            _ => {}
        }
    }
    out
}

/// The program's own arguments in a `java` command line: what follows `-jar FILE`, or the main
/// class after the Java options. `None` if the command does not start Java.
fn java_program_args(argv: &[String]) -> Option<Vec<String>> {
    let program = argv.first()?.rsplit(['/', '\\']).next()?.to_ascii_lowercase();
    if !matches!(program.as_str(), "java" | "javaw" | "java.exe" | "javaw.exe") {
        return None;
    }
    let mut i = 1;
    while i < argv.len() {
        let arg = argv[i].as_str();
        if arg == "-jar" {
            return Some(argv.get(i + 2..).unwrap_or_default().to_vec());
        }
        if matches!(arg, "-cp" | "-classpath" | "--class-path" | "-p" | "--module-path") {
            i += 2;
        } else if arg.starts_with('-') {
            i += 1;
        } else {
            // The main class.
            return Some(argv[i + 1..].to_vec());
        }
    }
    None
}

/// Most values kept per indicator list; the full list is in the report file.
const SECTION_LIMIT: usize = 200;

/// The report screen's data from a report, the sample's name and the run's folder.
pub fn report_view(report: &Report, sample_name: &str, output_dir: &str) -> ReportView {
    let text = |values: &[serde_json::Value]| -> Vec<String> {
        values.iter().take(SECTION_LIMIT).map(|v| v.as_str().map_or_else(|| v.to_string(), str::to_string)).collect()
    };
    let events_of = |category: &str, action: &str, field: &str| -> Vec<String> {
        let mut seen = std::collections::BTreeSet::new();
        report
            .events
            .iter()
            .filter(|e| e.category == category && e.action == action)
            .filter_map(|e| e.data.get(field).map(|v| v.as_str().map_or_else(|| v.to_string(), str::to_string)))
            .filter(|v| seen.insert(v.clone()))
            .take(SECTION_LIMIT)
            .collect()
    };
    let s = &report.summary;
    let mut sections = vec![
        section("processes", text(&s.processes)),
        section("http", text(&s.http_requests)),
        section("connections", text(&s.connections)),
        section("dns", text(&s.dns_lookups)),
        section("files", text(&s.files_written)),
        section("classes", text(&s.classes_defined)),
        section("native", text(&s.native_libraries)),
        section("decoded", events_of("string", "returned", "value")),
        section("crypto", events_of("crypto", "cipher-output", "preview")),
        section("blocked", text(&s.blocked_calls)),
    ];
    sections.retain(|section| !section.items.is_empty());
    ReportView {
        sample_name: sample_name.to_string(),
        sample_path: report.sample.path.clone(),
        sha256: report.sample.sha256.clone(),
        summary: plain_summary(report),
        runs: report
            .runs
            .iter()
            .map(|run| RunLine {
                entry: run.entry.clone(),
                outcome: run.outcome.clone(),
                meaning: describe_outcome(&run.outcome),
                detail: run.detail.clone(),
            })
            .collect(),
        sections,
        event_count: s.event_counts.values().sum(),
        gaps: s.gaps,
        dropped: s.events_dropped.iter().map(|(k, v)| (k.clone(), *v)).collect(),
        output_dir: output_dir.to_string(),
        follow_ups: follow_ups(report),
        limits: crate::limits::limits_view(report),
        config: report.config.clone(),
        config_text: None,
        artifact_count: report.artifacts.len(),
    }
}

/// An indicator list with its title and explanation from the resources.
fn section(id: &str, items: Vec<String>) -> IndicatorSection {
    IndicatorSection {
        id: id.into(),
        title: crate::i18n::tr(&format!("indicator-{id}"), None),
        explanation: crate::i18n::tr(&format!("indicator-{id}-about"), None),
        items,
    }
}

/// The paragraph that opens a report: what the sample tried to do, in one or two sentences.
pub fn plain_summary(report: &Report) -> String {
    let s = &report.summary;
    let mut actions: Vec<String> = Vec::new();
    let mut add = |count: usize, id: &str| {
        if count > 0 {
            actions.push(t!(id, count = count));
        }
    };
    add(s.processes.len(), "summary-started");
    // Distinct hosts, not requests: a beacon loop asks one server many times with different
    // addresses, and "contacted 42 servers" would be wrong.
    let hosts: std::collections::BTreeSet<String> =
        s.http_requests.iter().chain(&s.connections).filter_map(|v| v.as_str()).map(host_of).collect();
    add(hosts.len(), "summary-contacted");
    add(s.dns_lookups.len(), "summary-looked-up");
    add(s.files_written.len(), "summary-wrote");
    add(s.classes_defined.len(), "summary-created");
    add(s.native_libraries.len(), "summary-loaded");
    let mut summary =
        if actions.is_empty() { t!("summary-nothing") } else { t!("summary-sentence", actions = join_list(&actions)) };
    if s.gaps > 0 {
        summary.push(' ');
        summary.push_str(&t!("summary-gaps", count = s.gaps));
    }
    if !s.blocked_calls.is_empty() {
        summary.push(' ');
        summary.push_str(&t!("summary-blocked", count = s.blocked_calls.len()));
    }
    summary
}

/// The host of a URL (`http://a.b:80/x` gives `a.b`) or of a `host:port` endpoint.
fn host_of(address: &str) -> String {
    let rest = address.split_once("://").map_or(address, |(_, rest)| rest);
    let authority = rest.split(['/', '?', '#']).next().unwrap_or(rest);
    let authority = authority.rsplit_once('@').map_or(authority, |(_, host)| host);
    let host = if authority.starts_with('[') {
        authority.split_once(']').map_or(authority, |(h, _)| h).trim_start_matches('[')
    } else {
        authority.rsplit_once(':').map_or(authority, |(h, _)| h)
    };
    host.to_ascii_lowercase()
}

/// "a, b and c".
fn join_list(items: &[String]) -> String {
    match items {
        [] => String::new(),
        [one] => one.clone(),
        [rest @ .., last] => t!("list-and", first = rest.join(&t!("list-separator")), last = last),
    }
}


/// A run in the recent list on the start screen.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RecentRun {
    /// Stable id: the run folder's name for runs the app made, its path for opened reports.
    pub id: String,
    /// The sample's file name. Untrusted.
    pub sample_name: String,
    /// When it ran, as `YYYY-MM-DD HH:MM` in UTC.
    pub when: String,
    /// The first entry point's outcome, in plain words.
    pub outcome: String,
    /// Events recorded.
    pub events: u64,
    /// The run's folder, holding `report.json`.
    pub output_dir: String,
}

/// Whether the analysis engine (Java and the JavaByte tool) can be started.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct EngineStatus {
    /// True when Java and the tool were found and Java starts.
    pub ready: bool,
    /// Plain words: what is wrong and how to fix it. None when ready.
    pub problem: Option<String>,
}

/// The preferences that change how analyses run. The app keeps them in its data folder,
/// because the backend needs them; display preferences stay in the window's storage.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct AppPreferences {
    /// Where runs are saved, one folder each. None: Documents/JavaByte.
    pub output_folder: Option<String>,
    /// A Java 17+ executable to use instead of the bundled runtime. None: the bundled one.
    pub java: Option<String>,
    /// Linux: start each run with no network (a new network namespace).
    pub isolate: bool,
    /// Linux: ask before a run when isolation is on but the system does not allow it.
    pub ask_without_isolation: bool,
    /// Windows, with the sandbox add-on: use Windows Sandbox (strongest, slower to start)
    /// instead of an AppContainer.
    pub strongest_isolation: bool,
}

impl Default for AppPreferences {
    fn default() -> Self {
        Self { output_folder: None, java: None, isolate: true, ask_without_isolation: true, strongest_isolation: false }
    }
}

/// What the app found on this computer, for the Preferences screen and `--self-check`.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SetupStatus {
    /// The app's version, which is also the tool's.
    pub version: String,
    /// `windows`, `linux` or `macos`.
    pub os: String,
    /// The Java executable in use.
    pub java: String,
    /// True when it is the runtime that came with the app.
    pub java_bundled: bool,
    /// What `java -version` says, such as `21.0.5`; None when it did not answer.
    pub java_version: Option<String>,
    /// The JavaByte tool's jar.
    pub jar: String,
    /// The folder runs are saved in.
    pub output_folder: String,
    /// The native bridge's Python, when one came with the app.
    pub bridge: Option<String>,
    /// The network isolation for runs: available, or why not.
    pub isolation: Isolation,
    /// The preferences these come from.
    pub preferences: AppPreferences,
}

/// Whether runs can start without network.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Isolation {
    /// Isolation exists here at all: on Linux always; on Windows with the sandbox add-on.
    pub supported: bool,
    /// The app can create an isolated run right now (Linux: a network namespace; Windows: an
    /// AppContainer).
    pub available: bool,
    /// When not available: why, in plain words, and what to do.
    pub problem: Option<String>,
    /// Windows: Windows Sandbox can be used, as the strongest choice.
    pub strongest_available: bool,
    /// Windows: why Windows Sandbox cannot be used, when it cannot.
    pub strongest_problem: Option<String>,
}

#[cfg(test)]
mod follow_up_tests {
    use super::*;

    #[test]
    fn java_relaunches_give_their_program_arguments() {
        let argv = |a: &[&str]| a.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        assert_eq!(java_program_args(&argv(&["C:\\jre\\bin\\javaw.exe", "-Xmx1g", "-jar", "x.jar", "--install", "2"])), Some(argv(&["--install", "2"])));
        assert_eq!(java_program_args(&argv(&["java", "-cp", "a.jar;b.jar", "a.Main", "go"])), Some(argv(&["go"])));
        assert_eq!(java_program_args(&argv(&["/usr/bin/java", "-jar", "x.jar"])), Some(vec![]));
        assert_eq!(java_program_args(&argv(&["cmd.exe", "/c", "del", "x"])), None);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn inspection(main: Option<&str>, agent: Option<&str>, candidates: &[&str]) -> Inspection {
        serde_json::from_value(serde_json::json!({
            "path": "x.jar", "size": 10, "classCount": 3, "mainClass": main, "agentClass": agent,
            "mainCandidates": candidates, "nativeMethodCount": 0, "obfuscatedCallSites": 0
        }))
        .unwrap()
    }

    fn choice() -> SampleChoice {
        SampleChoice { path: "x.jar".into(), name: "x.jar".into() }
    }

    #[test]
    fn main_class_is_offered_first_and_not_twice() {
        let view = overview(choice(), inspection(Some("a.Main"), None, &["a/Main", "b/Other"]));
        assert_eq!(view.entries[0].entry, EntryChoice::Main);
        assert_eq!(view.entries[1].entry, EntryChoice::MainOf { class: "b.Other".into() });
        assert_eq!(view.entries.last().unwrap().entry, EntryChoice::ClinitSweep);
        assert_eq!(view.entries.len(), 3);
        assert!(view.findings.is_empty());
    }

    #[test]
    fn agents_and_samples_without_a_start_say_so() {
        let agent = overview(choice(), inspection(None, Some("c.Agent"), &[]));
        assert_eq!(agent.entries[0].entry, EntryChoice::Agent { class: "c.Agent".into() });
        assert_eq!(agent.findings[0].title, "This is a Java agent");
        let none = overview(choice(), inspection(None, None, &[]));
        assert_eq!(none.entries, vec![none.entries[0].clone()]);
        assert_eq!(none.entries[0].entry, EntryChoice::ClinitSweep);
        assert!(none.findings[0].detail.contains("Plugins and mods"));
    }

    #[test]
    fn entry_choices_become_entry_points() {
        use serde_json::json;
        assert_eq!(EntryChoice::Main.entry_points(), json!([{"type": "main"}]));
        assert_eq!(EntryChoice::MainOf { class: "a'b".into() }.entry_points(), json!([{"type": "main", "class": "a'b"}]));
        assert_eq!(EntryChoice::Agent { class: "a.B".into() }.entry_points()[0]["method"], "premain");
        assert_eq!(EntryChoice::ClinitSweep.entry_points(), json!([{"type": "clinit-sweep"}]));
    }

    #[test]
    fn summaries_read_as_sentences() {
        let mut report: Report = serde_json::from_value(serde_json::json!({
            "schemaVersion": 1, "tool": {"name": "javabyte", "version": "x"},
            "sample": {"path": "x", "sha256": "0", "size": 1, "classCount": 1},
            "runs": [], "summary": {"instructions": 1, "gaps": 0}
        }))
        .unwrap();
        assert_eq!(plain_summary(&report), "The sample did nothing the analysis counts as notable.");
        report.summary.processes = vec!["calc.exe".into(), "cmd.exe".into()];
        report.summary.connections = vec!["a:1".into()];
        // Many requests to one server count once, and match the connection to the same host.
        report.summary.http_requests =
            vec!["http://A:8080/task?n=1".into(), "http://a:8080/task?n=2".into(), "https://user@a/x".into()];
        report.summary.files_written = vec!["f".into()];
        report.summary.gaps = 1;
        assert_eq!(
            plain_summary(&report),
            "The sample tried to start 2 programs, contacted 1 server and wrote 1 file. 1 part could not be emulated \
             faithfully, so the real behaviour may differ there."
        );
    }
}

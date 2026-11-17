//! What the screens show, prepared from the tool's output.
//!
//! The backend builds these from the tool's real output; the browser build used for automated
//! accessibility tests builds them from the committed tool-output samples. Either way the
//! screens only display them. Wording for people lives here so it can be tested in one place.

use crate::inspect::Inspection;
use crate::report::{Report, describe_outcome};
use serde::{Deserialize, Serialize};

/// A sample the person chose, by picker or by dropping it on the window.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SampleChoice {
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
    MainOf { class: String },
    /// A Java agent's `premain` method, called as the JVM would with no options.
    Agent { class: String },
    /// Every class's start-up code, to collect hidden text.
    ClinitSweep,
}

impl EntryChoice {
    /// The `entryPoints` override that selects this choice on the command line.
    pub fn override_value(&self) -> Option<String> {
        match self {
            EntryChoice::Main => None,
            EntryChoice::MainOf { class } => Some(format!("entryPoints=[{{class: '{}'}}]", class.replace('\'', "''"))),
            EntryChoice::Agent { class } => Some(format!(
                "entryPoints=[{{type: method, class: '{}', method: premain, args: ['', null]}}]",
                class.replace('\'', "''")
            )),
            EntryChoice::ClinitSweep => Some("entryPoints=[{type: clinit-sweep}]".into()),
        }
    }
}

/// One way to start the sample, offered on the overview screen.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct EntryOption {
    pub label: String,
    pub help: String,
    pub entry: EntryChoice,
}

/// Something the overview wants the person to know before running.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Finding {
    /// `attention` for things that change what to do next; `info` otherwise. Shown in words,
    /// never by colour alone.
    pub level: String,
    pub title: String,
    pub detail: String,
}

/// Everything the overview screen shows.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Overview {
    pub sample: SampleChoice,
    pub inspection: Inspection,
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
            label: format!("The sample's own starting point ({})", dotted(main)),
            help: "Runs the main method the sample names, as double-clicking it would.".into(),
            entry: EntryChoice::Main,
        }),
        (None, Some(agent)) => {
            findings.push(Finding {
                level: "attention".into(),
                title: "This is a Java agent".into(),
                detail: "It has no main method. It is meant to be loaded into another Java program, which starts it \
                         through its premain method. The analysis can start it the same way."
                    .into(),
            });
            entries.push(EntryOption {
                label: format!("Start it as an agent ({}.premain)", dotted(agent)),
                help: "Calls premain as the Java runtime would, with no options.".into(),
                entry: EntryChoice::Agent { class: dotted(agent) },
            });
        }
        (None, None) => findings.push(Finding {
            level: "attention".into(),
            title: "The sample names no starting point".into(),
            detail: if inspection.main_candidates.is_empty() {
                "No class has a main method. Plugins and mods are started by the program that loads them; collecting \
                 hidden text from every class's start-up code is the best first step."
                    .into()
            } else {
                "Choose a class to start from below. Classes with a main method are listed.".into()
            },
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
            label: format!("Start from {class}"),
            help: "Runs this class's main method.".into(),
            entry: EntryChoice::MainOf { class },
        });
    }
    entries.push(EntryOption {
        label: "Collect hidden text from every class's start-up code".into(),
        help: "Runs only the code each class runs when it is first used. Most obfuscators decrypt their hidden text \
               there, so this often reveals addresses and names without running the program itself."
            .into(),
        entry: EntryChoice::ClinitSweep,
    });

    if inspection.native_method_count > 0 || !inspection.native_libraries.is_empty() {
        findings.push(Finding {
            level: "attention".into(),
            title: "Part of it is native code".into(),
            detail: format!(
                "{} method{} and {} bundled librar{} are machine code, not Java. Without the native bridge their \
                 behaviour is reported but not run.",
                inspection.native_method_count,
                plural(inspection.native_method_count),
                inspection.native_libraries.len(),
                if inspection.native_libraries.len() == 1 { "y" } else { "ies" }
            ),
        });
    }
    if inspection.obfuscated_call_sites > 0 {
        findings.push(Finding {
            level: "info".into(),
            title: "Calls are hidden by an obfuscator".into(),
            detail: format!(
                "{} call{} go through the sample's own lookup code. The analysis follows them as it runs.",
                inspection.obfuscated_call_sites,
                plural(inspection.obfuscated_call_sites)
            ),
        });
    }
    if !inspection.class_loaders.is_empty() {
        findings.push(Finding {
            level: "info".into(),
            title: "It loads code of its own".into(),
            detail: "The sample defines class loaders, a common way to unpack hidden stages. Classes it creates while \
                     running are saved and analysed too."
                .into(),
        });
    }
    if !inspection.unparseable_classes.is_empty() {
        findings.push(Finding {
            level: "info".into(),
            title: "Some files could not be read".into(),
            detail: format!(
                "{} entr{} look like classes but are damaged or decoys. This is common in protected samples.",
                inspection.unparseable_classes.len(),
                if inspection.unparseable_classes.len() == 1 { "y" } else { "ies" }
            ),
        });
    }
    Overview { sample, inspection, findings, entries, other_classes }
}

/// One list of indicators on the report screen.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct IndicatorSection {
    /// Stable id, for headings and links.
    pub id: String,
    pub title: String,
    /// What this list means, in a sentence.
    pub explanation: String,
    /// Untrusted: values from the sample. Pass each through `display_text`.
    pub items: Vec<String>,
}

/// How one entry point ended, for people.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RunLine {
    pub entry: String,
    pub outcome: String,
    pub meaning: String,
    pub detail: Option<String>,
}

/// Everything the report screen shows apart from the event list.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct ReportView {
    pub sample_name: String,
    pub sample_path: String,
    pub sha256: String,
    /// The plain-language paragraph that opens the report.
    pub summary: String,
    pub runs: Vec<RunLine>,
    pub sections: Vec<IndicatorSection>,
    pub event_count: u64,
    pub gaps: u64,
    /// Categories whose events were not all kept, and how many were dropped.
    pub dropped: Vec<(String, u64)>,
    /// Where the full report and saved files are.
    pub output_dir: String,
}

/// Most values kept per indicator list; the full list is in the report file.
const SECTION_LIMIT: usize = 200;

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
        section("processes", "Programs it tried to start", "Command lines. Nothing was really started.", text(&s.processes)),
        section("http", "Web addresses it contacted", "Nothing was really sent; answers came from the settings.", text(&s.http_requests)),
        section("connections", "Network connections", "Host and port of each connection it opened.", text(&s.connections)),
        section("dns", "Host names it looked up", "Often the first sign of its servers.", text(&s.dns_lookups)),
        section("files", "Files it wrote", "On the pretend disk. Copies are saved with the report.", text(&s.files_written)),
        section("classes", "Code it created while running", "Hidden stages unpacked at run time. Saved with the report.", text(&s.classes_defined)),
        section("native", "Native libraries it loaded", "Machine code outside Java.", text(&s.native_libraries)),
        section("decoded", "Text it decoded", "Text returned by its decoding methods: often addresses, keys and commands.", events_of("string", "returned", "value")),
        section("crypto", "Decrypted data", "The start of what its decryption produced.", events_of("crypto", "cipher-output", "preview")),
        section("blocked", "Calls that were refused", "Java features it tried to use that the analysis does not allow.", text(&s.blocked_calls)),
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
                meaning: describe_outcome(&run.outcome).to_string(),
                detail: run.detail.clone(),
            })
            .collect(),
        sections,
        event_count: s.event_counts.values().sum(),
        gaps: s.gaps,
        dropped: s.events_dropped.iter().map(|(k, v)| (k.clone(), *v)).collect(),
        output_dir: output_dir.to_string(),
    }
}

fn section(id: &str, title: &str, explanation: &str, items: Vec<String>) -> IndicatorSection {
    IndicatorSection { id: id.into(), title: title.into(), explanation: explanation.into(), items }
}

/// The paragraph that opens a report: what the sample tried to do, in one or two sentences.
pub fn plain_summary(report: &Report) -> String {
    let s = &report.summary;
    let mut parts = Vec::new();
    let mut add = |count: usize, one: &str, many: &str| {
        if count > 0 {
            parts.push(format!("{} {}", count, if count == 1 { one } else { many }));
        }
    };
    add(s.processes.len(), "program", "programs");
    let started = parts.pop().map(|p| format!("tried to start {p}"));
    let mut actions: Vec<String> = started.into_iter().collect();
    // Distinct hosts, not requests: a beacon loop asks one server many times with different
    // addresses, and "contacted 42 servers" would be wrong.
    let hosts: std::collections::BTreeSet<String> =
        s.http_requests.iter().chain(&s.connections).filter_map(|v| v.as_str()).map(host_of).collect();
    let servers = hosts.len();
    if servers > 0 {
        actions.push(format!("contacted {servers} {}", if servers == 1 { "server" } else { "servers" }));
    }
    if !s.dns_lookups.is_empty() {
        actions.push(format!("looked up {} host {}", s.dns_lookups.len(), if s.dns_lookups.len() == 1 { "name" } else { "names" }));
    }
    if !s.files_written.is_empty() {
        actions.push(format!("wrote {} {}", s.files_written.len(), if s.files_written.len() == 1 { "file" } else { "files" }));
    }
    if !s.classes_defined.is_empty() {
        actions.push(format!(
            "created {} {} of hidden code",
            s.classes_defined.len(),
            if s.classes_defined.len() == 1 { "class" } else { "classes" }
        ));
    }
    if !s.native_libraries.is_empty() {
        actions.push(format!(
            "loaded {} native {}",
            s.native_libraries.len(),
            if s.native_libraries.len() == 1 { "library" } else { "libraries" }
        ));
    }
    let mut summary = if actions.is_empty() {
        "The sample did nothing the analysis counts as notable.".to_string()
    } else {
        format!("The sample {}.", join_list(&actions))
    };
    if s.gaps > 0 {
        summary.push_str(&format!(
            " {} {} could not be emulated faithfully, so the real behaviour may differ there.",
            s.gaps,
            if s.gaps == 1 { "part" } else { "parts" }
        ));
    }
    if !s.blocked_calls.is_empty() {
        summary.push_str(&format!(
            " {} Java {} refused.",
            s.blocked_calls.len(),
            if s.blocked_calls.len() == 1 { "feature it tried to use was" } else { "features it tried to use were" }
        ));
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
        [rest @ .., last] => format!("{} and {last}", rest.join(", ")),
    }
}

fn plural(n: u64) -> &'static str {
    if n == 1 { "" } else { "s" }
}

/// A run in the recent list on the start screen.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RecentRun {
    pub id: String,
    pub sample_name: String,
    /// When it ran, as `YYYY-MM-DD HH:MM` in UTC.
    pub when: String,
    /// The first entry point's outcome, in plain words.
    pub outcome: String,
    pub events: u64,
    pub output_dir: String,
}

/// Whether the analysis engine (Java and the JavaByte tool) can be started.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct EngineStatus {
    pub ready: bool,
    /// Plain words: what is wrong and how to fix it. None when ready.
    pub problem: Option<String>,
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
    fn entry_choices_become_overrides() {
        assert_eq!(EntryChoice::Main.override_value(), None);
        assert_eq!(EntryChoice::MainOf { class: "a.B".into() }.override_value().unwrap(), "entryPoints=[{class: 'a.B'}]");
        assert!(EntryChoice::Agent { class: "a.B".into() }.override_value().unwrap().contains("method: premain"));
        assert_eq!(EntryChoice::ClinitSweep.override_value().unwrap(), "entryPoints=[{type: clinit-sweep}]");
        // A quote in a class name cannot end the YAML string early.
        assert_eq!(EntryChoice::MainOf { class: "a'b".into() }.override_value().unwrap(), "entryPoints=[{class: 'a''b'}]");
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

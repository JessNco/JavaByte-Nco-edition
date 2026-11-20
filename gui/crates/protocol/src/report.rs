//! The report: `report.json`, or `javabyte run --json`.

use crate::stream::{Event, RunResult, Tool};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// The report layout this GUI understands.
pub const SCHEMA_VERSION: u32 = 1;

/// `report.json` as the GUI reads it. Fields the GUI does not use are left out; serde
/// ignores them, so the tool can add fields without breaking the app.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Report {
    /// The report layout's version; see `SUPPORTED_SCHEMA` in the backend.
    pub schema_version: u32,
    /// The tool that wrote the report.
    pub tool: Tool,
    /// What was analysed.
    pub sample: ReportSample,
    /// How each part of the run ended, in order.
    pub runs: Vec<RunResult>,
    /// Counts and indicator lists.
    pub summary: Summary,
    /// Files the run saved, relative to the report's folder, with `/` between names.
    #[serde(default)]
    pub artifacts: Vec<String>,
    /// Every event kept, in order.
    #[serde(default)]
    pub events: Vec<Event>,
    /// The effective config, exactly as used.
    #[serde(default)]
    pub config: serde_json::Value,
}

/// The sample as the report describes it.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReportSample {
    /// The path the tool was given.
    pub path: String,
    /// SHA-256 of the sample file, in lowercase hex.
    pub sha256: String,
    /// Size in bytes.
    pub size: u64,
    /// The manifest's `Main-Class`, when there is one.
    #[serde(default)]
    pub main_class: Option<String>,
    /// Classes in the sample.
    pub class_count: u64,
    /// Where the sample believed it was stored (`environment.samplePath`), if set.
    #[serde(default)]
    pub virtual_path: Option<String>,
}

/// The indicators an analyst looks at first. All values come from the sample and are untrusted.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Summary {
    /// Bytecode instructions run, in all parts.
    pub instructions: u64,
    /// Events recorded, by category.
    #[serde(default)]
    pub event_counts: BTreeMap<String, u64>,
    /// Per category, events counted but not stored because the category was full.
    #[serde(default)]
    pub events_dropped: BTreeMap<String, u64>,
    /// Command lines it tried to run. Untrusted.
    #[serde(default)]
    pub processes: Vec<serde_json::Value>,
    /// Web addresses it requested. Untrusted.
    #[serde(default)]
    pub http_requests: Vec<serde_json::Value>,
    /// Network connections it opened, as `host:port`. Untrusted.
    #[serde(default)]
    pub connections: Vec<serde_json::Value>,
    /// Host names it looked up. Untrusted.
    #[serde(default)]
    pub dns_lookups: Vec<serde_json::Value>,
    /// Paths it wrote on the pretend disk. Untrusted.
    #[serde(default)]
    pub files_written: Vec<serde_json::Value>,
    /// Classes it defined while running. Untrusted.
    #[serde(default)]
    pub classes_defined: Vec<serde_json::Value>,
    /// Native libraries it loaded. Untrusted.
    #[serde(default)]
    pub native_libraries: Vec<serde_json::Value>,
    /// Java features the analysis refused. Untrusted.
    #[serde(default)]
    pub blocked_calls: Vec<serde_json::Value>,
    /// Places the analysis could not imitate faithfully.
    pub gaps: u64,
}

/// What a run's outcome means, in plain words, for people who have not read the manual.
pub fn describe_outcome(outcome: &str) -> String {
    const KNOWN: &[&str] = &[
        "completed", "uncaught-exception", "exit", "sleep-budget", "silent-loop", "blocked", "instruction-limit", "timeout",
        "policy", "stopped", "no-entry-point", "config-error", "internal-error",
    ];
    let id = if KNOWN.contains(&outcome) { format!("outcome-{outcome}") } else { "outcome-unknown".into() };
    crate::i18n::tr(&id, None)
}

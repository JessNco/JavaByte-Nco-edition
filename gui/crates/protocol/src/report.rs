//! The report: `report.json`, or `javabyte run --json`.

use crate::stream::{Event, RunResult, Tool};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// The report layout this GUI understands.
pub const SCHEMA_VERSION: u32 = 1;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Report {
    pub schema_version: u32,
    pub tool: Tool,
    pub sample: ReportSample,
    pub runs: Vec<RunResult>,
    pub summary: Summary,
    #[serde(default)]
    pub artifacts: Vec<String>,
    #[serde(default)]
    pub events: Vec<Event>,
    /// The effective config, exactly as used.
    #[serde(default)]
    pub config: serde_json::Value,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReportSample {
    pub path: String,
    pub sha256: String,
    pub size: u64,
    #[serde(default)]
    pub main_class: Option<String>,
    pub class_count: u64,
    #[serde(default)]
    pub virtual_path: Option<String>,
}

/// The indicators an analyst looks at first. All values come from the sample and are untrusted.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Summary {
    pub instructions: u64,
    #[serde(default)]
    pub event_counts: BTreeMap<String, u64>,
    /// Per category, events counted but not stored because the category was full.
    #[serde(default)]
    pub events_dropped: BTreeMap<String, u64>,
    #[serde(default)]
    pub processes: Vec<serde_json::Value>,
    #[serde(default)]
    pub http_requests: Vec<serde_json::Value>,
    #[serde(default)]
    pub connections: Vec<serde_json::Value>,
    #[serde(default)]
    pub dns_lookups: Vec<serde_json::Value>,
    #[serde(default)]
    pub files_written: Vec<serde_json::Value>,
    #[serde(default)]
    pub classes_defined: Vec<serde_json::Value>,
    #[serde(default)]
    pub native_libraries: Vec<serde_json::Value>,
    #[serde(default)]
    pub blocked_calls: Vec<serde_json::Value>,
    pub gaps: u64,
}

/// What a run's outcome means, in plain words, for people who have not read the manual.
pub fn describe_outcome(outcome: &str) -> &'static str {
    match outcome {
        "completed" => "The sample ran to its end.",
        "uncaught-exception" => "The sample stopped with an error it did not handle.",
        "exit" => "The sample ended itself.",
        "sleep-budget" => "The sample kept waiting, so the analysis moved on. It was probably waiting for an answer from a server.",
        "blocked" => "The sample waited for something that would never happen, so the analysis moved on.",
        "instruction-limit" => "The analysis reached its instruction limit before the sample finished.",
        "timeout" => "The analysis reached its time limit before the sample finished.",
        "policy" => "The analysis stopped the sample because of a safety rule.",
        "stopped" => "You stopped the analysis. Everything found until then is kept.",
        "no-entry-point" => "There was nowhere to start: the sample names no main class. Choose where to start in the settings.",
        "config-error" => "The settings have a problem; nothing was run.",
        "internal-error" => "The analysis tool itself failed. This is a fault in the tool, not something the sample did.",
        _ => "The analysis ended in a way this version of the app does not know.",
    }
}

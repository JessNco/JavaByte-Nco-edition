//! The live event stream: `javabyte run --events jsonl`, one record per line.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// The stream version this GUI understands. A different version is refused with a clear message.
pub const STREAM_VERSION: u32 = 1;

/// One line of the stream.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum StreamRecord {
    Start(Start),
    Event(Event),
    Progress(Progress),
    /// One entry point, or the queued work after it, has ended.
    Entry(RunResult),
    Finish(Box<Finish>),
    /// A record type added by a newer tool. Ignored rather than treated as an error.
    #[serde(other)]
    Unknown,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Start {
    pub stream_version: u32,
    pub tool: Tool,
    pub sample: StreamSample,
    pub output: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Tool {
    pub name: String,
    pub version: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StreamSample {
    pub path: String,
    pub sha256: String,
    pub size: u64,
    #[serde(default)]
    pub main_class: Option<String>,
}

/// One thing the sample did. The same shape as an entry in the report's `events`.
///
/// Every string here except `category` and `action` comes from the sample and is untrusted.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Event {
    pub seq: u64,
    pub category: String,
    pub action: String,
    #[serde(default)]
    pub caller: Option<String>,
    #[serde(default)]
    pub data: serde_json::Map<String, serde_json::Value>,
}

impl Event {
    /// `key=value` pairs, as the tool's `trace.log` writes them.
    pub fn details(&self) -> String {
        let mut text = String::new();
        for (key, value) in &self.data {
            if !text.is_empty() {
                text.push(' ');
            }
            text.push_str(key);
            text.push('=');
            match value {
                serde_json::Value::String(s) => text.push_str(s),
                other => text.push_str(&other.to_string()),
            }
        }
        text
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Progress {
    pub elapsed_ms: u64,
    pub instructions: u64,
    pub entry: String,
    pub events: u64,
    #[serde(default)]
    pub event_counts: BTreeMap<String, u64>,
}

/// How one entry point ended. The same shape as an entry in the report's `runs`.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct RunResult {
    pub entry: String,
    pub outcome: String,
    #[serde(default)]
    pub detail: Option<String>,
    pub instructions: u64,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Finish {
    pub exit_code: i32,
    pub report: String,
    pub runs: Vec<RunResult>,
    pub summary: crate::report::Summary,
}

/// Reads one line of the stream.
pub fn parse_line(line: &str) -> Result<StreamRecord, String> {
    serde_json::from_str(line).map_err(|e| crate::t!("error-unreadable-line", detail = e.to_string()))
}

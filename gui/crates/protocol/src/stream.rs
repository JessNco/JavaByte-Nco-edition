//! The live event stream: `javabyte run --events jsonl`, one record per line.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// The stream version this GUI understands. A different version is refused with a clear message.
pub const STREAM_VERSION: u32 = 1;

/// One line of the stream.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum StreamRecord {
    /// Sent once, first: the tool, the sample and where results go.
    Start(Start),
    /// One thing the sample did.
    Event(Event),
    /// Counts so far, about every half second (with `--progress`).
    Progress(Progress),
    /// One entry point, or the queued work after it, has ended.
    Entry(RunResult),
    /// Sent once, last: the exit code, how each part ended, and the summary.
    Finish(Box<Finish>),
    /// A record type added by a newer tool. Ignored rather than treated as an error.
    #[serde(other)]
    Unknown,
}

/// The stream's first record.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Start {
    /// Raised only when a reader could break, like the report's `schemaVersion`.
    pub stream_version: u32,
    /// The tool that wrote the stream.
    pub tool: Tool,
    /// What is being analysed.
    pub sample: StreamSample,
    /// The folder the report and saved files go to.
    pub output: String,
}

/// The tool's name and version.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Tool {
    /// Always `javabyte`.
    pub name: String,
    /// The tool's version, such as `0.1.0`.
    pub version: String,
}

/// The sample as the stream's start record describes it.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StreamSample {
    /// The path the tool was given.
    pub path: String,
    /// SHA-256 of the sample file, in lowercase hex.
    pub sha256: String,
    /// Size in bytes.
    pub size: u64,
    /// The manifest's `Main-Class`, with dots; `None` when there is none.
    #[serde(default)]
    pub main_class: Option<String>,
}

/// One thing the sample did. The same shape as an entry in the report's `events`.
///
/// Every string here except `category` and `action` comes from the sample and is untrusted.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Event {
    /// The event's number: position in the run, from 0, never reused.
    pub seq: u64,
    /// The kind of behaviour: `process`, `net`, `file`, `class`, `string`, `policy`, `gap`, ...
    pub category: String,
    /// What happened within the category, such as `exec` or `http`.
    pub action: String,
    /// Where in the sample it happened, as `class.method:line`, when known. Untrusted.
    #[serde(default)]
    pub caller: Option<String>,
    /// The event's details, as the tool recorded them. Untrusted.
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

/// A progress record.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Progress {
    /// Milliseconds since the run started.
    pub elapsed_ms: u64,
    /// Bytecode instructions run so far.
    pub instructions: u64,
    /// The entry point running now.
    pub entry: String,
    /// Events recorded so far, in all categories.
    pub events: u64,
    /// Events so far, by category.
    #[serde(default)]
    pub event_counts: BTreeMap<String, u64>,
}

/// How one entry point ended. The same shape as an entry in the report's `runs`.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct RunResult {
    /// The entry point, such as `a.B.main`.
    pub entry: String,
    /// How it ended: an outcome word such as `completed`; see `report::describe_outcome`.
    pub outcome: String,
    /// The tool's explanation, such as the exception, when there is one. Untrusted.
    #[serde(default)]
    pub detail: Option<String>,
    /// Instructions this part ran.
    pub instructions: u64,
}

/// The stream's last record.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Finish {
    /// The tool's exit code: 0 done, 1 tool fault, 2 bad settings, 3 limit reached.
    pub exit_code: i32,
    /// Where the report was written.
    pub report: String,
    /// How each part of the run ended, in order.
    pub runs: Vec<RunResult>,
    /// The report's summary, as in `report.json`.
    pub summary: crate::report::Summary,
}

/// Reads one line of the stream.
pub fn parse_line(line: &str) -> Result<StreamRecord, String> {
    serde_json::from_str(line).map_err(|e| crate::t!("error-unreadable-line", detail = e.to_string()))
}

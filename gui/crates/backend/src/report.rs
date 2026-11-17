//! Reading events from a real `report.json` written by the emulator.

use crate::events::EventSource;
use javabyte_gui_protocol::EventRow;
use serde::Deserialize;
use std::io::Read;

/// The parts of the report the event list needs. Every other field of the report is skipped
/// while parsing, without being kept in memory.
#[derive(Deserialize)]
struct ReportEvents {
    #[serde(rename = "schemaVersion")]
    schema_version: u32,
    events: Vec<RawEvent>,
}

#[derive(Deserialize)]
struct RawEvent {
    category: String,
    action: String,
    #[serde(default)]
    caller: Option<String>,
    #[serde(default)]
    data: serde_json::Map<String, serde_json::Value>,
}

/// The report layout this GUI understands. A report with another version is refused with a
/// clear message rather than shown wrongly.
pub const SUPPORTED_SCHEMA: u32 = 1;

pub struct ReportEventsSource {
    rows: Vec<EventRow>,
}

impl ReportEventsSource {
    pub fn read(reader: impl Read) -> Result<Self, String> {
        let report: ReportEvents =
            serde_json::from_reader(std::io::BufReader::new(reader)).map_err(|e| format!("This is not a JavaByte report: {e}"))?;
        if report.schema_version != SUPPORTED_SCHEMA {
            return Err(format!(
                "This report uses layout version {}, but this version of the app reads version {SUPPORTED_SCHEMA}.",
                report.schema_version
            ));
        }
        let rows = report
            .events
            .into_iter()
            .enumerate()
            .map(|(index, event)| EventRow {
                seq: index as u64,
                category: event.category,
                action: event.action,
                details: details(&event.data),
                caller: event.caller,
            })
            .collect();
        Ok(Self { rows })
    }
}

/// `key=value` pairs, in the order the emulator wrote them, as in its `trace.log`.
fn details(data: &serde_json::Map<String, serde_json::Value>) -> String {
    let mut text = String::new();
    for (key, value) in data {
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

impl EventSource for ReportEventsSource {
    fn len(&self) -> u64 {
        self.rows.len() as u64
    }

    fn row(&self, index: u64) -> EventRow {
        self.rows[index as usize].clone()
    }

    fn category(&self, index: u64) -> &str {
        &self.rows[index as usize].category
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_events_and_ignores_other_fields() {
        let json = r#"{"schemaVersion":1,"tool":{"name":"javabyte"},"runs":[],"events":[
            {"seq":0,"category":"process","action":"exec","caller":"a/B.main:3","data":{"command":"calc.exe","exitCode":0}},
            {"seq":1,"category":"net","action":"dns","data":{"host":"x.test"}}],"config":{}}"#;
        let source = ReportEventsSource::read(json.as_bytes()).unwrap();
        assert_eq!(source.len(), 2);
        assert_eq!(source.row(0).details, "command=calc.exe exitCode=0");
        assert_eq!(source.row(0).caller.as_deref(), Some("a/B.main:3"));
        assert_eq!(source.category(1), "net");
    }

    #[test]
    fn refuses_other_layouts_and_other_files() {
        let newer = ReportEventsSource::read(r#"{"schemaVersion":2,"events":[]}"#.as_bytes());
        assert!(newer.err().unwrap().contains("version 2"));
        assert!(ReportEventsSource::read("PK\u{3}\u{4}".as_bytes()).is_err());
    }
}

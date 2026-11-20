//! Reads real output of the command-line tool with this crate's types.
//!
//! The files in `tests/tool-output/` are written by the Java test `ContractSamplesTest` from
//! harmless fixtures, and that test fails if they no longer match the tool's output. These
//! tests fail if the GUI's types can no longer read them. Together they stop the two sides
//! drifting apart.

use javabyte_gui_protocol::inspect::Inspection;
use javabyte_gui_protocol::report::{Report, SCHEMA_VERSION, describe_outcome};
use javabyte_gui_protocol::schema::Schema;
use javabyte_gui_protocol::stream::{STREAM_VERSION, StreamRecord, parse_line};
use javabyte_gui_protocol::tools::{PolicyListing, default_rows};
use javabyte_gui_protocol::validate::Validation;

fn sample(name: &str) -> String {
    std::fs::read_to_string(format!("{}/tests/tool-output/{name}", env!("CARGO_MANIFEST_DIR")))
        .unwrap_or_else(|e| panic!("{name}: {e}"))
}

#[test]
fn event_stream_reads_and_agrees_with_the_report() {
    let records: Vec<StreamRecord> = sample("stream-basics.jsonl").lines().map(|l| parse_line(l).unwrap()).collect();
    let StreamRecord::Start(start) = &records[0] else { panic!("first record must be start: {:?}", records[0]) };
    assert_eq!(start.stream_version, STREAM_VERSION);
    assert_eq!(start.sample.main_class.as_deref(), Some("dev.javabyte.guest.Basics"));
    let StreamRecord::Finish(finish) = records.last().unwrap() else { panic!("last record must be finish") };
    assert_eq!(finish.exit_code, 0);

    let events: Vec<_> = records.iter().filter_map(|r| if let StreamRecord::Event(e) = r { Some(e) } else { None }).collect();
    let entries: Vec<_> = records.iter().filter_map(|r| if let StreamRecord::Entry(e) = r { Some(e) } else { None }).collect();
    let progress = records.iter().find_map(|r| if let StreamRecord::Progress(p) = r { Some(p) } else { None }).unwrap();
    assert!(!events.is_empty());
    assert!(events.windows(2).all(|pair| pair[0].seq < pair[1].seq), "events arrive in order");
    assert_eq!(progress.events, events.len() as u64);

    let report: Report = serde_json::from_str(&sample("report-basics.json")).unwrap();
    assert_eq!(report.schema_version, SCHEMA_VERSION);
    assert_eq!(report.events.len(), events.len(), "the stream carries every stored event");
    assert_eq!(report.events.iter().collect::<Vec<_>>(), events);
    assert_eq!(report.runs, finish.runs);
    assert_eq!(entries.into_iter().cloned().collect::<Vec<_>>(), report.runs);
    assert_eq!(report.summary, finish.summary);
    assert!(events[1].details().starts_with("text="), "details render as key=value: {}", events[1].details());
}

#[test]
fn unknown_record_types_are_ignored_not_errors() {
    assert_eq!(parse_line(r#"{"type":"something-new","x":1}"#).unwrap(), StreamRecord::Unknown);
    assert!(parse_line("not json").is_err());
}

#[test]
fn inspection_reads() {
    let inspection: Inspection = serde_json::from_str(&sample("inspect-native.json")).unwrap();
    assert_eq!(inspection.main_class.as_deref(), Some("dev.javabyte.guest.NativeUser"));
    assert_eq!(inspection.native_method_count, 1);
    let library = &inspection.native_libraries["native/testlib.dll"];
    assert_eq!(library.format, "PE");
    assert!(library.has_jni_on_load);
}

#[test]
fn schema_reads_and_describes_every_option() {
    let schema: Schema = serde_json::from_str(&sample("schema.json")).unwrap();
    assert!(schema.options.len() > 50);
    assert!(schema.options.iter().all(|o| !o.description.is_empty()));
    let os = schema.option("environment.os").unwrap();
    assert_eq!(os.allowed, ["windows", "linux"]);
    assert_eq!(schema.option("limits.timeoutSeconds").unwrap().default, Some(serde_json::json!(120)));
    assert_eq!(schema.option("network.http").unwrap().element.as_deref(), Some("object"));
    let bridge = schema.plugins.iter().find(|p| p.id == "native-bridge").expect("the native bridge is described");
    assert!(!bridge.description.is_empty());
    assert_eq!(bridge.settings[0].path, "python");
}

#[test]
fn validation_reads() {
    let validation: Validation = serde_json::from_str(&sample("validate-problems.json")).unwrap();
    assert!(!validation.valid);
    let keys: Vec<_> = validation.problems.iter().map(|p| p.key.as_str()).collect();
    assert_eq!(keys, ["limitz", "limits.timeoutSeconds", "network.http[0].url"]);
}

#[test]
fn every_outcome_has_plain_words() {
    for outcome in ["completed", "uncaught-exception", "exit", "sleep-budget", "silent-loop", "blocked", "instruction-limit", "timeout", "policy",
        "stopped", "no-entry-point", "config-error", "internal-error"] {
        assert!(!describe_outcome(outcome).contains("does not know"), "{outcome}");
    }
}

#[test]
fn the_policy_and_the_defaults_read() {
    let policy: PolicyListing = serde_json::from_str(&sample("policy.json")).unwrap();
    assert!(policy.rules.len() > 100, "{} rules", policy.rules.len());
    assert!(policy.rules.iter().any(|r| r.rule == "java/lang/String" && r.allow));
    let timer = policy.rules.iter().find(|r| r.rule == "java/util/Timer").unwrap();
    assert!(!timer.allow);
    assert!(timer.matches("starts threads"));

    let schema: Schema = serde_json::from_str(&sample("schema.json")).unwrap();
    let rows = default_rows(&schema);
    let timeout = rows.iter().find(|r| r.path == "limits.timeoutSeconds").unwrap();
    assert_eq!(timeout.default, "120");
    assert!(rows.iter().any(|r| r.path == "plugins.native-bridge.python"), "plugin settings are listed too");
    assert!(rows.iter().filter(|r| r.matches("timeout")).count() >= 1);
}

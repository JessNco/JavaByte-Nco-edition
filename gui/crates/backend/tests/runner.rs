//! Drives the real command-line tool through the runner, on harmless test fixtures only.
//!
//! Needs the built tool and the compiled Java test classes (`mvn package` at the repository
//! root). Set `JAVABYTE_JAR` to the jar and `JAVABYTE_TEST_CLASSES` to `target/test-classes`; CI
//! does. Without them the tests say so and pass, unless `JAVABYTE_RUNNER_TESTS_REQUIRED` is set.

use javabyte_gui_backend::runner::{Launcher, RunRequest, exit_code};
use javabyte_gui_protocol::stream::StreamRecord;
use std::path::PathBuf;
use std::time::Duration;

fn setup() -> Option<(Launcher, PathBuf)> {
    let jar = std::env::var_os("JAVABYTE_JAR");
    let classes = std::env::var_os("JAVABYTE_TEST_CLASSES");
    match (jar, classes) {
        (Some(jar), Some(classes)) => Some((Launcher::find(jar).expect("JAVABYTE_JAR"), PathBuf::from(classes))),
        _ if std::env::var_os("JAVABYTE_RUNNER_TESTS_REQUIRED").is_some() => {
            panic!("JAVABYTE_JAR and JAVABYTE_TEST_CLASSES must be set")
        }
        _ => {
            eprintln!("skipped: set JAVABYTE_JAR and JAVABYTE_TEST_CLASSES to run the runner tests");
            None
        }
    }
}

fn output(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("javabyte-runner-test-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    dir
}

/// The fixtures run from the folder of compiled test classes, with the class named.
fn request(classes: &PathBuf, class: &str, args: &[&str], name: &str) -> RunRequest {
    let args = args.iter().map(|a| format!("'{a}'")).collect::<Vec<_>>().join(", ");
    RunRequest {
        sample: classes.clone(),
        output: output(name),
        config: None,
        overrides: vec![format!("entryPoints=[{{class: {class}, args: [{args}]}}]"), "limits.timeoutSeconds=60".into()],
    }
}

#[test]
fn a_run_streams_from_start_to_finish() {
    let Some((launcher, classes)) = setup() else { return };
    let request = request(&classes, "dev.javabyte.guest.Basics", &[], "basics");
    let ended = launcher.start(&request).unwrap().wait(Duration::from_secs(60), Duration::from_secs(10));
    assert_eq!(ended.exit_code, Some(exit_code::OK), "{}", ended.stderr);
    let tally = &ended.tally;
    assert!(tally.unreadable.is_empty(), "{:?}", tally.unreadable);
    assert_eq!(tally.start.as_ref().unwrap().stream_version, javabyte_gui_protocol::stream::STREAM_VERSION);
    let finish = tally.finish.as_ref().expect("finish record");
    assert_eq!(finish.exit_code, exit_code::OK);
    assert_eq!(tally.events, finish.summary.event_counts.values().sum::<u64>(), "every event arrived");
    assert_eq!(tally.entries, finish.runs);
    assert!(std::path::Path::new(&finish.report).is_file());
    assert!(ended.summary.starts_with("The sample ran to its end."), "{}", ended.summary);
}

#[test]
fn stop_ends_a_run_and_keeps_the_report() {
    let Some((launcher, classes)) = setup() else { return };
    let mut request = request(&classes, "dev.javabyte.guest.sim.Limits", &["spin"], "stop");
    request.overrides.push("limits.maxInstructions=100000000000".into());
    let mut run = launcher.start(&request).unwrap();
    // Wait for the first progress record, so the stop arrives mid-run.
    let mut saw_progress = false;
    for _ in 0..200 {
        if let Some(StreamRecord::Progress(_)) = run.next(Duration::from_millis(100)) {
            saw_progress = true;
            break;
        }
    }
    assert!(saw_progress, "a spinning run reports progress");
    run.stop().unwrap();
    let ended = run.wait(Duration::from_secs(30), Duration::from_secs(10));
    assert!(!ended.forced, "the run should stop when asked: {}", ended.stderr);
    assert_eq!(ended.exit_code, Some(exit_code::OK));
    assert_eq!(ended.tally.entries[0].outcome, "stopped");
    assert!(ended.summary.starts_with("You stopped the analysis."), "{}", ended.summary);
    assert!(ended.tally.by_category.contains_key("process"), "events before the stop are kept");
}

#[test]
fn dropping_a_run_ends_the_process() {
    let Some((launcher, classes)) = setup() else { return };
    let mut request = request(&classes, "dev.javabyte.guest.sim.Limits", &["spin"], "drop");
    request.overrides.push("limits.maxInstructions=100000000000".into());
    let run = launcher.start(&request).unwrap();
    drop(run);
    // Nothing to assert beyond not hanging: Drop kills and reaps the child.
}

#[test]
fn bad_settings_are_reported_not_run() {
    let Some((launcher, classes)) = setup() else { return };
    let mut request = request(&classes, "dev.javabyte.guest.Basics", &[], "bad");
    request.overrides.push("limits.timeoutSeconds=0".into());
    let ended = launcher.start(&request).unwrap().wait(Duration::from_secs(60), Duration::from_secs(10));
    assert_eq!(ended.exit_code, Some(exit_code::BAD_SETTINGS));
    assert!(ended.tally.finish.is_none());
    assert!(ended.summary.contains("settings have a problem"), "{}", ended.summary);
}

#[test]
fn inspect_schema_and_validate() {
    let Some((launcher, classes)) = setup() else { return };
    let inspection = launcher.inspect(&classes).unwrap();
    assert!(inspection.class_count > 10);
    assert!(inspection.main_candidates.iter().any(|c| c.ends_with("guest/Basics")));

    let schema = launcher.schema().unwrap();
    assert!(schema.option("limits.timeoutSeconds").is_some());

    let invalid = launcher.validate(None, &["limits.maxSleep=3".into(), "environment.os=macos".into()]).unwrap();
    assert!(!invalid.valid);
    assert_eq!(invalid.problems.len(), 2);
    assert!(invalid.problems[0].message.contains("did you mean"), "{:?}", invalid.problems);
    assert!(launcher.validate(None, &[]).unwrap().valid);
}

#[test]
fn class_files_are_listed_as_instructions() {
    let Some((launcher, classes)) = setup() else { return };
    let class_file = classes.join("dev/javabyte/guest/Basics.class");
    let text = launcher.disasm(&class_file).unwrap();
    assert!(text.contains("class dev/javabyte/guest/Basics"), "{text}");
    let not_a_class = launcher.disasm(&classes.join("dev/javabyte/guest")).unwrap_err();
    assert!(not_a_class.contains("could not be shown"), "{not_a_class}");
}

//! Drives the real command-line tool through the runner, on harmless test fixtures only.
//!
//! Needs the built tool and the compiled Java test classes (`mvn package` at the repository
//! root). Set `JAVABYTE_JAR` to the jar and `JAVABYTE_TEST_CLASSES` to `target/test-classes`; CI
//! does. Without them the tests say so and pass, unless `JAVABYTE_RUNNER_TESTS_REQUIRED` is set.

use javabyte_gui_backend::runner::{Launcher, RunRequest, exit_code};
use javabyte_gui_protocol::stream::StreamRecord;
use std::path::{Path, PathBuf};
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
fn request(classes: &Path, class: &str, args: &[&str], name: &str) -> RunRequest {
    let args = args.iter().map(|a| format!("'{a}'")).collect::<Vec<_>>().join(", ");
    RunRequest {
        sample: classes.to_path_buf(),
        output: output(name),
        config: None,
        overrides: vec![format!("entryPoints=[{{class: {class}, args: [{args}]}}]"), "limits.timeoutSeconds=60".into()],
        pin_sample: false,
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

    let policy = launcher.policy().unwrap();
    assert!(policy.rules.iter().any(|r| r.rule == "java/lang/String" && r.allow));
    assert!(policy.rules.iter().any(|r| !r.allow), "the policy has blocking rules");

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

/// With isolation on, a run starts in a namespace with no network, and still streams its
/// events and writes its report and files where the person can read them. Skipped where the
/// system does not allow namespaces (the app then asks before running).
/// Sample names from a security audit, all legal on NTFS and most of them invisible: a `'`, a
/// byte order mark, a line separator, a C1 control.
#[cfg(unix)]
const AWKWARD_NAMES: [&str; 4] = ["it's", "in\u{feff}voice", "a\u{2028}b", "a\u{85}b"];

/// A Windows Sandbox run hands the sandbox add-on the sample to copy in, and names it in the
/// tool's arguments only as the sandbox will see it. The add-on here is a script that records
/// its arguments, so this is the command the app really starts.
#[cfg(unix)]
#[test]
fn a_windows_sandbox_run_keeps_an_awkward_sample_name_out_of_the_tools_arguments() {
    use std::os::unix::fs::PermissionsExt;
    let dir = output("sandbox-args");
    std::fs::create_dir_all(&dir).unwrap();
    let helper = dir.join("javabyte-sandbox");
    std::fs::write(&helper, "#!/bin/sh\nfor a; do printf '%s\\0' \"$a\"; done > \"$0.args\"\n").unwrap();
    std::fs::set_permissions(&helper, std::fs::Permissions::from_mode(0o755)).unwrap();
    let mut launcher = Launcher::new("/opt/jb/runtime/bin/java", "/opt/jb/tool/javabyte.jar");
    launcher.isolation = javabyte_gui_backend::runner::RunIsolation::WindowsSandbox(helper.clone());

    for name in AWKWARD_NAMES {
        let sample = dir.join("o'brien\u{feff}").join(format!("{name}.jar"));
        let request = RunRequest { sample: sample.clone(), output: dir.join("run"), config: None, overrides: vec![], pin_sample: true };
        // Another test starting a process at the moment the script was written can hold it open
        // for writing for an instant, and a file open for writing cannot be started.
        let run = (0..50).find_map(|attempt| {
            std::thread::sleep(Duration::from_millis(20 * attempt));
            launcher.start(&request).ok()
        });
        let ended = run.expect("the stand-in add-on starts").wait(Duration::from_secs(20), Duration::from_secs(5));
        assert_eq!(ended.exit_code, Some(0), "{}", ended.stderr);
        let recorded = std::fs::read(dir.join("javabyte-sandbox.args")).unwrap();
        let args: Vec<String> = recorded.split(|b| *b == 0).filter(|a| !a.is_empty()).map(|a| String::from_utf8(a.to_vec()).unwrap()).collect();
        let split = args.iter().position(|a| a == "--").expect("the program follows --");
        let (helper_args, program) = (&args[..split], &args[split + 1..]);
        assert!(helper_args.windows(2).any(|w| w[0] == "--stage" && Path::new(&w[1]) == sample), "{helper_args:?}");

        let staged = format!("C:\\JavaByteStage\\{}.jar", name.replace(|c: char| !c.is_ascii_alphanumeric(), "_"));
        assert_eq!(program[3..5], ["run".to_string(), staged.clone()], "{program:?}");
        assert_eq!(program.last().unwrap(), &format!("sample='{staged}'"), "{program:?}");
        // Nothing of the name the author chose is left for the add-on to rewrite or refuse.
        let folder = dir.join("o'brien\u{feff}").to_string_lossy().into_owned();
        assert!(!program.iter().any(|a| a.contains(&folder) || a.contains(['"', '%', '\u{feff}', '\u{2028}', '\u{85}'])), "{program:?}");
    }
    let _ = std::fs::remove_dir_all(&dir);
}

/// The same names without Windows Sandbox, where the tool gets the real path as a YAML value:
/// the tool must read back exactly the path it was given.
#[cfg(unix)]
#[test]
fn a_sample_with_an_awkward_name_runs() {
    let Some((launcher, classes)) = setup() else { return };
    // Java can only open a name outside ASCII when the locale's encoding has the characters.
    let utf8 = ["LC_ALL", "LC_CTYPE", "LANG"].iter().find_map(|v| std::env::var(v).ok().filter(|l| !l.is_empty())).is_some_and(|l| l.to_ascii_lowercase().replace('-', "").contains("utf8"));
    for (n, name) in AWKWARD_NAMES.iter().enumerate() {
        if !utf8 && !name.is_ascii() {
            eprintln!("skipped {name:?}: the locale is not UTF-8");
            continue;
        }
        let dir = output(&format!("awkward-{n}"));
        let sample = dir.join(name);
        std::fs::create_dir_all(sample.join("dev/javabyte/guest")).unwrap();
        for entry in std::fs::read_dir(classes.join("dev/javabyte/guest")).unwrap().flatten() {
            if entry.file_name().to_string_lossy().starts_with("Basics") {
                std::fs::copy(entry.path(), sample.join("dev/javabyte/guest").join(entry.file_name())).unwrap();
            }
        }
        let mut request = request(&sample, "dev.javabyte.guest.Basics", &[], &format!("awkward-{n}-run"));
        request.pin_sample = true;
        let ended = launcher.start(&request).unwrap().wait(Duration::from_secs(60), Duration::from_secs(10));
        assert_eq!(ended.exit_code, Some(exit_code::OK), "{name:?}: {}", ended.stderr);
        assert_eq!(ended.tally.entries.first().map(|e| e.outcome.as_str()), Some("completed"), "{name:?}: {:?}", ended.tally.entries);
        let _ = std::fs::remove_dir_all(&dir);
    }
}

#[cfg(target_os = "linux")]
#[test]
fn an_isolated_run_works_and_writes_its_report() {
    let Some((mut launcher, classes)) = setup() else { return };
    if let Err(e) = javabyte_gui_backend::isolation::check() {
        eprintln!("skipped: isolation is not available here: {e}");
        return;
    }
    launcher.isolation = javabyte_gui_backend::runner::RunIsolation::Namespace;
    let request = request(&classes, "dev.javabyte.guest.Basics", &[], "isolated");
    let ended = launcher.start(&request).unwrap().wait(Duration::from_secs(60), Duration::from_secs(10));
    assert_eq!(ended.exit_code, Some(exit_code::OK), "{}", ended.stderr);
    let finish = ended.tally.finish.as_ref().expect("finish record");
    let report = std::fs::metadata(&finish.report).expect("report written");
    // Written as the person, not as a user that exists only inside the namespace.
    use std::os::unix::fs::MetadataExt;
    // SAFETY: getuid cannot fail.
    assert_eq!(report.uid(), unsafe { libc::getuid() });
}

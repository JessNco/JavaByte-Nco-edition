//! The session against the real tool: choose, inspect, run, report, recent list. Harmless
//! fixtures only; needs JAVABYTE_JAR and JAVABYTE_TEST_CLASSES like tests/runner.rs.

use javabyte_gui_backend::runner::Launcher;
use javabyte_gui_backend::session::Session;
use javabyte_gui_backend::config::{self, ConfigDoc, ConfigPath};
use javabyte_gui_protocol::views::EntryChoice;
use javabyte_gui_protocol::{PageRequest, RunState};
use std::path::PathBuf;
use std::time::{Duration, Instant};

fn session(name: &str) -> Option<(Session, PathBuf)> {
    let (Some(jar), Some(classes)) = (std::env::var_os("JAVABYTE_JAR"), std::env::var_os("JAVABYTE_TEST_CLASSES")) else {
        assert!(std::env::var_os("JAVABYTE_RUNNER_TESTS_REQUIRED").is_none(), "JAVABYTE_JAR and JAVABYTE_TEST_CLASSES must be set");
        eprintln!("skipped: set JAVABYTE_JAR and JAVABYTE_TEST_CLASSES");
        return None;
    };
    let dir = std::env::temp_dir().join(format!("javabyte-session-test-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    Some((Session::new(Launcher::find(jar), dir.join("runs"), dir.join("recent.json")), PathBuf::from(classes)))
}

fn wait_until_finished(session: &mut Session) -> javabyte_gui_protocol::RunStatus {
    let deadline = Instant::now() + Duration::from_secs(90);
    loop {
        let status = session.status();
        if status.state == RunState::Finished || Instant::now() > deadline {
            return status;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}

#[test]
fn choose_inspect_run_and_open_the_report() {
    let Some((mut session, classes)) = session("flow") else { return };
    assert!(session.engine().ready);
    let sample = Session::choose(&classes).unwrap();
    let overview = session.inspect(sample.clone()).unwrap();
    // A folder of classes has no manifest: the overview says so and offers classes to start from.
    assert!(overview.findings.iter().any(|f| f.title == "The sample names no starting point"), "{:?}", overview.findings);
    let wanted = "dev.javabyte.guest.sim.HttpRat";
    let offered = overview.entries.iter().any(|e| e.entry == EntryChoice::MainOf { class: wanted.into() })
        || overview.other_classes.iter().any(|c| c == wanted);
    assert!(offered, "every class with a main method is offered, as an option or in the list");
    // Settings start from the tool's suggestions for this sample, without an output folder of
    // their own (each run gets one).
    let text = session.settings_text().expect("settings start with the sample");
    assert!(text.starts_with("# JavaByte config"), "{text}");
    assert!(!text.contains("directory:"), "{text}");
    choose_entry(&mut session, &EntryChoice::MainOf { class: wanted.into() });

    session.start().unwrap();
    assert!(session.start().is_err(), "one analysis at a time");
    let status = wait_until_finished(&mut session);
    assert_eq!(status.state, RunState::Finished);
    // With no answers configured the fixture polls its server until the sleep budget ends it.
    assert!(status.ended.as_deref().unwrap().starts_with("The sample kept waiting"), "{status:?}");
    assert!(status.report_ready);
    assert!(status.notable.iter().any(|e| e.category == "net"), "network events are shown live: {:?}", status.notable);

    let report = session.report().unwrap();
    assert_eq!(report.runs[0].outcome, "sleep-budget");
    assert!(report.summary.starts_with("The sample contacted 1 server"), "{}", report.summary);
    let http = report.sections.iter().find(|s| s.id == "http").expect("web addresses listed");
    assert!(http.items.iter().any(|u| u.contains("c2.rat.test")), "{:?}", http.items);
    assert!(
        report.follow_ups.unanswered_urls.iter().any(|u| u.contains("c2.rat.test")),
        "addresses no rule answered are offered for the next run: {:?}",
        report.follow_ups
    );
    let saved = std::fs::read_to_string(std::path::Path::new(&report.output_dir).join("config.yaml")).unwrap();
    assert!(saved.contains("dev.javabyte.guest.sim.HttpRat"), "the run's settings are kept beside its report:\n{saved}");
    let page = session.events_page(&PageRequest { offset: 0, limit: 50, ..Default::default() });
    assert_eq!(page.total, report.event_count);

    let recent = session.recent();
    assert_eq!(recent.len(), 1);
    assert_eq!(recent[0].sample_name, sample.name);
    let reopened = session.open_recent(&recent[0].id).unwrap();
    assert_eq!(reopened.summary, report.summary);
}

#[test]
fn stopping_from_the_session() {
    let Some((mut session, classes)) = session("stop") else { return };
    let sample = Session::choose(&classes).unwrap();
    session.inspect(sample).unwrap();
    // A run that sleeps in a loop, so there is time to stop it.
    choose_entry(&mut session, &EntryChoice::MainOf { class: "dev.javabyte.guest.sim.Background".into() });
    session.start().unwrap();
    std::thread::sleep(Duration::from_millis(300));
    session.stop().unwrap();
    let status = wait_until_finished(&mut session);
    assert_eq!(status.state, RunState::Finished);
}

/// What the overview does when a starting point is chosen: sets `entryPoints` in the settings.
fn choose_entry(session: &mut Session, entry: &EntryChoice) {
    let mut doc = ConfigDoc::new(session.settings_text().unwrap());
    doc.set(&ConfigPath::parse("entryPoints"), &entry.entry_points()).unwrap();
    session.set_settings_text(doc.text().to_string());
}

#[test]
fn checking_settings_lists_every_problem() {
    let Some((mut session, classes)) = session("check") else { return };
    session.inspect(Session::choose(&classes).unwrap()).unwrap();
    let good = session.check(&session.settings_text().unwrap()).unwrap();
    assert!(good.valid, "the suggested settings are valid: {:?}", good.problems);
    let bad = session.check("limits:\n  maxSleeps: lots\nnetwrk:\n  mode: offline\n").unwrap();
    assert!(!bad.valid);
    let keys: Vec<_> = bad.problems.iter().map(|p| p.key.as_str()).collect();
    assert!(keys.contains(&"limits.maxSleeps") && keys.contains(&"netwrk"), "{:?}", bad.problems);
}

#[test]
fn presets_round_trip_without_their_sample() {
    let Some((mut session, classes)) = session("presets") else { return };
    session.inspect(Session::choose(&classes).unwrap()).unwrap();
    let mut doc = ConfigDoc::new(session.settings_text().unwrap());
    doc.set(&ConfigPath::parse("limits.maxSleeps"), &serde_json::json!(7)).unwrap();
    let saved = session.save_preset("Beacon: slow", doc.text()).unwrap();
    assert_eq!(saved.name, "Beacon slow");
    assert_eq!(session.presets().iter().map(|p| p.name.clone()).collect::<Vec<_>>(), vec!["Beacon slow"]);
    let file = std::fs::read_to_string(session.presets_folder().join("Beacon slow.yaml")).unwrap();
    assert!(!file.contains("sample:"), "a preset is for any sample:\n{file}");
    let loaded = session.load_preset("Beacon slow").unwrap();
    let loaded = ConfigDoc::new(loaded);
    assert_eq!(loaded.get(&ConfigPath::parse("limits.maxSleeps")), Some(serde_json::json!(7)));
    assert_eq!(loaded.get(&ConfigPath::parse("sample")), Some(serde_json::json!(classes.display().to_string())));
    session.delete_preset("Beacon slow").unwrap();
    assert!(session.presets().is_empty());
}

/// The plan's equivalence test: settings given as a file and as the copied command line give the
/// tool exactly the same effective config (read back from each run's report).
#[test]
fn the_command_line_is_the_same_analysis() {
    let (Some(jar), Some(classes)) = (std::env::var_os("JAVABYTE_JAR"), std::env::var_os("JAVABYTE_TEST_CLASSES")) else {
        assert!(std::env::var_os("JAVABYTE_RUNNER_TESTS_REQUIRED").is_none(), "JAVABYTE_JAR and JAVABYTE_TEST_CLASSES must be set");
        return;
    };
    let dir = std::env::temp_dir().join(format!("javabyte-equivalence-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    // Touches every section, with values that need quoting and keys with dots.
    let settings = serde_json::json!({
        "entryPoints": [{"type": "main", "class": "dev.javabyte.guest.Basics", "args": ["--flag", "it's", "C:\\x y"]}],
        "limits": {"timeoutSeconds": 30, "maxSleeps": 3, "maxEventsPerCategory": 500},
        "environment": {
            "os": "linux", "userName": "o'brien", "hostName": "lab-01", "samplePath": "/home/x/a b.jar",
            "properties": {"java.version": "17", "custom.flag": "yes"},
            "env": {"APPDATA": "C:\\Users\\x\\AppData"},
            "files": [{"path": "/etc/hosts", "text": "127.0.0.1 a: b # c"}],
            "clockStart": "2024-01-02T03:04:05Z", "randomSeed": 7
        },
        "network": {
            "mode": "respond",
            "http": [{"url": "example\\.com/gate", "status": 404, "headers": {"X-A": "1"}, "body": "OK"}],
            "dns": {"c2.example": "10.0.0.5"}
        },
        "processes": [{"command": "whoami", "stdout": "lab\\o'brien\n", "exitCode": 0}],
        "policy": {"onBlocked": "throw", "deny": ["java/lang/Runtime.exec"]},
        "hooks": [{"method": "a/B.isSandbox()Z", "action": "return", "value": false}],
        "trace": {"strings": "all", "hostCalls": true},
        "threads": {"mode": "inline", "periodicRuns": 2, "runShutdownHooks": false},
        "output": {"console": "quiet", "dumpDroppedFiles": false}
    });
    let config_file = dir.join("config.yaml");
    std::fs::write(&config_file, config::render_document(&settings)).unwrap();
    let run = |out: &str, extra: &[String]| -> serde_json::Value {
        let output = dir.join(out);
        let status = std::process::Command::new(std::env::var_os("JAVABYTE_JAVA").unwrap_or_else(|| "java".into()))
            .arg("-jar")
            .arg(&jar)
            .arg("run")
            .arg(&classes)
            .arg("-o")
            .arg(&output)
            .args(extra)
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .unwrap();
        assert!(status.code().is_some_and(|c| c == 0 || c == 3), "the tool ran ({status})");
        let report: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(output.join("report.json")).unwrap()).unwrap();
        let mut effective = report["config"].clone();
        // Where results went differs by design; everything else must not.
        effective["output"]["directory"] = serde_json::Value::Null;
        effective
    };
    let from_file = run("file", &["-c".into(), config_file.display().to_string()]);
    let mut args = Vec::new();
    for setting in config::overrides(&settings) {
        args.push("-s".to_string());
        args.push(setting);
    }
    let from_command_line = run("command-line", &args);
    assert_eq!(from_file, from_command_line, "the command line must give the same settings as the file\nargs: {args:?}");
    assert_eq!(from_file["environment"]["properties"]["java.version"], "17");
    assert_eq!(from_file["environment"]["userName"], "o'brien");
}

/// The HTTP fixture's full scenario (from the Java corpus tests): canned answers make it download
/// a file and send results back, so the run saves files.
fn http_rat_settings(sample: &str) -> String {
    config::render_document(&serde_json::json!({
        "sample": sample,
        "entryPoints": [{"type": "main", "class": "dev.javabyte.guest.sim.HttpRat"}],
        "network": {"http": [
            {"url": "/task\\?id=\\w+&n=1$", "body": "exec whoami /all"},
            {"url": "/task\\?id=\\w+&n=2$", "body": "download http://cdn.rat.test/stage.bin C:\\Users\\Public\\stage.bin"},
            {"url": "/task\\?id=\\w+&n=3$", "body": "screenshot"},
            {"url": "/task\\?id=\\w+&n=4$", "body": "uninstall"},
            {"url": "cdn\\.rat\\.test/stage\\.bin", "bodyBase64": "TVqQAAMAAAAEAAAA"}
        ]},
        "processes": [{"command": "whoami", "stdout": "desktop\\admin\n"}]
    }))
}

#[test]
fn saved_files_limits_comparison_and_run_again() {
    let Some((mut session, classes)) = session("files") else { return };
    let sample = Session::choose(&classes).unwrap();
    session.inspect(sample.clone()).unwrap();

    // First run: no answers, so the fixture waits for its server.
    choose_entry(&mut session, &EntryChoice::MainOf { class: "dev.javabyte.guest.sim.HttpRat".into() });
    session.start().unwrap();
    wait_until_finished(&mut session);
    let first = session.report().unwrap();
    assert!(first.limits.endings.iter().any(|e| e.outcome == "sleep-budget" && e.next.as_ref().is_some_and(|n| n.setting.as_deref() == Some("network.http"))),
        "a waiting sample is pointed at web answers: {:?}", first.limits.endings);

    // Second run: with answers, it downloads a file and sends results.
    session.set_settings_text(http_rat_settings(&sample.path));
    session.start().unwrap();
    wait_until_finished(&mut session);
    let second = session.report().unwrap();
    assert!(second.config_text.as_deref().is_some_and(|t| t.contains("cdn")), "the run's settings file is shown");
    assert!(second.limits.refusals.iter().any(|r| r.member.starts_with("java/awt/Robot")), "{:?}", second.limits.refusals);

    let files = session.artifacts().unwrap();
    let stage = files.iter().find(|a| a.name == "stage.bin").expect("the downloaded file is listed");
    assert_eq!(stage.kind, "dropped");
    assert_eq!(stage.origin.as_deref(), Some("C:\\Users\\Public\\stage.bin"));
    assert_eq!(stage.size, 12);
    assert_eq!(stage.sha256, "d930a77b4e5a6df96f8be687f754be90fa6da7939406d214814a80393847ce1b");
    let sent = files.iter().find(|a| a.kind == "network").expect("data it sent is listed");
    assert!(sent.origin.as_deref().is_some_and(|u| u.starts_with("http://c2.rat.test")), "{sent:?}");
    let page = session.artifact_page(&stage.path, 0).unwrap();
    assert_eq!(&page.bytes[..2], b"MZ");
    assert!(session.artifact_page("../report.json", 0).is_err(), "only the run's saved files");

    // Copies: never next to the sample (the test classes folder's parent).
    let out = std::env::temp_dir().join(format!("javabyte-files-copy-{}.zip", std::process::id()));
    session.save_zip(&[stage.path.clone()], &out).unwrap();
    assert!(out.is_file());
    assert!(session.save_zip(&[stage.path.clone()], &classes.join("copy.zip")).is_err());

    // Compare: the second run against the first.
    let recent = session.recent();
    let comparison = session.compare(&recent[1].id, &recent[0].id).unwrap();
    assert!(comparison.same_sample);
    assert!(comparison.settings.iter().any(|s| s.path == "network.http"), "{:?}", comparison.settings);
    let files_written = comparison.indicators.iter().find(|i| i.id == "files").expect("files indicator");
    assert!(files_written.added.iter().any(|f| f.contains("stage.bin")), "{files_written:?}");
    assert!(session.compare(&recent[0].id, &recent[0].id).unwrap().identical(), "a run compared with itself");

    // Run again with changes: the run's own settings come back for the same sample.
    session.run_again().unwrap();
    assert!(session.settings_text().unwrap().contains("cdn"), "{}", session.settings_text().unwrap());
}

#[test]
fn reports_from_the_command_line_open_too() {
    let Some((mut session, classes)) = session("open") else { return };
    let jar = std::env::var_os("JAVABYTE_JAR").unwrap();
    let folder = std::env::temp_dir().join(format!("javabyte-cli-run-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&folder);
    let status = std::process::Command::new(std::env::var_os("JAVABYTE_JAVA").unwrap_or_else(|| "java".into()))
        .arg("-jar").arg(&jar).arg("run").arg(&classes)
        .args(["-s", "entryPoints=[{class: dev.javabyte.guest.Basics}]", "-s", "output.console=quiet", "-o"]).arg(&folder)
        .stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null())
        .status().unwrap();
    assert_eq!(status.code(), Some(0));
    let view = session.open_report_at(&folder).unwrap();
    assert_eq!(view.runs[0].outcome, "completed");
    assert_eq!(session.recent()[0].output_dir, folder.display().to_string(), "it joins the recent list");
    assert!(session.open_report_at(&folder.join("report.json")).is_ok(), "the file itself works too");
    assert!(session.open_report_at(&classes).is_err(), "a folder without a report is refused");
}

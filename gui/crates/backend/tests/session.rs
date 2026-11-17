//! The session against the real tool: choose, inspect, run, report, recent list. Harmless
//! fixtures only; needs JAVABYTE_JAR and JAVABYTE_TEST_CLASSES like tests/runner.rs.

use javabyte_gui_backend::runner::Launcher;
use javabyte_gui_backend::session::Session;
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
    let entry = EntryChoice::MainOf { class: wanted.into() };

    session.start(sample.clone(), &entry).unwrap();
    assert!(session.start(sample.clone(), &entry).is_err(), "one analysis at a time");
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
    // Limits with no argument fails at once; the spinning variant needs an argument, which
    // the GUI does not offer yet, so stop a run that sleeps in a loop instead.
    session.start(sample, &EntryChoice::MainOf { class: "dev.javabyte.guest.sim.Background".into() }).unwrap();
    std::thread::sleep(Duration::from_millis(300));
    session.stop().unwrap();
    let status = wait_until_finished(&mut session);
    assert_eq!(status.state, RunState::Finished);
}

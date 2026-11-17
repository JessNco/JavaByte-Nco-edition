//! Test-only attack on the window's network containment. Compiled only with the
//! `egress-probe` feature, which nothing but `tests/egress` turns on.
//!
//! Once the app's page has loaded, it runs `egress_probe.js` inside it, as if an attacker had
//! found a way to run script there. The script tries every way it knows to send data out and
//! reports what each attempt did. The test harness decides pass or fail from what its
//! listeners actually received, not from this report: a request the page thinks failed may
//! still have left the machine.

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use tauri::webview::PageLoadEvent;
use tauri::{AppHandle, Builder, Wry};

const PROBE: &str = include_str!("egress_probe.js");
static STARTED: AtomicBool = AtomicBool::new(false);

pub fn install(builder: Builder<Wry>) -> Builder<Wry> {
    eprintln!("javabyte-gui: EGRESS PROBE BUILD - for tests only");
    builder.on_page_load(|webview, payload| {
        // Only on the app's own page, and only once: if a navigation ever escaped, the probe
        // must not run again on the page it escaped to.
        if payload.event() == PageLoadEvent::Finished
            && crate::guard::is_app_origin(payload.url())
            && !STARTED.swap(true, Ordering::SeqCst)
        {
            let port = std::env::var("JAVABYTE_PROBE_PORT").unwrap_or_else(|_| "8099".into());
            let port: u16 = port.parse().expect("JAVABYTE_PROBE_PORT must be a port number");
            if let Err(e) = webview.eval(PROBE.replace("__PORT__", &port.to_string())) {
                eprintln!("javabyte-gui: probe could not start: {e}");
            }
        }
    })
}

/// Receives the page's own account of each attempt, writes it for the harness, and closes the
/// app after giving the final navigation attempts time to happen.
#[tauri::command]
pub fn probe_report(report: serde_json::Value, app: AppHandle) {
    let path = std::env::var("JAVABYTE_PROBE_REPORT").unwrap_or_else(|_| "egress-probe-report.json".into());
    let text = serde_json::to_string_pretty(&report).unwrap_or_default();
    if let Err(e) = std::fs::write(&path, text) {
        eprintln!("javabyte-gui: could not write the probe report to {path}: {e}");
    }
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_secs(8));
        app.exit(0);
    });
}

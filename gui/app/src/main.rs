//! The JavaByte desktop app: a window around the screens in `ui/`, and the backend they call.
//!
//! Phase 0 serves made-up events (or a real `report.json` given with `--report PATH`) and a
//! pretend running analysis. It does not start the emulator yet.

// No console window behind the app on Windows in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod guard;
#[cfg(feature = "egress-probe")]
mod egress_probe;

use javabyte_gui_backend::EventStore;
use javabyte_gui_backend::events::EventSource;
use javabyte_gui_backend::report::ReportEventsSource;
use javabyte_gui_backend::simulation::Simulation;
use javabyte_gui_backend::synthetic::SyntheticEvents;
use javabyte_gui_protocol::{Page, PageRequest, RunStatus};
use std::sync::Mutex;
use std::time::Instant;

/// Same sizes as the browser build in `ui/src/bridge.rs`, so tests see the same data.
const TRIAL_EVENTS: u64 = 300_000;
const TRIAL_RUN_MS: u64 = 60_000;

struct AppState {
    events: Mutex<EventStore>,
    run: Mutex<Simulation>,
    started: Instant,
}

impl AppState {
    fn now_ms(&self) -> f64 {
        self.started.elapsed().as_secs_f64() * 1000.0
    }
}

// Every command the screens can call is listed here and nowhere else. They take typed data,
// touch no files chosen by the screens, and start no processes.

#[tauri::command]
fn events_page(request: PageRequest, state: tauri::State<'_, AppState>) -> Page {
    state.events.lock().expect("event store lock").page(&request)
}

#[tauri::command]
fn run_start(state: tauri::State<'_, AppState>) {
    let now = state.now_ms();
    state.run.lock().expect("run lock").start(now);
}

#[tauri::command]
fn run_stop(state: tauri::State<'_, AppState>) {
    let now = state.now_ms();
    state.run.lock().expect("run lock").stop(now);
}

#[tauri::command]
fn run_status(state: tauri::State<'_, AppState>) -> RunStatus {
    let now = state.now_ms();
    state.run.lock().expect("run lock").status(now)
}

/// Events to show: a report named with `--report PATH`, or the made-up set.
fn event_source() -> Box<dyn EventSource + Send + Sync> {
    let args: Vec<String> = std::env::args().collect();
    if let Some(path) = args.iter().position(|a| a == "--report").and_then(|i| args.get(i + 1)) {
        match std::fs::File::open(path).map_err(|e| e.to_string()).and_then(ReportEventsSource::read) {
            Ok(report) => return Box::new(report),
            Err(message) => eprintln!("Could not read {path}: {message}. Showing made-up events instead."),
        }
    }
    Box::new(SyntheticEvents::new(TRIAL_EVENTS))
}

fn main() {
    let state = AppState {
        events: Mutex::new(EventStore::new(event_source())),
        run: Mutex::new(Simulation::new(TRIAL_RUN_MS)),
        started: Instant::now(),
    };
    let builder = tauri::Builder::default().plugin(guard::plugin()).manage(state);
    #[cfg(not(feature = "egress-probe"))]
    let builder = builder.invoke_handler(tauri::generate_handler![events_page, run_start, run_stop, run_status]);
    #[cfg(feature = "egress-probe")]
    let builder = egress_probe::install(builder)
        .invoke_handler(tauri::generate_handler![events_page, run_start, run_stop, run_status, egress_probe::probe_report]);
    builder.run(tauri::generate_context!()).expect("the JavaByte window could not be opened");
}

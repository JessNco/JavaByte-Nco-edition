//! How the screens reach the backend.
//!
//! In the desktop app every call goes to the Rust backend through Tauri. In a plain browser,
//! used for automated accessibility tests, a stand-in answers instead: it reads the real tool
//! output committed in `crates/protocol/tests/tool-output/` and runs a pretend analysis, so the
//! screens see realistic data. The screens cannot tell the difference.

use javabyte_gui_backend::EventStore;
use javabyte_gui_backend::simulation::Simulation;
use javabyte_gui_backend::synthetic::SyntheticEvents;
use javabyte_gui_protocol::inspect::Inspection;
use javabyte_gui_protocol::report::Report;
use javabyte_gui_protocol::views::{self, EngineStatus, EntryChoice, Overview, RecentRun, ReportView, SampleChoice};
use javabyte_gui_protocol::{Page, PageRequest, RunState, RunStatus};
use serde::Serialize;
use serde::de::DeserializeOwned;
use std::cell::RefCell;
use wasm_bindgen::{JsCast, JsValue};
use wasm_bindgen_futures::JsFuture;

/// Rows in the browser build's event list: the size of a very busy real run.
pub const TRIAL_EVENTS: u64 = 300_000;
/// Length of the browser build's pretend analysis.
pub const TRIAL_RUN_MS: u64 = 20_000;

const SAMPLE_INSPECTION: &str = include_str!("../../crates/protocol/tests/tool-output/inspect-native.json");
const SAMPLE_REPORT: &str = include_str!("../../crates/protocol/tests/tool-output/report-basics.json");

struct InPage {
    events: EventStore,
    run: Simulation,
    sample: Option<SampleChoice>,
    finished_once: bool,
}

thread_local! {
    static IN_PAGE: RefCell<Option<InPage>> = const { RefCell::new(None) };
}

fn in_page<T>(work: impl FnOnce(&mut InPage) -> T) -> T {
    IN_PAGE.with(|cell| {
        let mut slot = cell.borrow_mut();
        let backend = slot.get_or_insert_with(|| InPage {
            events: EventStore::new(Box::new(SyntheticEvents::new(TRIAL_EVENTS))),
            run: Simulation::new(TRIAL_RUN_MS),
            sample: None,
            finished_once: false,
        });
        work(backend)
    })
}

fn tauri_core() -> Option<JsValue> {
    let window: JsValue = web_sys::window()?.into();
    let tauri = js_sys::Reflect::get(&window, &"__TAURI__".into()).ok().filter(|v| !v.is_undefined())?;
    js_sys::Reflect::get(&tauri, &"core".into()).ok().filter(|v| !v.is_undefined())
}

pub fn in_desktop_app() -> bool {
    tauri_core().is_some()
}

async fn invoke<T: DeserializeOwned>(command: &str, args: impl Serialize) -> Result<T, String> {
    let core = tauri_core().ok_or("The desktop backend is not available.")?;
    let function: js_sys::Function = js_sys::Reflect::get(&core, &"invoke".into())
        .map_err(|_| "Tauri has no invoke function.")?
        .dyn_into()
        .map_err(|_| "Tauri's invoke is not a function.")?;
    let args = args
        .serialize(&serde_wasm_bindgen::Serializer::json_compatible())
        .map_err(|e| format!("Could not prepare the request: {e}"))?;
    let promise: js_sys::Promise = function
        .call2(&core, &JsValue::from_str(command), &args)
        .map_err(|e| format!("{e:?}"))?
        .dyn_into()
        .map_err(|_| "Tauri's invoke did not return a promise.")?;
    let value = JsFuture::from(promise).await.map_err(|e| e.as_string().unwrap_or_else(|| format!("{e:?}")))?;
    serde_wasm_bindgen::from_value(value).map_err(|e| format!("Unexpected reply from the backend: {e}"))
}

#[derive(Serialize)]
struct NoArgs {}

#[derive(Serialize)]
struct SampleArgs<'a> {
    sample: &'a SampleChoice,
}

#[derive(Serialize)]
struct RunArgs<'a> {
    sample: &'a SampleChoice,
    entry: &'a EntryChoice,
}

#[derive(Serialize)]
struct IdArgs<'a> {
    id: &'a str,
}

#[derive(Serialize)]
struct PageArgs<'a> {
    request: &'a PageRequest,
}

fn example_sample(folder: bool) -> SampleChoice {
    if folder {
        SampleChoice { path: "C:\\Samples\\classes".into(), name: "classes".into() }
    } else {
        SampleChoice { path: "C:\\Samples\\example.jar".into(), name: "example.jar".into() }
    }
}

fn example_report(sample_name: &str) -> ReportView {
    let report: Report = serde_json::from_str(SAMPLE_REPORT).expect("the committed sample report reads");
    views::report_view(&report, sample_name, "C:\\Users\\you\\Documents\\JavaByte\\2026-11-17 10-00-00 example.jar")
}

pub async fn engine_status() -> EngineStatus {
    if in_desktop_app() {
        invoke("engine_status", NoArgs {}).await.unwrap_or_else(|e| EngineStatus { ready: false, problem: Some(e) })
    } else {
        EngineStatus { ready: true, problem: None }
    }
}

/// Opens the system file picker. None if the person cancelled.
pub async fn choose_sample(folder: bool) -> Result<Option<SampleChoice>, String> {
    if in_desktop_app() {
        invoke(if folder { "choose_sample_folder" } else { "choose_sample" }, NoArgs {}).await
    } else {
        Ok(Some(example_sample(folder)))
    }
}

pub async fn take_dropped() -> Result<Option<SampleChoice>, String> {
    if in_desktop_app() { invoke("take_dropped", NoArgs {}).await } else { Ok(None) }
}

pub async fn inspect_sample(sample: &SampleChoice) -> Result<Overview, String> {
    if in_desktop_app() {
        invoke("inspect_sample", SampleArgs { sample }).await
    } else {
        let inspection: Inspection = serde_json::from_str(SAMPLE_INSPECTION).map_err(|e| e.to_string())?;
        Ok(views::overview(sample.clone(), inspection))
    }
}

pub async fn command_line(sample: &SampleChoice, entry: &EntryChoice) -> Result<String, String> {
    if in_desktop_app() {
        invoke("command_line", RunArgs { sample, entry }).await
    } else {
        Ok(match entry.override_value() {
            Some(value) => format!("javabyte run '{}' -s '{}'", sample.path, value),
            None => format!("javabyte run '{}'", sample.path),
        })
    }
}

pub async fn start_run(sample: &SampleChoice, entry: &EntryChoice) -> Result<(), String> {
    if in_desktop_app() {
        invoke("start_run", RunArgs { sample, entry }).await
    } else {
        in_page(|b| {
            b.sample = Some(sample.clone());
            b.run.start(js_sys::Date::now());
        });
        Ok(())
    }
}

pub async fn stop_run() -> Result<(), String> {
    if in_desktop_app() {
        invoke("stop_run", NoArgs {}).await
    } else {
        in_page(|b| b.run.stop(js_sys::Date::now()));
        Ok(())
    }
}

pub async fn run_status() -> Result<RunStatus, String> {
    if in_desktop_app() {
        invoke("run_status", NoArgs {}).await
    } else {
        Ok(in_page(|b| {
            let mut status = b.run.status(js_sys::Date::now());
            if let Some(sample) = &b.sample {
                status.sample_name = sample.name.clone();
            }
            if status.state == RunState::Finished {
                b.finished_once = true;
            }
            status
        }))
    }
}

pub async fn current_report() -> Result<ReportView, String> {
    if in_desktop_app() {
        invoke("current_report", NoArgs {}).await
    } else {
        Ok(in_page(|b| example_report(b.sample.as_ref().map_or("example.jar", |s| s.name.as_str()))))
    }
}

pub async fn recent_runs() -> Vec<RecentRun> {
    if in_desktop_app() {
        invoke("recent_runs", NoArgs {}).await.unwrap_or_default()
    } else {
        // One earlier run, so the list and its buttons can be tested.
        vec![RecentRun {
            id: "2026-11-16 09-30-00 earlier.jar".into(),
            sample_name: "earlier.jar".into(),
            when: "2026-11-16 09:30".into(),
            outcome: "The sample ran to its end.".into(),
            events: 27,
            output_dir: "C:\\Users\\you\\Documents\\JavaByte\\2026-11-16 09-30-00 earlier.jar".into(),
        }]
    }
}

pub async fn open_recent(id: &str) -> Result<ReportView, String> {
    if in_desktop_app() { invoke("open_recent", IdArgs { id }).await } else { Ok(example_report("earlier.jar")) }
}

pub async fn events_page(request: PageRequest) -> Result<Page, String> {
    if in_desktop_app() {
        invoke("events_page", PageArgs { request: &request }).await
    } else {
        Ok(in_page(|b| b.events.page(&request)))
    }
}

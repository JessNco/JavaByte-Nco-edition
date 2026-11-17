//! How the screens reach the backend.
//!
//! In the desktop app every call goes to the Rust backend through Tauri. In a plain browser,
//! used for automated accessibility tests, the same backend code runs inside the page instead.
//! The screens cannot tell the difference.

use javabyte_gui_backend::EventStore;
use javabyte_gui_backend::simulation::Simulation;
use javabyte_gui_backend::synthetic::SyntheticEvents;
use javabyte_gui_protocol::{Page, PageRequest, RunStatus};
use serde::Serialize;
use serde::de::DeserializeOwned;
use std::cell::RefCell;
use wasm_bindgen::{JsCast, JsValue};
use wasm_bindgen_futures::JsFuture;

/// Rows in the trial's made-up report: the size of a very busy real run.
pub const TRIAL_EVENTS: u64 = 300_000;
/// Length of the pretend analysis.
pub const TRIAL_RUN_MS: u64 = 60_000;

struct InPage {
    events: EventStore,
    run: Simulation,
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
struct PageArgs<'a> {
    request: &'a PageRequest,
}

pub async fn events_page(request: PageRequest) -> Result<Page, String> {
    if in_desktop_app() {
        invoke("events_page", PageArgs { request: &request }).await
    } else {
        Ok(in_page(|b| b.events.page(&request)))
    }
}

pub async fn run_start() -> Result<(), String> {
    if in_desktop_app() {
        invoke("run_start", NoArgs {}).await
    } else {
        in_page(|b| b.run.start(js_sys::Date::now()));
        Ok(())
    }
}

pub async fn run_stop() -> Result<(), String> {
    if in_desktop_app() {
        invoke("run_stop", NoArgs {}).await
    } else {
        in_page(|b| b.run.stop(js_sys::Date::now()));
        Ok(())
    }
}

pub async fn run_status() -> Result<RunStatus, String> {
    if in_desktop_app() {
        invoke("run_status", NoArgs {}).await
    } else {
        Ok(in_page(|b| b.run.status(js_sys::Date::now())))
    }
}

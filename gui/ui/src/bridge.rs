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
use javabyte_gui_protocol::t;
use javabyte_gui_backend::config::ConfigDoc;
use javabyte_gui_protocol::schema::Schema;
use javabyte_gui_protocol::validate::{Problem, Validation};
use javabyte_gui_protocol::views::{self, EngineStatus, Imported, Overview, Preset, RecentRun, ReportView, SampleChoice};
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
const SAMPLE_SCHEMA: &str = include_str!("../../crates/protocol/tests/tool-output/schema.json");
/// What `javabyte init` writes for the stand-in's sample (the committed inspection's main class).
const EXAMPLE_SETTINGS: &str = include_str!("example-settings.yaml");
/// What the stand-in's Import offers: a JSON config made for another sample.
const EXAMPLE_IMPORT: &str = r#"{"sample": "D:\\other\\dropper.jar", "limits": {"maxSleeps": 200}, "network": {"mode": "offline"}}"#;

struct InPage {
    events: EventStore,
    run: Simulation,
    sample: Option<SampleChoice>,
    finished_once: bool,
    settings: String,
    presets: Vec<(String, String)>,
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
            settings: EXAMPLE_SETTINGS.to_string(),
            presets: Vec::new(),
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
    let core = tauri_core().ok_or_else(|| t!("error-backend-missing"))?;
    let function: js_sys::Function = js_sys::Reflect::get(&core, &"invoke".into())
        .map_err(|_| t!("error-backend-call", detail = "no invoke function"))? // not-ui-text: technical detail
        .dyn_into()
        .map_err(|_| t!("error-backend-call", detail = "invoke is not a function"))?; // not-ui-text: technical detail
    let args = args
        .serialize(&serde_wasm_bindgen::Serializer::json_compatible())
        .map_err(|e| t!("error-backend-call", detail = e.to_string()))?;
    let promise: js_sys::Promise = function
        .call2(&core, &JsValue::from_str(command), &args)
        .map_err(|e| format!("{e:?}"))?
        .dyn_into()
        .map_err(|_| t!("error-backend-call", detail = "invoke did not return a promise"))?; // not-ui-text: technical detail
    let value = JsFuture::from(promise).await.map_err(|e| e.as_string().unwrap_or_else(|| format!("{e:?}")))?;
    serde_wasm_bindgen::from_value(value).map_err(|e| t!("error-backend-reply", detail = e.to_string()))
}

#[derive(Serialize)]
struct NoArgs {}

#[derive(Serialize)]
struct SampleArgs<'a> {
    sample: &'a SampleChoice,
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
        // As in the app: each sample starts from fresh suggested settings.
        in_page(|b| {
            b.sample = Some(sample.clone());
            b.settings = javabyte_gui_backend::session::with_sample(EXAMPLE_SETTINGS, Some(sample));
        });
        Ok(views::overview(sample.clone(), inspection))
    }
}

pub async fn start_run() -> Result<(), String> {
    if in_desktop_app() {
        invoke("start_run", NoArgs {}).await
    } else {
        in_page(|b| b.run.start(js_sys::Date::now()));
        Ok(())
    }
}

// --- Settings ---

#[derive(Serialize)]
struct TextArgs<'a> {
    text: &'a str,
}

#[derive(Serialize)]
struct NameArgs<'a> {
    name: &'a str,
}

#[derive(Serialize)]
struct PresetArgs<'a> {
    name: &'a str,
    text: &'a str,
}

/// The settings for the chosen sample's next run, as the backend has them.
pub async fn settings_text() -> Result<Option<String>, String> {
    if in_desktop_app() { invoke("settings_text", NoArgs {}).await } else { Ok(Some(in_page(|b| b.settings.clone()))) }
}

pub async fn set_settings_text(text: &str) -> Result<(), String> {
    if in_desktop_app() {
        invoke("set_settings_text", TextArgs { text }).await
    } else {
        in_page(|b| b.settings = text.to_string());
        Ok(())
    }
}

pub async fn config_schema() -> Result<Schema, String> {
    if in_desktop_app() { invoke("config_schema", NoArgs {}).await } else { serde_json::from_str(SAMPLE_SCHEMA).map_err(|e| e.to_string()) }
}

pub async fn check_settings(text: &str) -> Result<Validation, String> {
    if in_desktop_app() {
        invoke("check_settings", TextArgs { text }).await
    } else {
        let schema: Schema = serde_json::from_str(SAMPLE_SCHEMA).map_err(|e| e.to_string())?;
        Ok(stand_in_check(&schema, text))
    }
}

pub async fn presets() -> Vec<Preset> {
    if in_desktop_app() {
        invoke("presets", NoArgs {}).await.unwrap_or_default()
    } else {
        in_page(|b| b.presets.iter().map(|(name, _)| Preset { name: name.clone() }).collect())
    }
}

pub async fn presets_folder() -> String {
    if in_desktop_app() {
        invoke("presets_folder", NoArgs {}).await.unwrap_or_default()
    } else {
        "C:\\Users\\you\\AppData\\Roaming\\dev.javabyte.gui\\presets".into()
    }
}

pub async fn save_preset(name: &str, text: &str) -> Result<Preset, String> {
    if in_desktop_app() {
        invoke("save_preset", PresetArgs { name, text }).await
    } else {
        let name = name.trim().to_string();
        if name.is_empty() {
            return Err(t!("error-preset-name"));
        }
        let text = javabyte_gui_backend::session::with_sample(text, None);
        in_page(|b| {
            b.presets.retain(|(n, _)| *n != name);
            b.presets.push((name.clone(), text));
        });
        Ok(Preset { name })
    }
}

pub async fn load_preset(name: &str) -> Result<String, String> {
    if in_desktop_app() {
        invoke("load_preset", NameArgs { name }).await
    } else {
        let text = in_page(|b| b.presets.iter().find(|(n, _)| n == name).map(|(_, t)| t.clone()));
        let sample = example_sample(false);
        text.map(|t| javabyte_gui_backend::session::with_sample(&t, Some(&sample))).ok_or_else(|| t!("error-preset-read", detail = name))
    }
}

pub async fn delete_preset(name: &str) -> Result<(), String> {
    if in_desktop_app() {
        invoke("delete_preset", NameArgs { name }).await
    } else {
        in_page(|b| b.presets.retain(|(n, _)| n != name));
        Ok(())
    }
}

/// Opens a settings file the person picks. None if they cancelled.
pub async fn import_settings() -> Result<Option<Imported>, String> {
    if in_desktop_app() {
        invoke("import_settings", NoArgs {}).await
    } else {
        let value: serde_json::Value = serde_json::from_str(EXAMPLE_IMPORT).map_err(|e| e.to_string())?;
        Ok(Some(Imported {
            text: javabyte_gui_backend::config::render_document(&value),
            sample: value["sample"].as_str().map(str::to_string),
        }))
    }
}

/// Saves the settings where the person chooses. `false` if they cancelled.
pub async fn export_settings(text: &str) -> Result<bool, String> {
    if in_desktop_app() { invoke("export_settings", TextArgs { text }).await } else { Ok(true) }
}

/// A file on this computer, for settings that read one.
pub async fn choose_host_file() -> Option<String> {
    if in_desktop_app() {
        invoke("choose_host_file", NoArgs {}).await.ok().flatten()
    } else {
        Some("C:\\Users\\you\\Documents\\reply.bin".into())
    }
}

/// The browser stand-in's check: the tool's main rules (unknown options, whole numbers,
/// allowed values, required fields), so the screens can be tested without the tool. The
/// desktop app always asks the tool itself.
fn stand_in_check(schema: &Schema, text: &str) -> Validation {
    let doc = ConfigDoc::new(text);
    let Some(json) = doc.to_json() else {
        let problems = doc.syntax_problems();
        let message = problems.first().map_or_else(String::new, |p| p.message.clone());
        return Validation { valid: false, problems: vec![Problem { key: String::new(), message }] };
    };
    let mut problems = Vec::new();
    fn walk(schema: &Schema, prefix: &str, schema_prefix: &str, value: &serde_json::Value, problems: &mut Vec<Problem>) {
        let Some(map) = value.as_object() else { return };
        for (key, child) in map {
            let path = if prefix.is_empty() { key.clone() } else { format!("{prefix}.{key}") };
            let schema_path = if schema_prefix.is_empty() { key.clone() } else { format!("{schema_prefix}.{key}") };
            let Some(option) = schema.option(&schema_path) else {
                problems.push(Problem { key: path.clone(), message: format!("Unknown option '{key}'") }); // not-ui-text: the tool's own words
                continue;
            };
            match option.kind.as_str() {
                "integer" if !child.is_i64() && !child.is_null() => {
                    problems.push(Problem { key: path.clone(), message: format!("{path} must be a whole number, found '{}'", child.as_str().unwrap_or_default()) }); // not-ui-text: the tool's own words
                }
                "string" if !option.allowed.is_empty() => {
                    if let Some(text) = child.as_str() {
                        if !option.allowed.iter().any(|a| a == text) {
                            problems.push(Problem { key: path.clone(), message: format!("{path} must be one of {}", option.allowed.join(", ")) }); // not-ui-text: the tool's own words
                        }
                    }
                }
                "object" => walk(schema, &path, &schema_path, child, problems),
                "list" if option.element.as_deref() == Some("object") => {
                    for (i, item) in child.as_array().into_iter().flatten().enumerate() {
                        walk(schema, &format!("{path}[{i}]"), &format!("{schema_path}[]"), item, problems);
                        for required in ["url", "command", "method", "endpoint"] {
                            let required_path = format!("{schema_path}[].{required}");
                            let empty = item.get(required).and_then(|v| v.as_str()).is_none_or(str::is_empty);
                            if schema.option(&required_path).is_some() && !schema_path.starts_with("entryPoints") && empty {
                                problems.push(Problem { key: format!("{path}[{i}].{required}"), message: format!("{path}[{i}].{required} is required") }); // not-ui-text: the tool's own words
                            }
                        }
                    }
                }
                _ => {}
            }
        }
    }
    walk(schema, "", "", &json, &mut problems);
    Validation { valid: problems.is_empty(), problems }
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
            outcome: javabyte_gui_protocol::report::describe_outcome("completed"),
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

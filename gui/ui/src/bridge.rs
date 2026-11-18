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
use javabyte_gui_protocol::views::{self, AppPreferences, EngineStatus, Isolation, SetupStatus, Imported, Overview, Preset, RecentRun, ReportView, SampleChoice};
use javabyte_gui_protocol::{Page, PageRequest, RunState, RunStatus};
use serde::Serialize;
use serde::de::DeserializeOwned;
use std::cell::RefCell;
use wasm_bindgen::{JsCast, JsValue};
use wasm_bindgen_futures::JsFuture;

/// Rows in the browser build's event list: the size of a very busy real run.
pub const STAND_IN_EVENTS: u64 = 300_000;
/// Length of the browser build's pretend analysis.
pub const STAND_IN_RUN_MS: u64 = 20_000;

const SAMPLE_INSPECTION: &str = include_str!("../../crates/protocol/tests/tool-output/inspect-native.json");
const SAMPLE_REPORT: &str = include_str!("../../crates/protocol/tests/tool-output/report-basics.json");
const SAMPLE_SCHEMA: &str = include_str!("../../crates/protocol/tests/tool-output/schema.json");
const SAMPLE_POLICY: &str = include_str!("../../crates/protocol/tests/tool-output/policy.json");
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
    preferences: AppPreferences,
}

thread_local! {
    static IN_PAGE: RefCell<Option<InPage>> = const { RefCell::new(None) };
}

/// Runs `work` on the browser stand-in, creating it on first use.
fn in_page<T>(work: impl FnOnce(&mut InPage) -> T) -> T {
    IN_PAGE.with(|cell| {
        let mut slot = cell.borrow_mut();
        let backend = slot.get_or_insert_with(|| InPage {
            events: EventStore::new(Box::new(SyntheticEvents::new(STAND_IN_EVENTS))),
            run: Simulation::new(STAND_IN_RUN_MS),
            sample: None,
            finished_once: false,
            settings: EXAMPLE_SETTINGS.to_string(),
            presets: Vec::new(),
            preferences: AppPreferences::default(),
        });
        work(backend)
    })
}

/// Tauri's `core` object, present only inside the desktop app.
fn tauri_core() -> Option<JsValue> {
    let window: JsValue = web_sys::window()?.into();
    let tauri = js_sys::Reflect::get(&window, &"__TAURI__".into()).ok().filter(|v| !v.is_undefined())?;
    js_sys::Reflect::get(&tauri, &"core".into()).ok().filter(|v| !v.is_undefined())
}

/// Whether the screens run inside the desktop app (rather than a test browser).
pub fn in_desktop_app() -> bool {
    tauri_core().is_some()
}

/// Calls a desktop command and reads its reply. Errors come back as text for people.
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

/// The stand-in's sample: a jar, or a folder of classes.
fn example_sample(folder: bool) -> SampleChoice {
    if folder {
        SampleChoice { path: "C:\\Samples\\classes".into(), name: "classes".into() }
    } else {
        SampleChoice { path: "C:\\Samples\\example.jar".into(), name: "example.jar".into() }
    }
}

/// The stand-in's report, built from the committed real report.
fn example_report(sample_name: &str) -> ReportView {
    let report: Report = serde_json::from_str(SAMPLE_REPORT).expect("the committed sample report reads");
    let mut view = views::report_view(&report, sample_name, CURRENT_RUN);
    view.config_text = Some(in_page(|b| b.settings.clone()));
    view
}

/// Whether Java and the tool were found.
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

/// A file dropped on the window (or named on the command line), if one is waiting.
pub async fn take_dropped() -> Result<Option<SampleChoice>, String> {
    if in_desktop_app() { invoke("take_dropped", NoArgs {}).await } else { Ok(None) }
}

/// Describes the chosen sample and starts its settings afresh.
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

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StartArgs {
    without_isolation: bool,
}

/// What must be asked before the next run, if anything: why it cannot be isolated from the
/// network.
pub async fn run_question() -> Option<String> {
    if in_desktop_app() {
        invoke("run_question", NoArgs {}).await.unwrap_or(None)
    } else {
        let status = stand_in_setup();
        (status.isolation.supported && status.preferences.isolate && !status.isolation.available && status.preferences.ask_without_isolation)
            .then(|| status.isolation.problem.unwrap_or_default())
    }
}

/// Starts the analysis of the chosen sample with its settings. `without_isolation` answers the
/// [`run_question`], when there was one.
pub async fn start_run(without_isolation: bool) -> Result<(), String> {
    if in_desktop_app() {
        invoke("start_run", StartArgs { without_isolation }).await
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

/// Sends the settings to the backend after a change.
pub async fn set_settings_text(text: &str) -> Result<(), String> {
    if in_desktop_app() {
        invoke("set_settings_text", TextArgs { text }).await
    } else {
        in_page(|b| b.settings = text.to_string());
        Ok(())
    }
}

/// Every option the tool has.
pub async fn config_schema() -> Result<Schema, String> {
    if in_desktop_app() { invoke("config_schema", NoArgs {}).await } else { serde_json::from_str(SAMPLE_SCHEMA).map_err(|e| e.to_string()) }
}

/// Every problem in some settings, as the tool sees them.
pub async fn check_settings(text: &str) -> Result<Validation, String> {
    if in_desktop_app() {
        invoke("check_settings", TextArgs { text }).await
    } else {
        let schema: Schema = serde_json::from_str(SAMPLE_SCHEMA).map_err(|e| e.to_string())?;
        Ok(stand_in_check(&schema, text))
    }
}

/// Saved presets, by name.
pub async fn presets() -> Vec<Preset> {
    if in_desktop_app() {
        invoke("presets", NoArgs {}).await.unwrap_or_default()
    } else {
        in_page(|b| b.presets.iter().map(|(name, _)| Preset { name: name.clone() }).collect())
    }
}

/// Where saved presets are kept.
pub async fn presets_folder() -> String {
    if in_desktop_app() {
        invoke("presets_folder", NoArgs {}).await.unwrap_or_default()
    } else {
        "C:\\Users\\you\\AppData\\Roaming\\dev.javabyte.gui\\presets".into()
    }
}

/// Saves settings as a preset, without their sample.
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

/// A saved preset's settings, for the chosen sample.
pub async fn load_preset(name: &str) -> Result<String, String> {
    if in_desktop_app() {
        invoke("load_preset", NameArgs { name }).await
    } else {
        let text = in_page(|b| b.presets.iter().find(|(n, _)| n == name).map(|(_, t)| t.clone()));
        let sample = example_sample(false);
        text.map(|t| javabyte_gui_backend::session::with_sample(&t, Some(&sample))).ok_or_else(|| t!("error-preset-read", detail = name))
    }
}

/// Deletes a saved preset.
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
    /// Checks one level of the settings against the schema, then the levels below it.
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
                    if let Some(text) = child.as_str()
                        && !option.allowed.iter().any(|a| a == text) {
                            problems.push(Problem { key: path.clone(), message: format!("{path} must be one of {}", option.allowed.join(", ")) }); // not-ui-text: the tool's own words
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

/// Asks the running analysis to stop; it keeps what it found.
pub async fn stop_run() -> Result<(), String> {
    if in_desktop_app() {
        invoke("stop_run", NoArgs {}).await
    } else {
        in_page(|b| b.run.stop(js_sys::Date::now()));
        Ok(())
    }
}

/// The running analysis, for the Running screen.
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

/// The report of the last finished or opened run.
pub async fn current_report() -> Result<ReportView, String> {
    if in_desktop_app() {
        invoke("current_report", NoArgs {}).await
    } else {
        // The name first: example_report borrows the stand-in itself.
        let name = in_page(|b| b.sample.as_ref().map_or_else(|| "example.jar".to_string(), |s| s.name.clone()));
        Ok(example_report(&name))
    }
}

/// The stand-in's run folders: the run just made, an earlier run of the same sample, and a run of
/// another sample, so the recent list and Compare can be tested.
const CURRENT_RUN: &str = "C:\\Users\\you\\Documents\\JavaByte\\2026-11-17 10-00-00 example.jar";
const PREVIOUS_RUN: &str = "C:\\Users\\you\\Documents\\JavaByte\\2026-11-16 18-00-00 example.jar";
const OTHER_RUN: &str = "C:\\Users\\you\\Documents\\JavaByte\\2026-11-16 09-30-00 earlier.jar";

/// The stand-in's recent list. The current run is in it once it has finished.
fn stand_in_recent(finished: bool) -> Vec<RecentRun> {
    let run = |folder: &str, name: &str, when: &str| RecentRun {
        id: folder.rsplit('\\').next().unwrap_or(folder).to_string(),
        sample_name: name.into(),
        when: when.into(),
        outcome: javabyte_gui_protocol::report::describe_outcome("completed"),
        events: 27,
        output_dir: folder.into(),
    };
    let mut list = Vec::new();
    if finished {
        list.push(run(CURRENT_RUN, "example.jar", "2026-11-17 10:00"));
    }
    list.push(run(PREVIOUS_RUN, "example.jar", "2026-11-16 18:00"));
    list.push(run(OTHER_RUN, "earlier.jar", "2026-11-16 09:30"));
    list
}

/// Recent analyses, newest first.
pub async fn recent_runs() -> Vec<RecentRun> {
    if in_desktop_app() {
        invoke("recent_runs", NoArgs {}).await.unwrap_or_default()
    } else {
        in_page(|b| stand_in_recent(b.finished_once))
    }
}

/// Opens a report from the recent list.
pub async fn open_recent(id: &str) -> Result<ReportView, String> {
    if in_desktop_app() { invoke("open_recent", IdArgs { id }).await } else { Ok(example_report("earlier.jar")) }
}

/// A page of the open report's events.
pub async fn events_page(request: PageRequest) -> Result<Page, String> {
    if in_desktop_app() {
        invoke("events_page", PageArgs { request: &request }).await
    } else {
        Ok(in_page(|b| b.events.page(&request)))
    }
}

// --- Reports and their files ---

#[derive(Serialize)]
struct PageOfFile<'a> {
    path: &'a str,
    offset: u64,
}

#[derive(Serialize)]
struct PathArgs<'a> {
    path: &'a str,
}

#[derive(Serialize)]
struct PathsArgs<'a> {
    paths: &'a [String],
}

#[derive(Serialize)]
struct CompareArgs<'a> {
    a: &'a str,
    b: &'a str,
}

#[derive(Serialize)]
struct CategoryArgs<'a> {
    category: &'a str,
}

#[derive(Serialize)]
struct PositionArgs<'a> {
    seq: u64,
    filter: &'a javabyte_gui_protocol::EventFilter,
}

/// Opens a report.json (or, with `folder`, a results folder) the person picks.
pub async fn open_report_from_disk(folder: bool) -> Result<Option<ReportView>, String> {
    if in_desktop_app() {
        invoke(if folder { "open_report_folder" } else { "open_report_file" }, NoArgs {}).await
    } else {
        Ok(Some(example_report("cli-run.jar")))
    }
}

/// The browser stand-in's saved files: hostile on purpose, so the viewer's escaping is tested.
fn stand_in_files() -> Vec<(views::Artifact, Vec<u8>)> {
    let mut stage = b"MZ\x90\x00\x03\x00\x00\x00".to_vec();
    stage.extend((0..200_000u32).map(|i| (i % 251) as u8));
    let hostile = "<img src=x onerror=alert(1)> bell\u{7} rtl\u{202e}txt.exe zero\u{200b}width\nline two\n".as_bytes().to_vec(); // not-ui-text: pretend sample data
    let mut class = vec![0xCA, 0xFE, 0xBA, 0xBE, 0, 0, 0, 61];
    class.extend(b"pretend constant pool"); // not-ui-text: pretend sample data
    let artifact = |path: &str, name: &str, kind: &str, origin: &str, bytes: &[u8], sha: char| views::Artifact {
        path: path.into(),
        name: name.into(),
        kind: kind.into(),
        size: bytes.len() as u64,
        sha256: std::iter::repeat_n(sha, 64).collect(),
        origin: Some(origin.into()),
    };
    vec![
        (artifact("dropped/C_/Users/Public/stage.bin", "stage.bin", "dropped", "C:\\Users\\Public\\stage.bin", &stage, 'a'), stage.clone()),
        (artifact("dropped/C_/Users/admin/invoice\u{202e}fdp.exe", "invoice\u{202e}fdp.exe", "dropped", "C:\\Users\\admin\\invoice\u{202e}fdp.exe", &hostile, 'b'), hostile.clone()),
        (artifact("defined-classes/a/b/Loader.class", "Loader.class", "class", "a.b.Loader", &class, 'c'), class.clone()),
        (artifact("network/http-request-0.bin", "http-request-0.bin", "network", "http://c2.example.test/register", b"id=1&os=Windows", 'd'), b"id=1&os=Windows".to_vec()),
    ]
}

/// The open report's saved files.
pub async fn artifacts() -> Result<Vec<views::Artifact>, String> {
    if in_desktop_app() { invoke("artifacts", NoArgs {}).await } else { Ok(stand_in_files().into_iter().map(|(a, _)| a).collect()) }
}

/// One 64 KB page of a saved file.
pub async fn artifact_page(path: &str, offset: u64) -> Result<views::FilePage, String> {
    if in_desktop_app() {
        invoke("artifact_page", PageOfFile { path, offset }).await
    } else {
        let (_, bytes) = stand_in_files().into_iter().find(|(a, _)| a.path == path).ok_or_else(|| t!("error-artifact-refused", path = path))?;
        let start = (offset as usize).min(bytes.len());
        let end = (start + views::PAGE_BYTES as usize).min(bytes.len());
        Ok(views::FilePage { offset: start as u64, total: bytes.len() as u64, bytes: bytes[start..end].to_vec() })
    }
}

/// A saved class as readable instructions.
pub async fn artifact_disasm(path: &str) -> Result<String, String> {
    if in_desktop_app() {
        invoke("artifact_disasm", PathArgs { path }).await
    } else {
        Ok(include_str!("example-disasm.txt").to_string())
    }
}

/// Saves the chosen files in a password zip. `false` if the person cancelled.
pub async fn save_zip(paths: &[String]) -> Result<bool, String> {
    if in_desktop_app() { invoke("save_zip", PathsArgs { paths }).await } else { Ok(true) }
}

/// Saves one file as it is, after the warning. `false` if the person cancelled.
pub async fn save_as_is(path: &str) -> Result<bool, String> {
    if in_desktop_app() { invoke("save_as_is", PathArgs { path }).await } else { Ok(true) }
}

/// Compares two runs from the recent list.
pub async fn compare_runs(a: &str, b: &str) -> Result<javabyte_gui_protocol::compare::Comparison, String> {
    if in_desktop_app() {
        invoke("compare_runs", CompareArgs { a, b }).await
    } else {
        // The committed report, and a variant with a changed setting and an extra request.
        let report: Report = serde_json::from_str(SAMPLE_REPORT).map_err(|e| e.to_string())?;
        let mut other = report.clone();
        other.config["limits"]["maxSleeps"] = serde_json::json!(200);
        other.summary.http_requests.push(serde_json::json!("http://c2.example.test/next"));
        let pick = |id: &str| if id.contains("18-00-00") { (&report, "example.jar", PREVIOUS_RUN) } else { (&other, "example.jar", CURRENT_RUN) };
        Ok(javabyte_gui_protocol::compare::compare(pick(a), pick(b)))
    }
}

/// The open report's sample with the run's settings, for Run again with changes.
pub async fn run_again() -> Result<Overview, String> {
    if in_desktop_app() {
        invoke("run_again", NoArgs {}).await
    } else {
        let inspection: Inspection = serde_json::from_str(SAMPLE_INSPECTION).map_err(|e| e.to_string())?;
        Ok(views::overview(example_sample(false), inspection))
    }
}

/// The actions in one category of the open report's events.
pub async fn event_actions(category: &str) -> Vec<String> {
    if in_desktop_app() {
        invoke("event_actions", CategoryArgs { category }).await.unwrap_or_default()
    } else {
        in_page(|b| b.events.actions(category))
    }
}

/// Where an event number is in the filtered list, if it is there.
pub async fn event_position(seq: u64, filter: &javabyte_gui_protocol::EventFilter) -> Option<u64> {
    if in_desktop_app() {
        invoke("event_position", PositionArgs { seq, filter }).await.ok().flatten()
    } else {
        in_page(|b| b.events.position_of(seq, filter))
    }
}

// --- Preferences ---

/// The browser stand-in's computer: Linux, with isolation available unless the page address
/// asks otherwise (`?isolation=unavailable`, for testing the question before a run).
fn stand_in_setup() -> SetupStatus {
    let unavailable = web_sys::window().and_then(|w| w.location().search().ok()).is_some_and(|q| q.contains("isolation=unavailable"));
    let isolation = if unavailable {
        Isolation { supported: true, available: false, problem: Some(t!("isolation-unavailable-appimage", detail = "Permission denied (os error 13)")) } // not-ui-text: a system error as the stand-in reports it
    } else {
        Isolation { supported: true, available: true, problem: None }
    };
    SetupStatus {
        version: env!("CARGO_PKG_VERSION").into(),
        os: "linux".into(), // not-ui-text: an operating system id
        java: "/opt/javabyte/lib/javabyte/runtime/bin/java".into(), // not-ui-text: a path
        java_bundled: true,
        java_version: Some("21.0.5".into()),
        jar: "/opt/javabyte/lib/javabyte/javabyte.jar".into(), // not-ui-text: a path
        output_folder: "/home/analyst/Documents/JavaByte".into(), // not-ui-text: a path
        bridge: None,
        isolation,
        preferences: in_page(|b| b.preferences.clone()),
    }
}

/// What the app found, and the preferences in use.
pub async fn setup_status() -> Result<SetupStatus, String> {
    if in_desktop_app() { invoke("setup_status", NoArgs {}).await } else { Ok(stand_in_setup()) }
}

/// A preference the screens may change (paths are chosen with pickers instead).
#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum PreferenceChange {
    /// Start runs with no network (Linux).
    Isolate(bool),
    /// Ask before a run that cannot be isolated.
    AskWithoutIsolation(bool),
    /// Use the Java that came with the app again.
    BundledJava,
    /// Save runs in Documents/JavaByte again.
    DefaultOutputFolder,
}

#[derive(Serialize)]
struct ChangeArgs {
    change: PreferenceChange,
}

/// Changes one preference and returns the new state.
pub async fn change_preference(change: PreferenceChange) -> Result<SetupStatus, String> {
    if in_desktop_app() {
        invoke("change_preference", ChangeArgs { change }).await
    } else {
        in_page(|b| match change {
            PreferenceChange::Isolate(on) => b.preferences.isolate = on,
            PreferenceChange::AskWithoutIsolation(ask) => b.preferences.ask_without_isolation = ask,
            PreferenceChange::BundledJava => b.preferences.java = None,
            PreferenceChange::DefaultOutputFolder => b.preferences.output_folder = None,
        });
        Ok(stand_in_setup())
    }
}

/// Picks a Java runtime with the system's picker. None when cancelled.
pub async fn choose_java() -> Result<Option<SetupStatus>, String> {
    if in_desktop_app() { invoke("choose_java", NoArgs {}).await } else { Ok(None) }
}

/// Picks the folder analyses are saved in. None when cancelled.
pub async fn choose_output_folder() -> Result<Option<SetupStatus>, String> {
    if in_desktop_app() { invoke("choose_output_folder", NoArgs {}).await } else { Ok(None) }
}

/// The built-in host policy, rule by rule.
pub async fn tool_policy() -> Result<javabyte_gui_protocol::tools::PolicyListing, String> {
    if in_desktop_app() { invoke("tool_policy", NoArgs {}).await } else { serde_json::from_str(SAMPLE_POLICY).map_err(|e| e.to_string()) }
}

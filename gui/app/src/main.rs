//! The JavaByte desktop app: a window around the screens in `ui/`, and the commands they call.
//!
//! The commands are a thin layer over `javabyte_gui_backend::session::Session`, which holds all
//! the logic and is tested without a window. Analyses run in a separate Java process through the
//! session's runner.

// No console window behind the app on Windows in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod guard;
#[cfg(feature = "egress-probe")]
mod egress_probe;

use javabyte_gui_backend::runner::Launcher;
use javabyte_gui_backend::session::Session;
use javabyte_gui_protocol::t;
use javabyte_gui_protocol::schema::Schema;
use javabyte_gui_protocol::validate::Validation;
use javabyte_gui_protocol::views::{EngineStatus, Imported, Overview, Preset, RecentRun, ReportView, SampleChoice};
use javabyte_gui_protocol::{Page, PageRequest, RunStatus};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use tauri::Manager;
use tauri_plugin_dialog::DialogExt;

struct AppState {
    session: Mutex<Session>,
    /// The last file dropped on the window, waiting for the start screen to take it.
    dropped: Mutex<Option<PathBuf>>,
}

fn session<'a>(state: &'a tauri::State<'_, AppState>) -> std::sync::MutexGuard<'a, Session> {
    // A panic while holding the lock would poison it; the session's state is still usable.
    state.session.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

// Every command the screens can call is listed here and nowhere else. Paths come from the
// system's file picker or from a file the person dropped; the screens never supply a path of
// their own, and nothing here opens, runs or reads the sample: only the Java tool does.

#[tauri::command]
fn engine_status(state: tauri::State<'_, AppState>) -> EngineStatus {
    session(&state).engine()
}

/// Opens the system file picker. Async so the picker does not block the window's thread.
#[tauri::command]
async fn choose_sample(app: tauri::AppHandle) -> Result<Option<SampleChoice>, String> {
    let picked = app
        .dialog()
        .file()
        .set_title(t!("picker-sample"))
        .add_filter(t!("picker-java-files"), &["jar", "zip", "class"])
        .add_filter(t!("picker-all-files"), &["*"])
        .blocking_pick_file();
    match picked.and_then(|p| p.into_path().ok()) {
        Some(path) => Session::choose(&path).map(Some),
        None => Ok(None),
    }
}

/// The same picker for a folder of classes.
#[tauri::command]
async fn choose_sample_folder(app: tauri::AppHandle) -> Result<Option<SampleChoice>, String> {
    match app.dialog().file().set_title(t!("picker-folder")).blocking_pick_folder().and_then(|p| p.into_path().ok()) {
        Some(path) => Session::choose(&path).map(Some),
        None => Ok(None),
    }
}

/// A file dropped on the window since the last call, if any.
#[tauri::command]
fn take_dropped(state: tauri::State<'_, AppState>) -> Result<Option<SampleChoice>, String> {
    let dropped = state.dropped.lock().map_err(|_| "busy")?.take();
    dropped.map(|path| Session::choose(&path)).transpose()
}

#[tauri::command]
async fn inspect_sample(sample: SampleChoice, state: tauri::State<'_, AppState>) -> Result<Overview, String> {
    // Only paths the picker or a drop produced reach here, and the session re-checks them.
    Session::choose(Path::new(&sample.path))?;
    session(&state).inspect(sample)
}

#[tauri::command]
fn command_line(state: tauri::State<'_, AppState>) -> String {
    session(&state).command_line()
}

#[tauri::command]
fn start_run(state: tauri::State<'_, AppState>) -> Result<(), String> {
    session(&state).start()
}

// --- Settings ---

#[tauri::command]
fn settings_text(state: tauri::State<'_, AppState>) -> Option<String> {
    session(&state).settings_text()
}

#[tauri::command]
fn set_settings_text(text: String, state: tauri::State<'_, AppState>) {
    session(&state).set_settings_text(text)
}

#[tauri::command]
async fn config_schema(state: tauri::State<'_, AppState>) -> Result<Schema, String> {
    session(&state).schema()
}

#[tauri::command]
async fn check_settings(text: String, state: tauri::State<'_, AppState>) -> Result<Validation, String> {
    // The check runs the tool for about a second; the session is held only to read what it
    // needs, so the Running screen's updates are not held up.
    let checker = session(&state).checker()?;
    checker.check(&text)
}

#[tauri::command]
fn presets(state: tauri::State<'_, AppState>) -> Vec<Preset> {
    session(&state).presets()
}

#[tauri::command]
fn presets_folder(state: tauri::State<'_, AppState>) -> String {
    session(&state).presets_folder().display().to_string()
}

#[tauri::command]
fn save_preset(name: String, text: String, state: tauri::State<'_, AppState>) -> Result<Preset, String> {
    session(&state).save_preset(&name, &text)
}

#[tauri::command]
fn load_preset(name: String, state: tauri::State<'_, AppState>) -> Result<String, String> {
    session(&state).load_preset(&name)
}

#[tauri::command]
fn delete_preset(name: String, state: tauri::State<'_, AppState>) -> Result<(), String> {
    session(&state).delete_preset(&name)
}

/// Opens a settings file the person picks in the system picker.
#[tauri::command]
async fn import_settings(app: tauri::AppHandle) -> Result<Option<Imported>, String> {
    let picked = app
        .dialog()
        .file()
        .set_title(t!("picker-import"))
        .add_filter(t!("picker-settings-files"), &["yaml", "yml", "json"])
        .add_filter(t!("picker-all-files"), &["*"])
        .blocking_pick_file();
    match picked.and_then(|p| p.into_path().ok()) {
        Some(path) => Session::import(&path).map(Some),
        None => Ok(None),
    }
}

/// Saves the settings where the person chooses in the system picker. `true` if saved.
#[tauri::command]
async fn export_settings(text: String, app: tauri::AppHandle) -> Result<bool, String> {
    let picked = app
        .dialog()
        .file()
        .set_title(t!("picker-export"))
        .set_file_name("javabyte-settings.yaml")
        .add_filter(t!("picker-settings-files"), &["yaml", "yml"])
        .blocking_save_file();
    match picked.and_then(|p| p.into_path().ok()) {
        Some(path) => Session::export(&path, &text).map(|()| true),
        None => Ok(false),
    }
}

/// A file on this computer for a setting that reads one (a reply body, a file for the pretend
/// disk). Only the path is returned; the tool reads the file when it runs.
#[tauri::command]
async fn choose_host_file(app: tauri::AppHandle) -> Option<String> {
    app.dialog()
        .file()
        .set_title(t!("picker-host-file"))
        .blocking_pick_file()
        .and_then(|p| p.into_path().ok())
        .map(|p| p.display().to_string())
}

#[tauri::command]
fn run_status(state: tauri::State<'_, AppState>) -> RunStatus {
    session(&state).status()
}

#[tauri::command]
fn stop_run(state: tauri::State<'_, AppState>) -> Result<(), String> {
    session(&state).stop()
}

#[tauri::command]
fn current_report(state: tauri::State<'_, AppState>) -> Result<ReportView, String> {
    session(&state).report()
}

#[tauri::command]
fn recent_runs(state: tauri::State<'_, AppState>) -> Vec<RecentRun> {
    session(&state).recent()
}

#[tauri::command]
fn open_recent(id: String, state: tauri::State<'_, AppState>) -> Result<ReportView, String> {
    session(&state).open_recent(&id)
}

#[tauri::command]
fn events_page(request: PageRequest, state: tauri::State<'_, AppState>) -> Page {
    session(&state).events_page(&request)
}

/// The JavaByte tool to drive. Until the installer bundles it (phase 5): `JAVABYTE_JAR`, then
/// `javabyte.jar` next to the app, then the `target/` folder of a source checkout.
fn find_engine() -> Result<Launcher, String> {
    let mut candidates: Vec<PathBuf> = std::env::var_os("JAVABYTE_JAR").map(PathBuf::from).into_iter().collect();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            candidates.push(dir.join("javabyte.jar"));
            for ancestor in dir.ancestors() {
                if let Ok(entries) = std::fs::read_dir(ancestor.join("target")) {
                    candidates.extend(entries.flatten().map(|e| e.path()).filter(|p| {
                        p.file_name().is_some_and(|n| {
                            let n = n.to_string_lossy();
                            n.starts_with("javabyte-") && n.ends_with(".jar") && !n.contains("original")
                        })
                    }));
                }
            }
        }
    }
    let jar = candidates.into_iter().find(|p| p.is_file()).ok_or_else(|| t!("error-engine-missing"))?;
    let launcher = Launcher::find(jar)?;
    // Java must answer before anything is promised to the person.
    let works = std::process::Command::new(&launcher.java)
        .arg("-version")
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .is_ok_and(|s| s.success());
    if works {
        Ok(launcher)
    } else {
        Err(t!("error-java-missing"))
    }
}

fn main() {
    let builder = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            // Results go where people can find them: Documents/JavaByte, one folder per run.
            let documents = app.path().document_dir().or_else(|_| app.path().app_data_dir())?;
            let data = app.path().app_data_dir()?;
            app.manage(AppState {
                session: Mutex::new(Session::new(find_engine(), documents.join("JavaByte"), data.join("recent-runs.json"))),
                // A file named on the command line ("Open with" in a file manager) is treated
                // like a dropped one: the start screen picks it up and inspects it.
                dropped: Mutex::new(std::env::args_os().nth(1).map(PathBuf::from)),
            });
            Ok(())
        })
        .on_window_event(|window, event| {
            // A dropped file is only noted; the start screen asks for it and inspects it.
            if let tauri::WindowEvent::DragDrop(tauri::DragDropEvent::Drop { paths, .. }) = event {
                if let (Some(first), Some(state)) = (paths.first(), window.try_state::<AppState>()) {
                    if let Ok(mut slot) = state.dropped.lock() {
                        *slot = Some(first.clone());
                    }
                }
            }
        });
    #[cfg(not(feature = "egress-control"))]
    let builder = builder.plugin(guard::plugin());
    macro_rules! commands {
        ($($extra:path),*) => {
            tauri::generate_handler![engine_status, choose_sample, choose_sample_folder, take_dropped, inspect_sample,
                command_line, start_run, run_status, stop_run, current_report, recent_runs, open_recent, events_page,
                settings_text, set_settings_text, config_schema, check_settings, presets, presets_folder, save_preset,
                load_preset, delete_preset, import_settings, export_settings, choose_host_file $(, $extra)*]
        };
    }
    #[cfg(not(feature = "egress-probe"))]
    let builder = builder.invoke_handler(commands!());
    #[cfg(feature = "egress-probe")]
    let builder = egress_probe::install(builder).invoke_handler(commands!(egress_probe::probe_report));
    builder.run(tauri::generate_context!()).expect("the JavaByte window could not be opened");
}

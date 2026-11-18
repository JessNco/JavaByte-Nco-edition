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

use javabyte_gui_backend::session::Session;
use javabyte_gui_backend::setup::Setup;
use javabyte_gui_protocol::t;
use javabyte_gui_protocol::schema::Schema;
use javabyte_gui_protocol::validate::Validation;
use javabyte_gui_protocol::compare::Comparison;
use javabyte_gui_protocol::preferences::{self, PreferenceChange, RunQuestions};
use javabyte_gui_protocol::views::{AppPreferences, Artifact, EngineStatus, Isolation, SetupStatus, FilePage, Imported, Overview, Preset, RecentRun, ReportView, SampleChoice};
use javabyte_gui_protocol::{EventFilter, Page, PageRequest, RunStatus};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use tauri::Manager;
use tauri_plugin_dialog::DialogExt;

struct AppState {
    session: Mutex<Session>,
    /// Where the app's parts and preferences are.
    setup: Setup,
    /// Whether runs can be isolated from the network, checked once at start.
    isolation: Isolation,
    /// The last file dropped on the window, waiting for the start screen to take it.
    dropped: Mutex<Option<PathBuf>>,
    /// The last sample path the picker, a drop or the command line produced: the only one
    /// `inspect_sample` accepts.
    chosen: Mutex<Option<PathBuf>>,
}

/// The session, locked for this command. Commands holding a `tauri::State` pass it as it is,
/// through `Deref`.
fn session(state: &AppState) -> std::sync::MutexGuard<'_, Session> {
    // A panic while holding the lock would poison it; the session's state is still usable.
    state.session.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Checks a path the picker, a drop or the command line produced, and remembers it as the one
/// sample path `inspect_sample` will accept.
fn choose(state: &AppState, path: &Path) -> Result<SampleChoice, String> {
    let choice = Session::choose(path)?;
    *state.chosen.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(path.to_path_buf());
    Ok(choice)
}

/// Whether `sample` names the path the picker, a drop or the command line last produced. A
/// path on another computer is refused first, before anything compares or looks at it.
fn accept_choice(state: &AppState, sample: &SampleChoice) -> Result<(), String> {
    let path = Path::new(&sample.path);
    if !javabyte_gui_backend::session::is_local_path(path) {
        return Err(t!("error-sample-remote", path = sample.path.as_str()));
    }
    let chosen = state.chosen.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    match chosen.as_deref() {
        Some(chosen) if chosen == path => Ok(()),
        _ => Err(t!("error-sample-unchosen")),
    }
}

// Every command the screens can call is listed here and nowhere else. Paths come from the
// system's file picker, a file the person dropped, or the command line. inspect_sample accepts
// only the path one of those produced, and the session refuses paths on other computers.
// Nothing here opens, runs or reads the sample: only the Java tool does.

/// Whether Java and the tool were found, and if not, why.
#[tauri::command]
fn engine_status(state: tauri::State<'_, AppState>) -> EngineStatus {
    session(&state).engine()
}

/// Opens the system file picker. Async so the picker does not block the window's thread.
#[tauri::command]
async fn choose_sample(app: tauri::AppHandle, state: tauri::State<'_, AppState>) -> Result<Option<SampleChoice>, String> {
    let picked = app
        .dialog()
        .file()
        .set_title(t!("picker-sample"))
        .add_filter(t!("picker-java-files"), &["jar", "zip", "class"])
        .add_filter(t!("picker-all-files"), &["*"])
        .blocking_pick_file();
    match picked.and_then(|p| p.into_path().ok()) {
        Some(path) => choose(&state, &path).map(Some),
        None => Ok(None),
    }
}

/// The same picker for a folder of classes.
#[tauri::command]
async fn choose_sample_folder(app: tauri::AppHandle, state: tauri::State<'_, AppState>) -> Result<Option<SampleChoice>, String> {
    match app.dialog().file().set_title(t!("picker-folder")).blocking_pick_folder().and_then(|p| p.into_path().ok()) {
        Some(path) => choose(&state, &path).map(Some),
        None => Ok(None),
    }
}

/// A file dropped on the window since the last call, if any.
#[tauri::command]
fn take_dropped(state: tauri::State<'_, AppState>) -> Result<Option<SampleChoice>, String> {
    let dropped = state.dropped.lock().map_err(|_| "busy")?.take();
    dropped.map(|path| choose(&state, &path)).transpose()
}

/// Describes the chosen sample and starts its settings afresh. The path must be the one the
/// picker, a drop or the command line last produced ([`accept_choice`]); the session checks
/// it again.
#[tauri::command]
async fn inspect_sample(sample: SampleChoice, state: tauri::State<'_, AppState>) -> Result<Overview, String> {
    accept_choice(&state, &sample)?;
    let checked = Session::choose(Path::new(&sample.path))?;
    session(&state).inspect(checked)
}

/// The same analysis as a command line, for the shell people usually have here.
#[tauri::command]
fn command_line(state: tauri::State<'_, AppState>) -> String {
    session(&state).command_line()
}

/// Whether starting a run needs the person's agreement first, and why: isolation is on in
/// Preferences, the system does not allow it, and they have not said to stop asking.
/// The rule itself is shared with the screens' browser stand-in, in
/// `javabyte_gui_protocol::preferences::run_question`.
fn isolation_question(state: &AppState) -> Option<String> {
    preferences::run_question(&state.isolation, &state.setup.preferences())
}

/// What to ask before the next run, if anything: why it cannot be isolated (see
/// `isolation_question`), and the program on this computer its settings would start.
#[tauri::command]
fn run_question(state: tauri::State<'_, AppState>) -> RunQuestions {
    RunQuestions { isolation: isolation_question(&state), program: session(&state).program_question() }
}

/// Starts the analysis again in an AppContainer, after Windows Sandbox could not start and the
/// person chose the lighter isolation for this run (docs/gui/WINDOWS-SANDBOX.md).
#[tauri::command]
fn start_run_without_sandbox(state: tauri::State<'_, AppState>) -> Result<(), String> {
    session(&state).start_without_sandbox()
}

/// Starts the analysis of the chosen sample with its settings. One at a time. Refused when a
/// question must be answered first and the answers do not cover it: `without_isolation` for
/// the isolation question, and `program`, the path the person agreed to, for a program the
/// settings would start.
#[tauri::command]
fn start_run(without_isolation: bool, program: Option<String>, state: tauri::State<'_, AppState>) -> Result<(), String> {
    if isolation_question(&state).is_some() && !without_isolation {
        return Err(t!("error-isolation-unconfirmed"));
    }
    session(&state).start(program.as_deref())
}

// --- Preferences ---

/// Saves the preferences and makes the session use them.
fn apply_preferences(state: &AppState, preferences: &AppPreferences) -> Result<SetupStatus, String> {
    state.setup.save_preferences(preferences)?;
    let launcher = state.setup.launcher(preferences, &state.isolation);
    session(state).reconfigure(launcher, state.setup.output_folder(preferences));
    Ok(state.setup.status(preferences, &state.isolation))
}

/// What the app found and the preferences in use, for the Preferences screen.
#[tauri::command]
async fn setup_status(state: tauri::State<'_, AppState>) -> Result<SetupStatus, String> {
    Ok(state.setup.status(&state.setup.preferences(), &state.isolation))
}

/// Changes one preference.
#[tauri::command]
fn change_preference(change: PreferenceChange, state: tauri::State<'_, AppState>) -> Result<SetupStatus, String> {
    let mut preferences = state.setup.preferences();
    change.apply(&mut preferences);
    apply_preferences(&state, &preferences)
}

/// Picks a Java runtime's `java` program. It is checked (it must start, and be 17 or later)
/// before it is saved. None when the person cancelled.
#[tauri::command]
async fn choose_java(app: tauri::AppHandle, state: tauri::State<'_, AppState>) -> Result<Option<SetupStatus>, String> {
    let mut picker = app.dialog().file().set_title(t!("picker-java"));
    if cfg!(windows) {
        picker = picker.add_filter(t!("picker-programs"), &["exe"]);
    }
    let Some(path) = picker.blocking_pick_file().and_then(|p| p.into_path().ok()) else { return Ok(None) };
    let mut preferences = state.setup.preferences();
    preferences.java = Some(path.display().to_string());
    apply_preferences(&state, &preferences).map(Some)
}

/// Picks the folder runs are saved in. None when the person cancelled.
#[tauri::command]
async fn choose_output_folder(app: tauri::AppHandle, state: tauri::State<'_, AppState>) -> Result<Option<SetupStatus>, String> {
    let Some(path) = app.dialog().file().set_title(t!("picker-output-folder")).blocking_pick_folder().and_then(|p| p.into_path().ok()) else {
        return Ok(None);
    };
    let mut preferences = state.setup.preferences();
    preferences.output_folder = Some(path.display().to_string());
    apply_preferences(&state, &preferences).map(Some)
}

// --- Reports and their files ---

/// Opens a report the person picks: a `report.json` file.
#[tauri::command]
async fn open_report_file(app: tauri::AppHandle, state: tauri::State<'_, AppState>) -> Result<Option<ReportView>, String> {
    let picked = app
        .dialog()
        .file()
        .set_title(t!("picker-open-report"))
        .add_filter(t!("picker-report-files"), &["json"])
        .blocking_pick_file();
    match picked.and_then(|p| p.into_path().ok()) {
        Some(path) => session(&state).open_report_at(&path).map(Some),
        None => Ok(None),
    }
}

/// Opens a report from a results folder the person picks.
#[tauri::command]
async fn open_report_folder(app: tauri::AppHandle, state: tauri::State<'_, AppState>) -> Result<Option<ReportView>, String> {
    match app.dialog().file().set_title(t!("picker-open-report-folder")).blocking_pick_folder().and_then(|p| p.into_path().ok()) {
        Some(path) => session(&state).open_report_at(&path).map(Some),
        None => Ok(None),
    }
}

/// The open report's saved files, with size, SHA-256 and origin.
#[tauri::command]
async fn artifacts(state: tauri::State<'_, AppState>) -> Result<Vec<Artifact>, String> {
    session(&state).artifacts()
}

/// One 64 KB page of a saved file. Only the report's own files, inside its folder.
#[tauri::command]
fn artifact_page(path: String, offset: u64, state: tauri::State<'_, AppState>) -> Result<FilePage, String> {
    session(&state).artifact_page(&path, offset)
}

/// A saved class as readable instructions, from `javabyte disasm`.
#[tauri::command]
async fn artifact_disasm(path: String, state: tauri::State<'_, AppState>) -> Result<String, String> {
    session(&state).artifact_disasm(&path)
}

/// Saves the chosen files in a password zip where the person picks. `true` if saved.
#[tauri::command]
async fn save_zip(paths: Vec<String>, app: tauri::AppHandle, state: tauri::State<'_, AppState>) -> Result<bool, String> {
    let picked = app
        .dialog()
        .file()
        .set_title(t!("picker-save-zip"))
        .set_file_name("javabyte-files.zip")
        .add_filter(t!("picker-zip-files"), &["zip"])
        .blocking_save_file();
    match picked.and_then(|p| p.into_path().ok()) {
        Some(path) => session(&state).save_zip(&paths, &path).map(|()| true),
        None => Ok(false),
    }
}

/// Saves one file as it is, after the warning the screen shows. `true` if saved.
#[tauri::command]
async fn save_as_is(path: String, app: tauri::AppHandle, state: tauri::State<'_, AppState>) -> Result<bool, String> {
    let name = path.rsplit('/').next().unwrap_or("file").to_string();
    let picked = app.dialog().file().set_title(t!("picker-save-as-is")).set_file_name(name).blocking_save_file();
    match picked.and_then(|p| p.into_path().ok()) {
        Some(destination) => session(&state).save_as_is(&path, &destination).map(|()| true),
        None => Ok(false),
    }
}

/// Compares two runs from the recent list, by id.
#[tauri::command]
async fn compare_runs(a: String, b: String, state: tauri::State<'_, AppState>) -> Result<Comparison, String> {
    session(&state).compare(&a, &b)
}

/// The open report's sample and settings, ready to change and analyse again.
#[tauri::command]
async fn run_again(state: tauri::State<'_, AppState>) -> Result<Overview, String> {
    session(&state).run_again()
}

// --- Settings ---

/// The settings for the next analysis, as YAML.
#[tauri::command]
fn settings_text(state: tauri::State<'_, AppState>) -> Option<String> {
    session(&state).settings_text()
}

/// Replaces the settings, as the settings screen sends them after each change.
#[tauri::command]
fn set_settings_text(text: String, state: tauri::State<'_, AppState>) {
    session(&state).set_settings_text(text)
}

/// Every option the tool has, for building the forms.
#[tauri::command]
async fn config_schema(state: tauri::State<'_, AppState>) -> Result<Schema, String> {
    session(&state).schema()
}

/// The built-in host policy, rule by rule, for the Tools screen.
#[tauri::command]
async fn tool_policy(state: tauri::State<'_, AppState>) -> Result<javabyte_gui_protocol::tools::PolicyListing, String> {
    session(&state).policy()
}

/// Every problem the tool finds in some settings.
#[tauri::command]
async fn check_settings(text: String, state: tauri::State<'_, AppState>) -> Result<Validation, String> {
    // The check runs the tool for about a second; the session is held only to read what it
    // needs, so the Running screen's updates are not held up.
    let checker = session(&state).checker()?;
    checker.check(&text)
}

/// Saved presets, by name.
#[tauri::command]
fn presets(state: tauri::State<'_, AppState>) -> Vec<Preset> {
    session(&state).presets()
}

/// Where saved presets are kept, for people to copy.
#[tauri::command]
fn presets_folder(state: tauri::State<'_, AppState>) -> String {
    session(&state).presets_folder().display().to_string()
}

/// Saves settings as a preset (without the sample). The name is cleaned to a file name.
#[tauri::command]
fn save_preset(name: String, text: String, state: tauri::State<'_, AppState>) -> Result<Preset, String> {
    session(&state).save_preset(&name, &text)
}

/// A saved preset's settings, for the chosen sample.
#[tauri::command]
fn load_preset(name: String, state: tauri::State<'_, AppState>) -> Result<String, String> {
    session(&state).load_preset(&name)
}

/// Deletes a saved preset.
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

/// The running analysis as the Running screen shows it. Called a few times a second.
#[tauri::command]
fn run_status(state: tauri::State<'_, AppState>) -> RunStatus {
    session(&state).status()
}

/// Asks the running analysis to stop; it keeps what it found.
#[tauri::command]
fn stop_run(state: tauri::State<'_, AppState>) -> Result<(), String> {
    session(&state).stop()
}

/// The report of the last finished or opened run.
#[tauri::command]
fn current_report(state: tauri::State<'_, AppState>) -> Result<ReportView, String> {
    session(&state).report()
}

/// Recent analyses, newest first.
#[tauri::command]
fn recent_runs(state: tauri::State<'_, AppState>) -> Vec<RecentRun> {
    session(&state).recent()
}

/// Opens a report from the recent list, by id.
#[tauri::command]
fn open_recent(id: String, state: tauri::State<'_, AppState>) -> Result<ReportView, String> {
    session(&state).open_recent(&id)
}

/// The actions in one category of the open report's events, for the filter.
#[tauri::command]
fn event_actions(category: String, state: tauri::State<'_, AppState>) -> Vec<String> {
    session(&state).event_actions(&category)
}

/// Where an event number is in the filtered event list, if it is there.
#[tauri::command]
fn event_position(seq: u64, filter: EventFilter, state: tauri::State<'_, AppState>) -> Option<u64> {
    session(&state).event_position(seq, &filter)
}

/// A page of the open report's events.
#[tauri::command]
fn events_page(request: PageRequest, state: tauri::State<'_, AppState>) -> Page {
    session(&state).events_page(&request)
}

/// Where the app's parts are: its resource folder (where installers put the runtime, the jar
/// and the bridge), its own folder, its data folder and Documents/JavaByte.
fn setup_for(resources: PathBuf, data: PathBuf, documents: Option<PathBuf>) -> Setup {
    let resources = plain_path(resources);
    let exe_dir = std::env::current_exe().ok().and_then(|exe| exe.parent().map(Path::to_path_buf));
    let default_output = documents.unwrap_or_else(|| data.clone()).join("JavaByte");
    Setup { resources, exe_dir, data, default_output }
}

/// Removes Windows' `\\?\` prefix from a path such as `\\?\C:\Program Files\JavaByte`, as
/// Tauri reports its resource folder. Java cannot open a jar named that way ("Could not find or
/// load main class"), and the prefix is only needed for paths longer than 260 characters.
fn plain_path(path: PathBuf) -> PathBuf {
    match path.to_str().and_then(|p| p.strip_prefix(r"\\?\")) {
        // Only drive paths (C:\...); \\?\UNC\ paths would need their own form.
        Some(rest) if rest.as_bytes().get(1) == Some(&b':') => PathBuf::from(rest),
        _ => path,
    }
}

/// `--self-check`: prints what the app found and whether the tool works, then exits. Support
/// can ask for it, and CI uses it to test installers. Exit code 0 when the tool runs.
fn self_check(setup: &Setup) -> i32 {
    let preferences = setup.preferences();
    let isolation = setup.isolation_status();
    let status = setup.status(&preferences, &isolation);
    let yes_no = |found: bool| if found { t!("self-check-yes") } else { t!("self-check-no") };
    let mut lines = vec![
        t!("self-check-version", version = status.version.as_str(), os = status.os.as_str()),
        t!("self-check-java", path = status.java.as_str(), version = status.java_version.clone().unwrap_or_default(), bundled = yes_no(status.java_bundled)),
        t!("self-check-jar", path = status.jar.as_str()),
        t!("self-check-output", path = status.output_folder.as_str()),
    ];
    let launcher = setup.launcher(&preferences, &isolation);
    let tool = launcher.as_ref().map_err(Clone::clone).and_then(|l| l.schema().map(|_| ()));
    lines.push(match &tool {
        Ok(()) => t!("self-check-tool-works"),
        Err(problem) => t!("self-check-tool-fails", detail = problem.as_str()),
    });
    lines.push(match &status.bridge {
        Some(python) => {
            let imports = std::process::Command::new(python)
                .args(["-I", "-W", "ignore", "-c", "import speakeasy"]) // not-ui-text: a Python command
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .status()
                .is_ok_and(|s| s.success());
            t!("self-check-bridge", path = python.as_str(), works = yes_no(imports))
        }
        None => t!("self-check-no-bridge"),
    });
    lines.push(match (&isolation.supported, &isolation.problem) {
        (true, None) => t!("self-check-isolation-on"),
        (_, problem) => t!("self-check-isolation-off", detail = problem.clone().unwrap_or_default()),
    });
    if let Some(helper) = setup.sandbox_helper() {
        lines.push(t!("self-check-sandbox-addon", path = helper.display().to_string()));
        lines.push(match &isolation.strongest_problem {
            None if isolation.strongest_available => t!("self-check-windows-sandbox-on"),
            problem => t!("self-check-windows-sandbox-off", detail = problem.clone().unwrap_or_default()),
        });
    }
    for line in lines {
        println!("{line}");
    }
    if tool.is_ok() { 0 } else { 1 }
}

fn main() {
    let context = tauri::generate_context!();
    if std::env::args_os().nth(1).is_some_and(|a| a == "--self-check") {
        let resources = tauri::utils::platform::resource_dir(context.package_info(), &tauri::Env::default()).unwrap_or_default();
        let data = dirs::data_dir().unwrap_or_default().join(&context.config().identifier);
        std::process::exit(self_check(&setup_for(resources, data, dirs::document_dir())));
    }
    let builder = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            // Results go where people can find them: Documents/JavaByte, one folder per run,
            // unless Preferences name another folder.
            let setup = setup_for(app.path().resource_dir()?, app.path().app_data_dir()?, app.path().document_dir().ok());
            let preferences = setup.preferences();
            let isolation = setup.isolation_status();
            let launcher = setup.launcher(&preferences, &isolation);
            let runs_root = setup.output_folder(&preferences);
            let recent = setup.data.join("recent-runs.json");
            app.manage(AppState {
                session: Mutex::new(Session::new(launcher, runs_root, recent)),
                setup,
                isolation,
                // A file named on the command line ("Open with" in a file manager) is treated
                // like a dropped one: the start screen picks it up and inspects it.
                dropped: Mutex::new(std::env::args_os().nth(1).map(PathBuf::from)),
                chosen: Mutex::new(None),
            });
            Ok(())
        })
        .on_window_event(|window, event| {
            // A dropped file is only noted; the start screen asks for it and inspects it.
            if let tauri::WindowEvent::DragDrop(tauri::DragDropEvent::Drop { paths, .. }) = event
                && let (Some(first), Some(state)) = (paths.first(), window.try_state::<AppState>())
                    && let Ok(mut slot) = state.dropped.lock() {
                        *slot = Some(first.clone());
                    }
        });
    #[cfg(not(feature = "egress-control"))]
    let builder = builder.plugin(guard::plugin());
    macro_rules! commands {
        ($($extra:path),*) => {
            tauri::generate_handler![engine_status, choose_sample, choose_sample_folder, take_dropped, inspect_sample,
                command_line, run_question, start_run, start_run_without_sandbox, setup_status, change_preference, choose_java, choose_output_folder, run_status, stop_run, current_report, recent_runs, open_recent, events_page,
                settings_text, set_settings_text, config_schema, tool_policy, check_settings, presets, presets_folder, save_preset,
                load_preset, delete_preset, import_settings, export_settings, choose_host_file,
                open_report_file, open_report_folder, artifacts, artifact_page, artifact_disasm, save_zip, save_as_is,
                compare_runs, run_again, event_actions, event_position $(, $extra)*]
        };
    }
    #[cfg(not(feature = "egress-probe"))]
    let builder = builder.invoke_handler(commands!());
    #[cfg(feature = "egress-probe")]
    let builder = egress_probe::install(builder).invoke_handler(commands!(egress_probe::probe_report));
    builder.run(context).expect("the JavaByte window could not be opened");
}

#[cfg(test)]
mod choice_tests {
    use super::*;

    /// A state with no engine, for the checks that need no window.
    fn state() -> AppState {
        let dir = std::env::temp_dir().join(format!("javabyte-app-choice-{}", std::process::id()));
        let setup = Setup { resources: dir.clone(), exe_dir: None, data: dir.clone(), default_output: dir.join("out") };
        AppState {
            session: Mutex::new(Session::new(Err("no engine".into()), dir.join("runs"), dir.join("recent.json"))),
            setup,
            isolation: Isolation::default(),
            dropped: Mutex::new(None),
            chosen: Mutex::new(None),
        }
    }

    /// inspect_sample takes only the path the picker, a drop or the command line produced,
    /// and refuses a path on another computer before anything else.
    #[test]
    fn only_the_chosen_path_is_accepted() {
        let state = state();
        let sample = std::env::temp_dir().join(format!("javabyte-app-choice-{}.jar", std::process::id()));
        std::fs::write(&sample, b"PK").unwrap();
        let named = |path: &str| SampleChoice { path: path.into(), name: "x.jar".into() };
        let own = sample.display().to_string();
        assert_eq!(accept_choice(&state, &named(&own)).unwrap_err(), t!("error-sample-unchosen"), "nothing chosen yet");
        let choice = choose(&state, &sample).unwrap();
        assert_eq!(choice.path, own);
        assert!(accept_choice(&state, &choice).is_ok());
        assert_eq!(accept_choice(&state, &named("/etc/passwd")).unwrap_err(), t!("error-sample-unchosen"));
        let remote = r"\\attacker.example\share\x.jar";
        assert_eq!(accept_choice(&state, &named(remote)).unwrap_err(), t!("error-sample-remote", path = remote));
        // A refused choice does not replace the last good one.
        assert!(choose(&state, Path::new(remote)).is_err());
        assert!(accept_choice(&state, &choice).is_ok());
        let _ = std::fs::remove_file(sample);
    }
}

#[cfg(test)]
mod path_tests {
    use super::plain_path;
    use std::path::PathBuf;

    #[test]
    fn verbatim_drive_paths_lose_their_prefix() {
        assert_eq!(plain_path(PathBuf::from(r"\\?\C:\Program Files\JavaByte")), PathBuf::from(r"C:\Program Files\JavaByte"));
        assert_eq!(plain_path(PathBuf::from(r"\\?\UNC\server\share")), PathBuf::from(r"\\?\UNC\server\share"));
        assert_eq!(plain_path(PathBuf::from("/usr/lib/JavaByte")), PathBuf::from("/usr/lib/JavaByte"));
    }
}

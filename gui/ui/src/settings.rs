//! The settings for the next analysis, shared by the overview and the settings screen.
//!
//! The YAML text is the one source of truth (docs/gui/PHASE3-PLAN.md). Form fields change it
//! through [`edit`], which rewrites only the lines concerned; the YAML tab replaces it with
//! [`replace_text`]. Each change is sent to the backend, and the tool checks the settings a
//! moment after typing stops.

use crate::app::{Screen, flow, show};
use crate::bridge;
use crate::util::{announcer, focus_id};
use javabyte_gui_backend::config::{ConfigDoc, ConfigPath, EditError, Shell, SyntaxProblem};
use javabyte_gui_protocol::schema::Schema;
use javabyte_gui_protocol::t;
use javabyte_gui_protocol::validate::Validation;
use leptos::prelude::*;
use leptos::task::spawn_local;
use serde_json::Value;
use std::time::Duration;

/// How long after the last change the tool checks the settings.
const CHECK_PAUSE: Duration = Duration::from_millis(1000);

#[derive(Clone, Copy)]
pub struct Settings {
    /// The YAML text.
    pub text: RwSignal<String>,
    /// The text read as values; `None` while the YAML cannot be read.
    pub json: Memo<Option<Value>>,
    pub syntax: Memo<Vec<SyntaxProblem>>,
    pub schema: RwSignal<Option<Schema>>,
    /// The last check's result, and the text it was for.
    pub validation: RwSignal<Option<(String, Validation)>>,
    pub checking: RwSignal<bool>,
    pub check_error: RwSignal<Option<String>>,
    /// The text before the last preset or import, for Undo.
    pub undo: RwSignal<Option<String>>,
    /// The list item being edited: its list's path and its position.
    pub editing: RwSignal<Option<(String, usize)>>,
    /// 0 for the forms, 1 for the YAML.
    pub tab: RwSignal<usize>,
    /// Changes since start, so a stale check result or timer can be recognised.
    generation: StoredValue<u64>,
}

thread_local! {
    static SETTINGS: std::cell::OnceCell<Settings> = const { std::cell::OnceCell::new() };
}

/// The settings. Page-wide like the flow, because timers and backend replies use them. Created
/// by `App` first, so they live as long as the page.
pub fn settings() -> Settings {
    SETTINGS.with(|cell| {
        *cell.get_or_init(|| {
            let text = RwSignal::new(String::new());
            Settings {
                text,
                json: Memo::new(move |_| text.with(|t| ConfigDoc::new(t.as_str()).to_json())),
                syntax: Memo::new(move |_| text.with(|t| ConfigDoc::new(t.as_str()).syntax_problems())),
                schema: RwSignal::new(None),
                validation: RwSignal::new(None),
                checking: RwSignal::new(false),
                check_error: RwSignal::new(None),
                undo: RwSignal::new(None),
                editing: RwSignal::new(None),
                tab: RwSignal::new(0),
                generation: StoredValue::new(0),
            }
        })
    })
}

impl Settings {
    /// Whether the last check is for the text as it is now.
    pub fn checked(&self) -> bool {
        self.validation.with(|v| v.as_ref().is_some_and(|(text, _)| self.text.with(|t| t == text)))
    }

    /// Problems found by the last check of the current text.
    pub fn problems(&self) -> Vec<javabyte_gui_protocol::validate::Problem> {
        if !self.checked() {
            return Vec::new();
        }
        self.validation.with(|v| v.as_ref().map(|(_, v)| v.problems.clone()).unwrap_or_default())
    }

    /// The value at a path, if set.
    pub fn value(&self, path: &ConfigPath) -> Option<Value> {
        self.json.with(|json| {
            let mut node = json.as_ref()?;
            for step in &path.0 {
                node = match (step, node) {
                    (javabyte_gui_backend::config::Step::Key(k), Value::Object(m)) => m.get(k)?,
                    (javabyte_gui_backend::config::Step::Index(i), Value::Array(a)) => a.get(*i)?,
                    _ => return None,
                };
            }
            Some(node.clone())
        })
    }
}

/// Loads the settings for a newly chosen sample from the backend, and the schema once.
pub fn load() {
    let s = settings();
    spawn_local(async move {
        match bridge::settings_text().await {
            Ok(Some(text)) => {
                s.validation.set(None);
                s.undo.set(None);
                s.editing.set(None);
                s.text.set(text);
                schedule_check();
            }
            Ok(None) => {}
            Err(message) => s.check_error.set(Some(message)),
        }
        if s.schema.get_untracked().is_none() {
            match bridge::config_schema().await {
                Ok(schema) => s.schema.set(Some(schema)),
                Err(message) => s.check_error.set(Some(message)),
            }
        }
    });
}

/// Changes the settings through the config document. The error, if any, is for people.
pub fn edit(change: impl FnOnce(&mut ConfigDoc) -> Result<(), EditError>) -> Result<(), String> {
    let s = settings();
    let mut doc = ConfigDoc::new(s.text.get_untracked());
    change(&mut doc).map_err(|e| match e {
        EditError::Syntax(problems) => problems.first().map_or_else(String::new, |p| t!("syntax-problem", line = p.line, column = p.column, message = p.message.clone())),
        EditError::Shape(message) => message,
    })?;
    set_text(doc.text().to_string());
    Ok(())
}

/// Sets one value, or removes it when `value` is `None` (back to the default).
pub fn set_value(path: &ConfigPath, value: Option<Value>) {
    let result = edit(|doc| match &value {
        Some(value) => doc.set(path, value),
        None => doc.remove(path),
    });
    if let Err(message) = result {
        announcer().say(message);
    }
}

/// Replaces the whole text, as the YAML tab, a preset or an import does.
pub fn replace_text(text: String) {
    set_text(text);
}

/// Sets the text, sends it to the backend, and schedules a check.
fn set_text(text: String) {
    let s = settings();
    if s.text.with_untracked(|t| *t == text) {
        return;
    }
    s.text.set(text.clone());
    spawn_local(async move {
        let _ = bridge::set_settings_text(&text).await;
    });
    schedule_check();
}

/// Checks the settings once changes have paused.
fn schedule_check() {
    let s = settings();
    let generation = s.generation.get_value() + 1;
    s.generation.set_value(generation);
    set_timeout(
        move || {
            if s.generation.get_value() == generation {
                spawn_local(async move {
                    check_now().await;
                });
            }
        },
        CHECK_PAUSE,
    );
}

/// Checks the current text with the tool now. Problems are announced politely.
pub async fn check_now() -> Option<Validation> {
    let s = settings();
    let text = s.text.get_untracked();
    if text.is_empty() {
        return None;
    }
    s.checking.set(true);
    let result = bridge::check_settings(&text).await;
    s.checking.set(false);
    match result {
        Ok(validation) => {
            s.check_error.set(None);
            // A reply for text that has since changed is old news.
            if s.text.with_untracked(|t| *t == text) {
                let count = validation.problems.len();
                let was_valid = s.validation.with_untracked(|v| v.as_ref().is_none_or(|(_, v)| v.valid));
                s.validation.set(Some((text, validation.clone())));
                if count > 0 {
                    announcer().say(t!("check-problems", count = count));
                } else if !was_valid {
                    announcer().say(t!("check-ok"));
                }
            }
            Some(validation)
        }
        Err(message) => {
            s.check_error.set(Some(t!("check-failed", detail = message)));
            None
        }
    }
}

/// The shell the copied command line is written for: PowerShell on Windows.
pub fn shell() -> Shell {
    let windows = web_sys::window().and_then(|w| w.navigator().user_agent().ok()).is_some_and(|ua| ua.contains("Windows")); // not-ui-text: matched in the browser's own string
    if windows { Shell::PowerShell } else { Shell::Posix }
}

/// The same analysis as a command line.
pub fn command_line() -> String {
    let s = settings();
    let Some(sample) = flow().sample.get() else { return String::new() };
    let json = s.json.get().unwrap_or_default();
    javabyte_gui_backend::config::command_line(&sample.path, &json, shell())
}

/// Starts the analysis, unless the settings have problems: then the settings screen shows them.
pub fn analyse(busy: RwSignal<bool>, on_error: impl Fn(String) + 'static) {
    let s = settings();
    let flow = flow();
    busy.set(true);
    spawn_local(async move {
        let ready = if !s.syntax.get_untracked().is_empty() {
            false
        } else if s.checked() {
            s.validation.with_untracked(|v| v.as_ref().is_some_and(|(_, v)| v.valid))
        } else {
            check_now().await.is_some_and(|v| v.valid)
        };
        if !ready {
            busy.set(false);
            show(Screen::Settings);
            if s.syntax.get_untracked().is_empty() {
                focus_id("settings-problems-title".into());
            } else {
                focus_id("syntax-title".into());
            }
            return;
        }
        // The backend has the text already; send it again in case a change is still on its way.
        let _ = bridge::set_settings_text(&s.text.get_untracked()).await;
        match bridge::start_run().await {
            Ok(()) => {
                flow.run_started.set(true);
                flow.report.set(None);
                show(Screen::Running);
                if let Some(sample) = flow.sample.get_untracked() {
                    announcer().say(t!("overview-started", name = &sample.name));
                }
            }
            Err(message) => on_error(message),
        }
        busy.set(false);
    });
}

/// **Run again with changes**: the open report's sample and settings, in Settings. With a
/// setting (from Limits and gaps), that field is shown and focused once the forms are ready.
pub fn run_again_and_show(setting: Option<String>) {
    let flow = flow();
    spawn_local(async move {
        match bridge::run_again().await {
            Ok(overview) => {
                flow.sample.set(Some(overview.sample.clone()));
                flow.overview.set(Some(overview));
                load();
                show(Screen::Settings);
                if let Some(setting) = setting {
                    focus_when_ready(javabyte_gui_protocol::form::field_element_id(&setting), 40);
                }
            }
            Err(message) => announcer().say(message),
        }
    });
}

/// Focuses an element once it exists (the forms appear after the settings load), opening any
/// folded group around it. Gives up quietly after `tries` attempts a tenth of a second apart.
fn focus_when_ready(id: String, tries: u32) {
    set_timeout(
        move || match document().get_element_by_id(&id) {
            Some(element) if settings().text.with_untracked(|t| !t.is_empty()) => {
                settings().tab.set(0);
                crate::util::open_disclosures_around(&element);
                focus_id(id);
            }
            _ if tries > 0 => focus_when_ready(id, tries - 1),
            _ => {}
        },
        Duration::from_millis(100),
    );
}

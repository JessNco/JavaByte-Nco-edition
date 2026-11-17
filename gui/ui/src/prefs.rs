//! Display preferences: theme, text size, motion and how often progress is announced.
//!
//! By default everything follows the operating system. The choices here are for people whose
//! system settings do not suit this app, and they are remembered in the web view's storage.

use crate::util::announcer;
use javabyte_gui_protocol::t;
use leptos::prelude::*;
use serde::{Deserialize, Serialize};

const STORAGE_KEY: &str = "javabyte.preferences";

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Preferences {
    /// `system`, `light`, `dark` or `contrast`.
    pub theme: String,
    /// Percentage of the system text size.
    pub text_size: u32,
    /// `system` or `reduce`.
    pub motion: String,
    /// Seconds between progress announcements during a run; 0 means only start and finish.
    pub announce_every: u32,
}

impl Default for Preferences {
    fn default() -> Self {
        Self { theme: "system".into(), text_size: 100, motion: "system".into(), announce_every: 15 }
    }
}

impl Preferences {
    /// The saved preferences, or the defaults.
    pub fn load() -> Self {
        storage()
            .and_then(|s| s.get_item(STORAGE_KEY).ok().flatten())
            .and_then(|json| serde_json::from_str(&json).ok())
            .unwrap_or_default()
    }

    /// Remembers the preferences in this browser profile; failures are ignored.
    fn save(&self) {
        if let (Some(storage), Ok(json)) = (storage(), serde_json::to_string(self)) {
            let _ = storage.set_item(STORAGE_KEY, &json);
        }
    }

    /// Applies the preferences to the whole page through attributes the style sheet reads.
    pub fn apply(&self) {
        let Some(root) = document().document_element() else { return };
        let _ = root.set_attribute("data-theme", &self.theme);
        let _ = root.set_attribute("data-motion", &self.motion);
        // Set through the style object, not a style attribute, so the strict content security
        // policy (no inline styles) still allows it. Percent of the browser's own default, so
        // the system text size setting still counts.
        if let Ok(html) = wasm_bindgen::JsCast::dyn_into::<web_sys::HtmlElement>(root) {
            let _ = html.style().set_property("font-size", &format!("{}%", self.text_size));
        }
    }
}

/// The browser's local storage, if the page may use it.
fn storage() -> Option<web_sys::Storage> {
    web_sys::window()?.local_storage().ok().flatten()
}

thread_local! {
    static PREFERENCES: std::cell::OnceCell<RwSignal<Preferences>> = const { std::cell::OnceCell::new() };
}

/// The current preferences, loaded on first use. Page-wide for the same reason as the
/// announcer: timers and asynchronous code read them.
pub fn preferences() -> RwSignal<Preferences> {
    PREFERENCES.with(|cell| *cell.get_or_init(|| RwSignal::new(Preferences::load())))
}

/// The display part of the Preferences screen.
#[component]
pub fn DisplayPanel() -> impl IntoView {
    let prefs = preferences();
    let update = move |change: Box<dyn FnOnce(&mut Preferences)>| {
        prefs.update(|p| {
            change(p);
            p.apply();
            p.save();
        });
    };
    // Each theme's label is the message `theme-<value>`.
    let themes = ["system", "light", "dark", "contrast"];
    view! {
        <h3>{t!("display-heading")}</h3>
        <p>{t!("display-intro")}</p>
        <fieldset>
            <legend>{t!("display-colours")}</legend>
            {themes
                .into_iter()
                .map(|value| {
                    let id = format!("theme-{value}");
                    let label = javabyte_gui_protocol::i18n::tr(&format!("theme-{value}"), None);
                    view! {
                        <div class="choice">
                            <input
                                type="radio"
                                name="theme"
                                id=id.clone()
                                value=value
                                prop:checked=move || prefs.with(|p| p.theme == value)
                                on:change=move |_| update(Box::new(move |p| p.theme = value.to_string()))
                            />
                            <label for=id>{label}</label>
                        </div>
                    }
                })
                .collect_view()}
        </fieldset>
        <div class="field">
            <label for="text-size">{t!("display-text-size")}</label>
            <p class="hint" id="text-size-hint">{t!("display-text-size-hint")}</p>
            <select
                id="text-size"
                aria-describedby="text-size-hint"
                on:change=move |ev| {
                    let size = event_target_value(&ev).parse().unwrap_or(100);
                    update(Box::new(move |p| p.text_size = size));
                }
            >
                {[100u32, 125, 150, 175, 200]
                    .into_iter()
                    .map(|size| {
                        view! {
                            <option value=size.to_string() selected=move || prefs.with(|p| p.text_size == size)>
                                {t!("display-text-size-option", percent = size)}
                            </option>
                        }
                    })
                    .collect_view()}
            </select>
        </div>
        <div class="field">
            <label for="motion">{t!("display-animation")}</label>
            <select
                id="motion"
                on:change=move |ev| {
                    let motion = event_target_value(&ev);
                    update(Box::new(move |p| p.motion = motion));
                }
            >
                <option value="system" selected=move || prefs.with(|p| p.motion == "system")>{t!("motion-system")}</option>
                <option value="reduce" selected=move || prefs.with(|p| p.motion == "reduce")>{t!("motion-reduce")}</option>
            </select>
        </div>
        <div class="field">
            <label for="announce-every">{t!("display-announce")}</label>
            <p class="hint" id="announce-every-hint">{t!("display-announce-hint")}</p>
            <select
                id="announce-every"
                aria-describedby="announce-every-hint"
                on:change=move |ev| {
                    let seconds = event_target_value(&ev).parse().unwrap_or(15);
                    update(Box::new(move |p| p.announce_every = seconds));
                }
            >
                {[5u32, 15, 30, 0]
                    .into_iter()
                    .map(|seconds| {
                        let label = if seconds == 0 { t!("announce-never") } else { t!("announce-every", seconds = seconds) };
                        view! {
                            <option value=seconds.to_string() selected=move || prefs.with(|p| p.announce_every == seconds)>
                                {label}
                            </option>
                        }
                    })
                    .collect_view()}
            </select>
        </div>
        <button
            type="button"
            on:click=move |_| {
                update(Box::new(|p| *p = Preferences::default()));
                announcer().say(t!("display-reset-done"));
            }
        >
            {t!("display-reset")}
        </button>
    }
}

//! Display preferences: theme, text size, motion and how often progress is announced.
//!
//! By default everything follows the operating system. The choices here are for people whose
//! system settings do not suit this app, and they are remembered in the web view's storage.

use crate::util::announcer;
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
    pub fn load() -> Self {
        storage()
            .and_then(|s| s.get_item(STORAGE_KEY).ok().flatten())
            .and_then(|json| serde_json::from_str(&json).ok())
            .unwrap_or_default()
    }

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
    let themes = [
        ("system", "Follow the system setting"),
        ("light", "Light"),
        ("dark", "Dark"),
        ("contrast", "High contrast"),
    ];
    view! {
        <h2 id="display-heading" tabindex="-1">"Display settings"</h2>
        <p>"Changes apply at once and are remembered. By default the app follows your system settings."</p>
        <fieldset>
            <legend>"Colours"</legend>
            {themes
                .into_iter()
                .map(|(value, label)| {
                    let id = format!("theme-{value}");
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
            <label for="text-size">"Text size"</label>
            <p class="hint" id="text-size-hint">"Relative to your system's text size. Browser zoom (Control and plus) also works."</p>
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
                                {format!("{size}%")}
                            </option>
                        }
                    })
                    .collect_view()}
            </select>
        </div>
        <div class="field">
            <label for="motion">"Animation"</label>
            <select
                id="motion"
                on:change=move |ev| {
                    let motion = event_target_value(&ev);
                    update(Box::new(move |p| p.motion = motion));
                }
            >
                <option value="system" selected=move || prefs.with(|p| p.motion == "system")>"Follow the system setting"</option>
                <option value="reduce" selected=move || prefs.with(|p| p.motion == "reduce")>"Always reduce"</option>
            </select>
        </div>
        <div class="field">
            <label for="announce-every">"Progress announcements for screen readers"</label>
            <p class="hint" id="announce-every-hint">"How often a running analysis reports its progress. The start and the end are always announced."</p>
            <select
                id="announce-every"
                aria-describedby="announce-every-hint"
                on:change=move |ev| {
                    let seconds = event_target_value(&ev).parse().unwrap_or(15);
                    update(Box::new(move |p| p.announce_every = seconds));
                }
            >
                {[(5u32, "Every 5 seconds"), (15, "Every 15 seconds"), (30, "Every 30 seconds"), (0, "Only start and finish")]
                    .into_iter()
                    .map(|(seconds, label)| {
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
                announcer().say("Display settings reset to follow the system.");
            }
        >
            "Reset to system settings"
        </button>
    }
}

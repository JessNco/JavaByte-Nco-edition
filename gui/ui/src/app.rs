//! The window's frame: skip link, heading, section tabs and the shared live region.

use crate::util::{Announcer, focus_id};
use crate::{events, form, prefs, run};
use leptos::prelude::*;

/// The sections, in tab order: (id stem, tab label).
const SECTIONS: [(&str, &str); 4] =
    [("settings", "Run settings"), ("events", "Events"), ("running", "Running"), ("display", "Display")];

#[component]
pub fn App() -> impl IntoView {
    let announcer = Announcer::new();
    provide_context(announcer);
    let preferences = prefs::Preferences::load();
    preferences.apply();
    provide_context(RwSignal::new(preferences));

    let active = RwSignal::new(0usize);

    // Tabs follow the ARIA Authoring Practices "tabs with automatic activation" pattern: one tab
    // stop for the whole list, arrow keys move between tabs and show their panel at once
    // (panels here appear instantly, so there is no reason to make people press Enter).
    let on_tab_key = move |ev: leptos::ev::KeyboardEvent| {
        let current = active.get_untracked();
        let next = match ev.key().as_str() {
            "ArrowRight" => (current + 1) % SECTIONS.len(),
            "ArrowLeft" => (current + SECTIONS.len() - 1) % SECTIONS.len(),
            "Home" => 0,
            "End" => SECTIONS.len() - 1,
            _ => return,
        };
        ev.prevent_default();
        active.set(next);
        focus_id(format!("tab-{}", SECTIONS[next].0));
    };

    view! {
        <a class="skip-link" href="#main">"Skip to main content"</a>
        <header class="banner">
            <h1>"JavaByte"</h1>
            <p class="subtitle">"Accessibility trial, phase 0. Nothing is analysed and all data is made up."</p>
        </header>
        <main id="main" tabindex="-1">
            <div role="tablist" aria-label="Sections" class="tablist" on:keydown=on_tab_key>
                {SECTIONS
                    .iter()
                    .enumerate()
                    .map(|(index, (stem, label))| {
                        view! {
                            <button
                                type="button"
                                role="tab"
                                id=format!("tab-{stem}")
                                aria-controls=format!("panel-{stem}")
                                aria-selected=move || (active.get() == index).to_string()
                                tabindex=move || if active.get() == index { "0" } else { "-1" }
                                on:click=move |_| active.set(index)
                            >
                                {*label}
                            </button>
                        }
                    })
                    .collect_view()}
            </div>
            // Panels stay in the page when hidden so that a half-filled form or a scrolled list is
            // still there on return. `hidden` removes them from the accessibility tree.
            <section role="tabpanel" id="panel-settings" aria-labelledby="tab-settings" hidden=move || active.get() != 0>
                <form::RunSettings />
            </section>
            <section role="tabpanel" id="panel-events" aria-labelledby="tab-events" hidden=move || active.get() != 1>
                <events::EventsPanel visible=Signal::derive(move || active.get() == 1) />
            </section>
            <section role="tabpanel" id="panel-running" aria-labelledby="tab-running" hidden=move || active.get() != 2>
                <run::RunPanel />
            </section>
            <section role="tabpanel" id="panel-display" aria-labelledby="tab-display" hidden=move || active.get() != 3>
                <prefs::DisplayPanel />
            </section>
        </main>
        <div role="status" aria-live="polite" aria-atomic="true" class="visually-hidden" id="announcer">
            {move || announcer.text()}
        </div>
    }
}

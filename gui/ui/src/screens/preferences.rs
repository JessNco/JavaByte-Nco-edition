//! Preferences: how analyses run (output folder, Java, network isolation, native bridge), and
//! how the app looks (`prefs::DisplayPanel`).
//!
//! Folders and programs are chosen with the system's picker in the backend; this screen never
//! sends a path of its own.

use crate::app::{ErrorBox, Screen, flow, report_error};
use crate::bridge::{self, PreferenceChange};
use crate::prefs;
use crate::util::announcer;
use javabyte_gui_protocol::t;
use javabyte_gui_protocol::views::SetupStatus;
use leptos::prelude::*;
use leptos::task::spawn_local;

/// The Preferences screen.
#[component]
pub fn PreferencesScreen() -> impl IntoView {
    let flow = flow();
    let status = RwSignal::new(None::<SetupStatus>);
    let error = RwSignal::new(None::<String>);
    // Read again each time the screen opens: an install or the files may have changed.
    Effect::new(move |_| {
        if flow.screen.get() == Screen::Preferences {
            spawn_local(async move {
                match bridge::setup_status().await {
                    Ok(s) => status.set(Some(s)),
                    Err(message) => report_error(error, "preferences-error", message),
                }
            });
        }
    });
    // Applies a change and says what happened; `done` is announced on success.
    let apply = move |result: Result<Option<SetupStatus>, String>, done: String| match result {
        Ok(Some(s)) => {
            error.set(None);
            status.set(Some(s));
            announcer().say(done);
        }
        Ok(None) => {} // cancelled
        Err(message) => report_error(error, "preferences-error", message),
    };
    let change = move |change: PreferenceChange, done: String| {
        spawn_local(async move { apply(bridge::change_preference(change).await.map(Some), done) });
    };

    let output = move || {
        status.get().map(|s| {
            let custom = s.preferences.output_folder.is_some();
            view! {
                <h4 id="output-heading">{t!("prefs-output-heading")}</h4>
                <p>{t!("prefs-output-current")} " " <code class="path">{s.output_folder.clone()}</code></p>
                <div class="buttons">
                    <button type="button" id="prefs-choose-output" on:click=move |_| {
                        spawn_local(async move { apply(bridge::choose_output_folder().await, t!("prefs-output-changed")) });
                    }>{t!("prefs-output-choose")}</button>
                    <Show when=move || custom>
                        <button type="button" on:click=move |_| change(PreferenceChange::DefaultOutputFolder, t!("prefs-output-reset-done"))>
                            {t!("prefs-output-reset")}
                        </button>
                    </Show>
                </div>
            }
        })
    };

    let java = move || {
        status.get().map(|s| {
            let version = s.java_version.clone().unwrap_or_else(|| t!("prefs-java-no-version"));
            let current = if s.java_bundled {
                t!("prefs-java-bundled", version = version)
            } else if s.preferences.java.is_some() {
                t!("prefs-java-chosen", version = version)
            } else {
                t!("prefs-java-system", version = version)
            };
            let chosen = s.preferences.java.is_some();
            view! {
                <h4 id="java-heading">{t!("prefs-java-heading")}</h4>
                <p>{current} " " <code class="path">{s.java.clone()}</code></p>
                <div class="buttons">
                    <button type="button" id="prefs-choose-java" aria-describedby="prefs-java-hint" on:click=move |_| {
                        spawn_local(async move { apply(bridge::choose_java().await, t!("prefs-java-changed")) });
                    }>{t!("prefs-java-choose")}</button>
                    <Show when=move || chosen>
                        <button type="button" on:click=move |_| change(PreferenceChange::BundledJava, t!("prefs-java-reset-done"))>
                            {t!("prefs-java-reset")}
                        </button>
                    </Show>
                </div>
                <p class="hint" id="prefs-java-hint">{t!("prefs-java-hint")}</p>
            }
        })
    };

    let isolation = move || {
        status.get().map(|s| {
            let i = s.isolation.clone();
            if !i.supported {
                return view! {
                    <h4 id="isolation-heading">{t!("prefs-isolation-heading")}</h4>
                    <p>{i.problem.unwrap_or_default()}</p>
                }
                .into_any();
            }
            let state = if i.available { t!("prefs-isolation-available") } else { i.problem.clone().unwrap_or_default() };
            let unavailable = !i.available;
            let isolate = s.preferences.isolate;
            let ask = s.preferences.ask_without_isolation;
            let windows = s.os == "windows"; // not-ui-text: an operating system id
            let strongest = s.preferences.strongest_isolation;
            let strongest_available = i.strongest_available;
            let strongest_problem = i.strongest_problem.clone();
            view! {
                <h4 id="isolation-heading">{t!("prefs-isolation-heading")}</h4>
                <p id="isolation-state">{state}</p>
                <div class="choice">
                    <input type="checkbox" id="prefs-isolate" aria-describedby="prefs-isolate-hint" prop:checked=isolate
                        on:change=move |ev| {
                            let on = event_target_checked(&ev);
                            change(PreferenceChange::Isolate(on), if on { t!("prefs-isolate-on") } else { t!("prefs-isolate-off") });
                        } />
                    <label for="prefs-isolate">{t!("prefs-isolate")}</label>
                </div>
                <p class="hint" id="prefs-isolate-hint">{t!("prefs-isolate-hint")}</p>
                // Windows with the add-on: how to isolate. Windows Sandbox is offered only where it
                // works, and says why not otherwise.
                <Show when=move || windows && isolate>
                    <fieldset>
                        <legend>{t!("prefs-isolation-method")}</legend>
                        <div class="choice">
                            <input type="radio" name="isolation-method" id="isolation-appcontainer" prop:checked=!strongest
                                on:change=move |_| change(PreferenceChange::Strongest(false), t!("prefs-isolation-appcontainer-on")) />
                            <label for="isolation-appcontainer">{t!("prefs-isolation-appcontainer")}</label>
                        </div>
                        <div class="choice">
                            <input type="radio" name="isolation-method" id="isolation-windows-sandbox" prop:checked=strongest
                                disabled=!strongest_available && !strongest
                                aria-describedby=(!strongest_available).then_some("isolation-windows-sandbox-why")
                                on:change=move |_| change(PreferenceChange::Strongest(true), t!("prefs-isolation-windows-sandbox-on")) />
                            <label for="isolation-windows-sandbox">{t!("prefs-isolation-windows-sandbox")}</label>
                        </div>
                        {strongest_problem.clone().filter(|_| !strongest_available).map(|problem| view! {
                            <p class="hint" id="isolation-windows-sandbox-why">{t!("prefs-isolation-windows-sandbox-unavailable", detail = problem)}</p>
                        })}
                    </fieldset>
                </Show>
                <Show when=move || unavailable && isolate>
                    <div class="choice">
                        <input type="checkbox" id="prefs-ask" prop:checked=ask
                            on:change=move |ev| {
                                let on = event_target_checked(&ev);
                                change(PreferenceChange::AskWithoutIsolation(on), t!("prefs-saved"));
                            } />
                        <label for="prefs-ask">{t!("prefs-isolation-ask")}</label>
                    </div>
                </Show>
            }
            .into_any()
        })
    };

    let bridge_status = move || {
        status.get().map(|s| {
            let text = match &s.bridge {
                Some(_) => t!("prefs-bridge-installed"),
                None => t!("prefs-bridge-missing"),
            };
            view! {
                <h4 id="bridge-heading">{t!("prefs-bridge-heading")}</h4>
                <p>{text}</p>
            }
        })
    };

    view! {
        <h2 id="preferences-heading" tabindex="-1">{t!("prefs-heading")}</h2>
        <ErrorBox id="preferences-error" message=Signal::derive(move || error.get()) />
        <section aria-labelledby="analyses-heading">
            <h3 id="analyses-heading">{t!("prefs-analyses-heading")}</h3>
            {output}
            {java}
            {isolation}
            {bridge_status}
            <p class="hint">{move || status.get().map(|s| t!("prefs-version", version = s.version))}</p>
            // Where to report an accessibility problem: the app has no network, so the address is
            // shown as text to copy, not as a link it would open.
            <p>{t!("prefs-accessibility")}</p>
        </section>
        <section aria-label=t!("display-heading")>
            <prefs::DisplayPanel />
        </section>
    }
}

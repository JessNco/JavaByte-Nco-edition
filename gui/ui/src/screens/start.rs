//! Start: choose a sample (picker, folder picker, or drop), see recent analyses.

use crate::app::{ErrorBox, Screen, flow, open_report, report_error, show};
use crate::bridge;
use crate::util::{announcer, fmt_num};
use javabyte_gui_protocol::t;
use javabyte_gui_protocol::views::{EngineStatus, RecentRun, SampleChoice};
use leptos::prelude::*;
use leptos::task::spawn_local;
use std::time::Duration;

const NOTICE_KEY: &str = "javabyte.safety-notice-read";

fn notice_read() -> bool {
    web_sys::window()
        .and_then(|w| w.local_storage().ok().flatten())
        .and_then(|s| s.get_item(NOTICE_KEY).ok().flatten())
        .is_some()
}

#[component]
pub fn StartScreen() -> impl IntoView {
    let flow = flow();
    let engine = RwSignal::new(None::<EngineStatus>);
    let recent = RwSignal::new(Vec::<RecentRun>::new());
    let busy = RwSignal::new(false);
    let error = RwSignal::new(None::<String>);
    let show_notice = RwSignal::new(!notice_read());

    spawn_local(async move {
        engine.set(Some(bridge::engine_status().await));
        recent.set(bridge::recent_runs().await);
    });
    // Refresh the recent list whenever this screen is shown again.
    Effect::new(move |_| {
        if flow.screen.get() == Screen::Start {
            spawn_local(async move { recent.set(bridge::recent_runs().await) });
        }
    });

    let ready = move || engine.with(|e| e.as_ref().is_some_and(|e| e.ready));

    // Reads the chosen sample and moves on to its overview.
    let open_sample = move |sample: SampleChoice| {
        busy.set(true);
        error.set(None);
        announcer().say(t!("start-reading", name = &sample.name));
        spawn_local(async move {
            match bridge::inspect_sample(&sample).await {
                Ok(overview) => {
                    flow.sample.set(Some(sample));
                    flow.overview.set(Some(overview));
                    // Settings start afresh for each sample, from the tool's suggestions.
                    crate::settings::load();
                    show(Screen::Overview);
                }
                Err(message) => report_error(error, "start-error", message),
            }
            busy.set(false);
        });
    };

    let pick = move |folder: bool| {
        spawn_local(async move {
            match bridge::choose_sample(folder).await {
                Ok(Some(sample)) => open_sample(sample),
                Ok(None) => {} // cancelled
                Err(message) => report_error(error, "start-error", message),
            }
        });
    };

    // A dropped file is noted by the backend; look for one while this screen is open.
    if let Ok(handle) = set_interval_with_handle(
        move || {
            if flow.screen.get_untracked() == Screen::Start && !busy.get_untracked() && ready() {
                spawn_local(async move {
                    if let Ok(Some(sample)) = bridge::take_dropped().await {
                        open_sample(sample);
                    }
                });
            }
        },
        Duration::from_millis(700),
    ) {
        on_cleanup(move || handle.clear());
    }

    view! {
        <h2 id="start-heading" tabindex="-1">{t!("start-heading")}</h2>
        <Show when=move || show_notice.get()>
            <div class="notice" role="region" aria-labelledby="notice-heading">
                <h3 id="notice-heading">{t!("notice-heading")}</h3>
                <p>{t!("notice-emulated")}</p>
                <p>{t!("notice-live")}</p>
                <button
                    type="button"
                    on:click=move |_| {
                        if let Some(storage) = web_sys::window().and_then(|w| w.local_storage().ok().flatten()) {
                            let _ = storage.set_item(NOTICE_KEY, "1");
                        }
                        show_notice.set(false);
                        crate::util::focus_id("choose-file".into());
                    }
                >
                    {t!("notice-understood")}
                </button>
            </div>
        </Show>
        {move || {
            engine.get().filter(|e| !e.ready).map(|e| view! {
                <div class="error-summary" role="region" aria-labelledby="engine-problem">
                    <h3 id="engine-problem">{t!("engine-unavailable")}</h3>
                    <p>{e.problem.unwrap_or_default()}</p>
                </div>
            })
        }}
        <ErrorBox id="start-error" message=Signal::derive(move || error.get()) />
        <p>{t!("start-intro")}</p>
        <div class="buttons">
            <button type="button" class="primary" id="choose-file" disabled=move || busy.get() || !ready() on:click=move |_| pick(false)>
                {t!("choose-file")}
            </button>
            <button type="button" disabled=move || busy.get() || !ready() on:click=move |_| pick(true)>
                {t!("choose-folder")}
            </button>
        </div>
        <p class="hint">{t!("drop-hint")}</p>
        <p class="inline-status" role="status">{move || if busy.get() { t!("reading-sample") } else { String::new() }}</p>

        <h3 id="recent-heading">{t!("recent-heading")}</h3>
        {move || {
            let runs = recent.get();
            if runs.is_empty() {
                view! { <p>{t!("recent-none")}</p> }.into_any()
            } else {
                view! {
                    <div class="table-wrap" tabindex="0" role="region" aria-labelledby="recent-heading">
                        <table class="simple-table">
                            <caption class="visually-hidden">{t!("recent-caption")}</caption>
                            <thead>
                                <tr>
                                    <th scope="col">{t!("recent-sample")}</th>
                                    <th scope="col">{t!("recent-when")}</th>
                                    <th scope="col">{t!("recent-outcome")}</th>
                                    <th scope="col" class="number">{t!("recent-events")}</th>
                                    <th scope="col"><span class="visually-hidden">{t!("recent-action")}</span></th>
                                </tr>
                            </thead>
                            <tbody>
                                {runs.into_iter().map(|run| {
                                    let id = run.id.clone();
                                    let label = t!("recent-open-label", name = &run.sample_name, when = &run.when);
                                    view! {
                                        <tr>
                                            <th scope="row" class="untrusted">{run.sample_name.clone()}</th>
                                            <td>{run.when.clone()}</td>
                                            <td>{run.outcome.clone()}</td>
                                            <td class="number">{fmt_num(run.events)}</td>
                                            <td>
                                                <button
                                                    type="button"
                                                    aria-label=label
                                                    on:click=move |_| {
                                                        let id = id.clone();
                                                        spawn_local(async move {
                                                            match bridge::open_recent(&id).await {
                                                                Ok(view) => open_report(view),
                                                                Err(message) => report_error(error, "start-error", message),
                                                            }
                                                        });
                                                    }
                                                >
                                                    {t!("recent-open")}
                                                </button>
                                            </td>
                                        </tr>
                                    }
                                }).collect_view()}
                            </tbody>
                        </table>
                    </div>
                }.into_any()
            }
        }}
    }
}

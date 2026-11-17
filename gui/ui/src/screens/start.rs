//! Start: choose a sample (picker, folder picker, or drop), see recent analyses.

use crate::app::{ErrorBox, Screen, flow, open_report, report_error, show};
use crate::bridge;
use crate::util::{announcer, fmt_num};
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
        announcer().say(format!("Reading {}…", sample.name));
        spawn_local(async move {
            match bridge::inspect_sample(&sample).await {
                Ok(overview) => {
                    flow.sample.set(Some(sample));
                    flow.overview.set(Some(overview));
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
        <h2 id="start-heading" tabindex="-1">"Analyse a sample"</h2>
        <Show when=move || show_notice.get()>
            <div class="notice" role="region" aria-labelledby="notice-heading">
                <h3 id="notice-heading">"Before you start"</h3>
                <p>
                    "JavaByte reads the sample and imitates what it would do. It never runs it on your computer, and "
                    "nothing it tries to send leaves the analysis."
                </p>
                <p>
                    "The sample file itself is still live malware. Keep it in its own folder, do not open it with other "
                    "programs, and for real malware use a virtual machine."
                </p>
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
                    "I understand"
                </button>
            </div>
        </Show>
        {move || {
            engine.get().filter(|e| !e.ready).map(|e| view! {
                <div class="error-summary" role="region" aria-labelledby="engine-problem">
                    <h3 id="engine-problem">"The analysis engine is not available"</h3>
                    <p>{e.problem.unwrap_or_default()}</p>
                </div>
            })
        }}
        <ErrorBox id="start-error" message=Signal::derive(move || error.get()) />
        <p>"Choose a Java program (.jar), a single class file, or a folder of classes."</p>
        <div class="buttons">
            <button type="button" class="primary" id="choose-file" disabled=move || busy.get() || !ready() on:click=move |_| pick(false)>
                "Choose a sample file…"
            </button>
            <button type="button" disabled=move || busy.get() || !ready() on:click=move |_| pick(true)>
                "Choose a folder of classes…"
            </button>
        </div>
        <p class="hint">"You can also drop a file anywhere on this window."</p>
        <p class="inline-status" role="status">{move || if busy.get() { "Reading the sample…" } else { "" }}</p>

        <h3 id="recent-heading">"Recent analyses"</h3>
        {move || {
            let runs = recent.get();
            if runs.is_empty() {
                view! { <p>"None yet. Results are kept in your Documents folder, under JavaByte."</p> }.into_any()
            } else {
                view! {
                    <div class="table-wrap" tabindex="0" role="region" aria-labelledby="recent-heading">
                        <table class="simple-table">
                            <caption class="visually-hidden">"Recent analyses, newest first"</caption>
                            <thead>
                                <tr>
                                    <th scope="col">"Sample"</th>
                                    <th scope="col">"When (UTC)"</th>
                                    <th scope="col">"How it ended"</th>
                                    <th scope="col" class="number">"Events"</th>
                                    <th scope="col"><span class="visually-hidden">"Action"</span></th>
                                </tr>
                            </thead>
                            <tbody>
                                {runs.into_iter().map(|run| {
                                    let id = run.id.clone();
                                    let label = format!("Open report: {}, {}", run.sample_name, run.when);
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
                                                    "Open report"
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

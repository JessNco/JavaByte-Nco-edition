//! A pretend running analysis, to test progress display and screen-reader announcements.
//!
//! The visible numbers update ten times a second. Screen readers are told much less: that the
//! run started, a short summary at the interval chosen under Display, and how it ended.

use crate::bridge;
use crate::prefs::preferences;
use crate::util::{announcer, fmt_num};
use javabyte_gui_protocol::{RunState, RunStatus, display_text};
use leptos::prelude::*;
use leptos::task::spawn_local;
use std::time::Duration;

#[component]
pub fn RunPanel() -> impl IntoView {
    let status = RwSignal::new(RunStatus::default());
    let last_announced_ms = StoredValue::new(0u64);
    let poller = StoredValue::new(None::<IntervalHandle>);
    let running = move || status.with(|s| s.state == RunState::Running);

    let poll = move || {
        spawn_local(async move {
            let Ok(now) = bridge::run_status().await else { return };
            let was_running = status.with_untracked(|s| s.state == RunState::Running);
            if now.state == RunState::Running {
                let every = u64::from(preferences().get_untracked().announce_every) * 1000;
                if every > 0 && now.elapsed_ms >= last_announced_ms.get_value() + every {
                    last_announced_ms.set_value(now.elapsed_ms);
                    announcer().say(progress_sentence(&now));
                }
            } else if was_running && now.state == RunState::Finished {
                if let Some(handle) = poller.get_value() {
                    handle.clear();
                }
                announcer().say(format!(
                    "Analysis finished. Outcome: {}. {} events.",
                    now.outcome.clone().unwrap_or_default(),
                    fmt_num(now.events)
                ));
            }
            status.set(now);
        });
    };

    let start = move |_| {
        spawn_local(async move {
            if let Err(message) = bridge::run_start().await {
                announcer().say(format!("The analysis could not start. {message}"));
                return;
            }
            last_announced_ms.set_value(0);
            announcer().say("Analysis started. Progress will be announced as set under Display.");
            if let Ok(handle) = set_interval_with_handle(poll, Duration::from_millis(100)) {
                poller.set_value(Some(handle));
            }
        });
    };
    let stop = move || {
        spawn_local(async move {
            let _ = bridge::run_stop().await;
        });
    };

    // Control and full stop stops a run from anywhere in the window. Shortcuts always use a
    // modifier, so a single key press cannot trigger them by accident (WCAG 2.1.4).
    let _shortcut = window_event_listener(leptos::ev::keydown, move |ev| {
        if (ev.ctrl_key() || ev.meta_key()) && ev.key() == "." && running() {
            ev.prevent_default();
            stop();
        }
    });

    view! {
        <h2>"Running"</h2>
        <p>"A pretend analysis lasting one minute, producing about 4,000 events a second."</p>
        <div class="buttons">
            <button type="button" class="primary" disabled=running on:click=start>
                "Start pretend analysis"
            </button>
            <button type="button" disabled=move || !running() aria-keyshortcuts="Control+Period" on:click=move |_| stop()>
                "Stop analysis"
            </button>
        </div>
        <p class="hint">
            {move || if running() {
                "An analysis is running. Only one runs at a time. Stop it with the Stop button or Control and full stop; what was found so far is kept."
            } else {
                "No analysis is running."
            }}
        </p>
        <h3>"Progress"</h3>
        <dl class="stats">
            <dt>"State"</dt>
            <dd>{move || status.with(|s| match s.state {
                RunState::Idle => "Not started".to_string(),
                RunState::Running => "Running".to_string(),
                RunState::Finished => format!("Finished: {}", s.outcome.clone().unwrap_or_default()),
            })}</dd>
            <dt>"Time"</dt>
            <dd>{move || format!("{:.1} seconds", status.with(|s| s.elapsed_ms) as f64 / 1000.0)}</dd>
            <dt>"Instructions run"</dt>
            <dd>{move || fmt_num(status.with(|s| s.instructions))}</dd>
            <dt>"Events"</dt>
            <dd>{move || fmt_num(status.with(|s| s.events))}</dd>
        </dl>
        <h3 id="by-category-heading">"Events by category"</h3>
        <table class="simple-table compact" aria-labelledby="by-category-heading">
            <thead>
                <tr>
                    <th scope="col">"Category"</th>
                    <th scope="col" class="number">"Events"</th>
                </tr>
            </thead>
            <tbody>
                {move || {
                    status
                        .get()
                        .by_category
                        .into_iter()
                        .map(|(name, count)| view! {
                            <tr>
                                <th scope="row">{name}</th>
                                <td class="number">{fmt_num(count)}</td>
                            </tr>
                        })
                        .collect_view()
                }}
            </tbody>
        </table>
        <h3>"Latest notable events"</h3>
        <ul class="notable">
            {move || {
                let notable = status.get().notable;
                if notable.is_empty() {
                    view! { <li>"None yet."</li> }.into_any()
                } else {
                    notable
                        .into_iter()
                        .map(|row| view! {
                            <li>
                                <span class="tag">{row.category.clone()}</span>
                                " "
                                {row.action.clone()}
                                ": "
                                <span class="untrusted">{display_text(&row.details, 160).text}</span>
                            </li>
                        })
                        .collect_view()
                        .into_any()
                }
            }}
        </ul>
    }
}

/// The sentence a screen reader hears during a run: totals and the categories analysts care
/// about most, nothing else.
fn progress_sentence(status: &RunStatus) -> String {
    let count = |name: &str| status.by_category.iter().find(|(n, _)| n == name).map_or(0, |(_, c)| *c);
    format!(
        "{} seconds, {} events, including {} network, {} programs started and {} classes defined.",
        status.elapsed_ms / 1000,
        fmt_num(status.events),
        fmt_num(count("net")),
        fmt_num(count("process")),
        fmt_num(count("class")),
    )
}

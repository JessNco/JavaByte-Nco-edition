//! Running: live progress of the analysis, a way to stop it, and the way to its report.
//!
//! The numbers on screen update about four times a second. Screen readers are told much less:
//! a short summary at the interval chosen under Display settings, and how it ended.

use crate::app::{Screen, flow, open_report};
use crate::bridge;
use crate::prefs::preferences;
use crate::util::{announcer, fmt_num};
use javabyte_gui_protocol::{RunState, RunStatus, display_text};
use leptos::prelude::*;
use leptos::task::spawn_local;
use std::time::Duration;

#[component]
pub fn RunningScreen() -> impl IntoView {
    let flow = flow();
    let status = RwSignal::new(RunStatus::default());
    let last_announced_ms = StoredValue::new(0u64);
    let opening = RwSignal::new(false);
    let running = move || status.with(|s| s.state == RunState::Running);

    // Poll while a run may be going. Cheap when idle: one small request.
    if let Ok(handle) = set_interval_with_handle(
        move || {
            if !flow.run_started.get_untracked() {
                return;
            }
            spawn_local(async move {
                let Ok(now) = bridge::run_status().await else { return };
                let before = status.get_untracked().state;
                if now.state == RunState::Running {
                    if before != RunState::Running {
                        last_announced_ms.set_value(0);
                    }
                    let every = u64::from(preferences().get_untracked().announce_every) * 1000;
                    if every > 0 && now.elapsed_ms >= last_announced_ms.get_value() + every {
                        last_announced_ms.set_value(now.elapsed_ms);
                        announcer().say(progress_sentence(&now));
                    }
                } else if before == RunState::Running && now.state == RunState::Finished {
                    announcer().say(format!("Analysis finished. {}", now.ended.clone().unwrap_or_default()));
                }
                status.set(now);
            });
        },
        Duration::from_millis(250),
    ) {
        on_cleanup(move || handle.clear());
    }

    let stop = move || {
        // Said before asking, so that "Analysis finished" (which may come back first) is the
        // last word rather than being replaced by this.
        announcer().say("Stopping. What was found so far is kept.");
        spawn_local(async move {
            if let Err(message) = bridge::stop_run().await {
                announcer().say(format!("The analysis could not be stopped. {message}"));
            }
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

    let view_report = move |_| {
        opening.set(true);
        spawn_local(async move {
            if let Ok(view) = bridge::current_report().await {
                open_report(view);
            }
            opening.set(false);
        });
    };

    view! {
        <h2 id="running-heading" tabindex="-1">
            {move || {
                let name = display_text(&status.with(|s| s.sample_name.clone()), 120).text;
                if running() { format!("Analysing {name}") } else { format!("Analysis of {name}") }
            }}
        </h2>
        <div class="buttons">
            <button type="button" disabled=move || !running() aria-keyshortcuts="Control+Period" on:click=move |_| stop()>
                "Stop analysis"
            </button>
        </div>
        <p class="hint">
            {move || if running() {
                "Only one analysis runs at a time. Stop it with the button or Control and full stop; what was found so far is kept."
            } else {
                ""
            }}
        </p>
        <Show when=move || status.with(|s| s.state == RunState::Finished)>
            <div class="outcome" role="region" aria-labelledby="outcome-heading">
                <h3 id="outcome-heading">"Finished"</h3>
                <p>{move || status.with(|s| s.ended.clone().unwrap_or_default())}</p>
                {move || if status.with(|s| s.report_ready) {
                    view! {
                        <button type="button" class="primary" disabled=move || opening.get() on:click=view_report>"View the report"</button>
                    }.into_any()
                } else {
                    view! { <p>"No report was written. The analysis results folder may still hold a trace file."</p> }.into_any()
                }}
                <button type="button" on:click=move |_| crate::app::show(Screen::Start)>"Analyse another sample"</button>
            </div>
        </Show>
        <h3>"Progress"</h3>
        <dl class="stats">
            <dt>"State"</dt>
            <dd>{move || status.with(|s| match s.state {
                RunState::Idle => "Starting".to_string(),
                RunState::Running => "Running".to_string(),
                RunState::Finished => format!("Finished: {}", s.outcome.clone().unwrap_or_default()),
            })}</dd>
            <dt>"Starting point"</dt>
            <dd class="untrusted">{move || display_text(&status.with(|s| s.entry.clone()), 200).text}</dd>
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
                {move || status.get().by_category.into_iter().filter(|(_, n)| *n > 0).map(|(name, count)| view! {
                    <tr>
                        <th scope="row">{name}</th>
                        <td class="number">{fmt_num(count)}</td>
                    </tr>
                }).collect_view()}
            </tbody>
        </table>
        <h3>"Latest notable events"</h3>
        <ul class="notable">
            {move || {
                let notable = status.get().notable;
                if notable.is_empty() {
                    view! { <li>"None yet."</li> }.into_any()
                } else {
                    notable.into_iter().map(|row| view! {
                        <li>
                            <span class="tag">{row.category.clone()}</span>" "{row.action.clone()}": "
                            <span class="untrusted">{display_text(&row.details, 200).text}</span>
                        </li>
                    }).collect_view().into_any()
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
        "{} seconds, {} events, including {} network, {} programs started and {} classes created.",
        status.elapsed_ms / 1000,
        fmt_num(status.events),
        fmt_num(count("net")),
        fmt_num(count("process")),
        fmt_num(count("class")),
    )
}

//! Running: live progress of the analysis, a way to stop it, and the way to its report.
//!
//! The numbers on screen update about four times a second. Screen readers are told much less:
//! a short summary at the interval chosen under Display settings, and how it ended.

use crate::app::{Screen, flow, open_report};
use crate::bridge;
use crate::prefs::preferences;
use crate::util::{announcer, fmt_num};
use javabyte_gui_protocol::{RunState, RunStatus, display_text, t};
use leptos::prelude::*;
use leptos::task::spawn_local;
use std::time::Duration;

/// The Running screen: progress, notable events, Stop, and the outcome.
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
                    announcer().say(t!("run-finished-announce", ended = now.ended.clone().unwrap_or_default()));
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
        announcer().say(t!("run-stopping"));
        spawn_local(async move {
            if let Err(message) = bridge::stop_run().await {
                announcer().say(t!("run-stop-failed", detail = message));
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
                t!(if running() { "run-heading-running" } else { "run-heading-done" }, name = name)
            }}
        </h2>
        <div class="buttons">
            <button type="button" disabled=move || !running() aria-keyshortcuts="Control+Period" on:click=move |_| stop()>
                {t!("run-stop")}
            </button>
        </div>
        <p class="hint">
            {move || if running() {
                t!("run-one-at-a-time")
            } else {
                String::new()
            }}
        </p>
        <Show when=move || status.with(|s| s.state == RunState::Finished)>
            <div class="outcome" role="region" aria-labelledby="outcome-heading">
                <h3 id="outcome-heading">{t!("run-finished-heading")}</h3>
                <p>{move || status.with(|s| s.ended.clone().unwrap_or_default())}</p>
                {move || if status.with(|s| s.report_ready) {
                    view! {
                        <button type="button" class="primary" disabled=move || opening.get() on:click=view_report>{t!("run-view-report")}</button>
                    }.into_any()
                } else {
                    view! { <p>{t!("run-no-report")}</p> }.into_any()
                }}
                <button type="button" on:click=move |_| crate::app::show(Screen::Start)>{t!("run-another")}</button>
            </div>
        </Show>
        <h3>{t!("run-progress-heading")}</h3>
        <dl class="stats">
            <dt>{t!("run-state")}</dt>
            <dd>{move || status.with(|s| match s.state {
                RunState::Idle => t!("run-state-starting"),
                RunState::Running => t!("run-state-running"),
                RunState::Finished => t!("run-state-finished", outcome = s.outcome.clone().unwrap_or_default()),
            })}</dd>
            <dt>{t!("run-start-point")}</dt>
            <dd class="untrusted">{move || display_text(&status.with(|s| s.entry.clone()), 200).text}</dd>
            <dt>{t!("run-time")}</dt>
            <dd>{move || t!("run-seconds", seconds = format!("{:.1}", status.with(|s| s.elapsed_ms) as f64 / 1000.0))}</dd>
            <dt>{t!("run-instructions")}</dt>
            <dd>{move || fmt_num(status.with(|s| s.instructions))}</dd>
            <dt>{t!("run-events")}</dt>
            <dd>{move || fmt_num(status.with(|s| s.events))}</dd>
        </dl>
        <h3 id="by-category-heading">{t!("run-by-category")}</h3>
        <table class="simple-table compact" aria-labelledby="by-category-heading">
            <thead>
                <tr>
                    <th scope="col">{t!("run-category")}</th>
                    <th scope="col" class="number">{t!("run-events")}</th>
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
        <h3>{t!("run-notable")}</h3>
        <ul class="notable">
            {move || {
                let notable = status.get().notable;
                if notable.is_empty() {
                    view! { <li>{t!("run-notable-none")}</li> }.into_any()
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
    t!(
        "run-progress",
        seconds = status.elapsed_ms / 1000,
        events = status.events,
        network = count("net"),
        programs = count("process"),
        classes = count("class"),
    )
}

//! Report: what the sample did. Tabs for the summary, the indicators and every event.

use crate::app::{EventsPanel, flow};
use crate::screens::report_more::{FilesPanel, LimitsPanel, SettingsUsedPanel};
use crate::util::{Disclosure, copied_message, copy_text, focus_id, fmt_num};
use javabyte_gui_protocol::{display_text, t};
use leptos::prelude::*;

/// Tab stems; each tab's label is the message `report-tab-<stem>`.
const TABS: [&str; 6] = ["summary", "indicators", "events", "limits", "files", "settings"];
/// Characters of one indicator shown before "show all" is needed; the full text is in the file.
const ITEM_CHARS: usize = 300;

/// The Report screen and its tabs. `visible` lets tabs that load data wait until shown.
#[component]
pub fn ReportScreen(visible: Signal<bool>) -> impl IntoView {
    let flow = flow();
    let active = RwSignal::new(0usize);
    let copied = RwSignal::new(String::new());

    // A newly opened report starts on its summary.
    Effect::new(move |_| {
        flow.report.track();
        active.set(0);
    });

    // Tabs with automatic activation, as tested in phase 0.
    let on_tab_key = move |ev: leptos::ev::KeyboardEvent| {
        let current = active.get_untracked();
        let next = match ev.key().as_str() {
            "ArrowRight" => (current + 1) % TABS.len(),
            "ArrowLeft" => (current + TABS.len() - 1) % TABS.len(),
            "Home" => 0,
            "End" => TABS.len() - 1,
            _ => return,
        };
        ev.prevent_default();
        active.set(next);
        focus_id(format!("report-tab-{}", TABS[next]));
    };

    view! {
        <h2 id="report-heading" tabindex="-1">
            {move || t!("report-heading", name = flow.report.with(|r| r.as_ref().map(|r| display_text(&r.sample_name, 120).text).unwrap_or_default()))}
        </h2>
        <div class="buttons">
            <button type="button" on:click=move |_| crate::app::show(crate::app::Screen::Compare)>{t!("report-compare")}</button>
        </div>
        <div role="tablist" aria-label=t!("report-tabs") class="tablist" on:keydown=on_tab_key>
            {TABS.iter().enumerate().map(|(index, stem)| view! {
                <button
                    type="button"
                    role="tab"
                    id=format!("report-tab-{stem}")
                    aria-controls=format!("report-panel-{stem}")
                    aria-selected=move || (active.get() == index).to_string()
                    tabindex=move || if active.get() == index { "0" } else { "-1" }
                    on:click=move |_| active.set(index)
                >
                    {javabyte_gui_protocol::i18n::tr(&format!("report-tab-{stem}"), None)}
                </button>
            }).collect_view()}
        </div>

        <div role="tabpanel" id="report-panel-summary" aria-labelledby="report-tab-summary" hidden=move || active.get() != 0>
            {move || flow.report.get().map(|r| {
                let folder = r.output_dir.clone();
                view! {
                    <h3>{t!("report-in-short")}</h3>
                    <p class="lead">{r.summary.clone()}</p>
                    <h3>{t!("report-how-ended")}</h3>
                    <ul class="runs">
                        {r.runs.iter().map(|run| view! {
                            <li>
                                <strong class="untrusted">{display_text(&run.entry, 200).text}</strong>": "{run.meaning.clone()}
                                {run.detail.clone().map(|detail| view! {
                                    <Disclosure label=t!("report-details")>
                                        <pre class="untrusted detail-text">{display_text(&detail, 4000).text}</pre>
                                    </Disclosure>
                                })}
                            </li>
                        }).collect_view()}
                    </ul>
                    <h3>{t!("report-numbers")}</h3>
                    <dl class="stats">
                        <dt>{t!("report-events-recorded")}</dt><dd>{fmt_num(r.event_count)}</dd>
                        <dt>{t!("report-gaps")}</dt><dd>{fmt_num(r.gaps)}</dd>
                        <dt>{t!("report-dropped")}</dt><dd>{if r.dropped.is_empty() { t!("report-dropped-none") } else {
                            r.dropped.iter().map(|(c, n)| t!("report-dropped-item", count = *n, category = c)).collect::<Vec<_>>().join(&t!("list-separator"))
                        }}</dd>
                        <dt>{t!("report-sha256")}</dt><dd class="untrusted hash">{r.sha256.clone()}</dd>
                    </dl>
                    <h3 id="report-folder-heading">{t!("report-folder-heading")}</h3>
                    <p>{t!("report-folder-intro")}</p>
                    <pre class="command" aria-labelledby="report-folder-heading" tabindex="0"><code>{folder.clone()}</code></pre>
                    <div class="buttons">
                        <button type="button" on:click=move |_| copied.set(copied_message(copy_text(&folder)))>{t!("report-copy-folder")}</button>
                        <span role="status" class="inline-status">{move || copied.get()}</span>
                    </div>
                    <p class="hint">{t!("report-folder-warning")}</p>
                }
            })}
        </div>

        <div role="tabpanel" id="report-panel-indicators" aria-labelledby="report-tab-indicators" hidden=move || active.get() != 1>
            {move || flow.report.get().map(|r| {
                if r.sections.is_empty() {
                    view! { <p>{t!("indicators-none")}</p> }.into_any()
                } else {
                    view! {
                        <p>{t!("indicators-untrusted")}</p>
                        <nav aria-label=t!("indicators-nav")>
                            <ul class="jump-list">
                                {r.sections.iter().map(|s| view! {
                                    <li><a href=format!("#indicator-{}", s.id)>{t!("indicators-title-count", title = &s.title, count = s.items.len())}</a></li>
                                }).collect_view()}
                            </ul>
                        </nav>
                        {r.sections.iter().map(|s| {
                            let all = s.items.join("\n");
                            let status = RwSignal::new(String::new());
                            view! {
                                <section aria-labelledby=format!("indicator-{}", s.id)>
                                    <h3 id=format!("indicator-{}", s.id) tabindex="-1">{t!("indicators-title-count", title = &s.title, count = s.items.len())}</h3>
                                    <p class="hint">{s.explanation.clone()}</p>
                                    <ul class="indicators">
                                        {s.items.iter().map(|item| {
                                            let shown = display_text(item, ITEM_CHARS);
                                            view! {
                                                <li class="untrusted">
                                                    {shown.text}
                                                    {shown.truncated.then(|| view! {
                                                        <span class="shortened">{t!("indicators-shortened", count = shown.original_chars as u64)}</span>
                                                    })}
                                                </li>
                                            }
                                        }).collect_view()}
                                    </ul>
                                    <div class="buttons">
                                        <button type="button" aria-label=t!("indicators-copy-label", title = &s.title) on:click=move |_| status.set(copied_message(copy_text(&all)))>
                                            {t!("indicators-copy")}
                                        </button>
                                        <span role="status" class="inline-status">{move || status.get()}</span>
                                    </div>
                                </section>
                            }
                        }).collect_view()}
                    }.into_any()
                }
            })}
        </div>

        <div role="tabpanel" id="report-panel-events" aria-labelledby="report-tab-events" hidden=move || active.get() != 2>
            <EventsPanel visible=Signal::derive(move || visible.get() && active.get() == 2) reload=Signal::derive(move || flow.report.with(|r| r.as_ref().map(|r| r.output_dir.clone()))) />
        </div>
        <div role="tabpanel" id="report-panel-limits" aria-labelledby="report-tab-limits" hidden=move || active.get() != 3>
            <LimitsPanel />
        </div>
        <div role="tabpanel" id="report-panel-files" aria-labelledby="report-tab-files" hidden=move || active.get() != 4>
            <FilesPanel visible=Signal::derive(move || visible.get() && active.get() == 4) />
        </div>
        <div role="tabpanel" id="report-panel-settings" aria-labelledby="report-tab-settings" hidden=move || active.get() != 5>
            <SettingsUsedPanel />
        </div>
    }
}

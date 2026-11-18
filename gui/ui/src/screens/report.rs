//! Report: what the sample did. Tabs for the summary, the indicators, every event, limits and
//! gaps, saved files, and the settings used (the last three live in `report_more`).

use crate::app::{EventsPanel, flow};
use crate::screens::report_more::{FilesPanel, LimitsPanel, SettingsUsedPanel};
use crate::util::{Disclosure, Tab, copied_message, copy_text, fmt_num, tab_list};
use javabyte_gui_protocol::{display_text, t};
use leptos::prelude::*;

/// The Report screen's tabs, in the order shown. Each tab's label is the message
/// `report-tab-<stem>`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ReportTab {
    Summary,
    Indicators,
    Events,
    Limits,
    Files,
    Settings,
}

impl Tab for ReportTab {
    const ALL: &'static [Self] = &[Self::Summary, Self::Indicators, Self::Events, Self::Limits, Self::Files, Self::Settings];

    fn stem(self) -> &'static str {
        match self {
            Self::Summary => "summary",
            Self::Indicators => "indicators",
            Self::Events => "events",
            Self::Limits => "limits",
            Self::Files => "files",
            Self::Settings => "settings",
        }
    }
}
/// Characters of one indicator shown before "show all" is needed; the full text is in the file.
const ITEM_CHARS: usize = 300;

/// The Report screen and its tabs. `visible` lets tabs that load data wait until shown.
#[component]
pub fn ReportScreen(visible: Signal<bool>) -> impl IntoView {
    let flow = flow();
    let active = RwSignal::new(ReportTab::Summary);
    let copied = RwSignal::new(String::new());

    // A newly opened report starts on its summary.
    Effect::new(move |_| {
        flow.report.track();
        active.set(ReportTab::Summary);
    });

    view! {
        <h2 id="report-heading" tabindex="-1">
            {move || t!("report-heading", name = flow.report.with(|r| r.as_ref().map(|r| display_text(&r.sample_name, 120).text).unwrap_or_default()))}
        </h2>
        <div class="buttons">
            <button type="button" on:click=move |_| crate::app::show(crate::app::Screen::Compare)>{t!("report-compare")}</button>
        </div>
        {tab_list("report", t!("report-tabs"), active, |tab| javabyte_gui_protocol::i18n::tr(&format!("report-tab-{}", tab.stem()), None))}

        <div role="tabpanel" id="report-panel-summary" aria-labelledby="report-tab-summary" hidden=move || active.get() != ReportTab::Summary>
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

        <div role="tabpanel" id="report-panel-indicators" aria-labelledby="report-tab-indicators" hidden=move || active.get() != ReportTab::Indicators>
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

        <div role="tabpanel" id="report-panel-events" aria-labelledby="report-tab-events" hidden=move || active.get() != ReportTab::Events>
            <EventsPanel visible=Signal::derive(move || visible.get() && active.get() == ReportTab::Events) reload=Signal::derive(move || flow.report.with(|r| r.as_ref().map(|r| r.output_dir.clone()))) />
        </div>
        <div role="tabpanel" id="report-panel-limits" aria-labelledby="report-tab-limits" hidden=move || active.get() != ReportTab::Limits>
            <LimitsPanel />
        </div>
        <div role="tabpanel" id="report-panel-files" aria-labelledby="report-tab-files" hidden=move || active.get() != ReportTab::Files>
            <FilesPanel visible=Signal::derive(move || visible.get() && active.get() == ReportTab::Files) />
        </div>
        <div role="tabpanel" id="report-panel-settings" aria-labelledby="report-tab-settings" hidden=move || active.get() != ReportTab::Settings>
            <SettingsUsedPanel />
        </div>
    }
}

//! Report: what the sample did. Tabs for the summary, the indicators and every event.

use crate::app::{EventsPanel, flow};
use crate::util::{copied_message, copy_text, focus_id, fmt_num};
use javabyte_gui_protocol::display_text;
use leptos::prelude::*;

const TABS: [(&str, &str); 3] = [("summary", "Summary"), ("indicators", "Indicators"), ("events", "Events")];
/// Characters of one indicator shown before "show all" is needed; the full text is in the file.
const ITEM_CHARS: usize = 300;

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
        focus_id(format!("report-tab-{}", TABS[next].0));
    };

    view! {
        <h2 id="report-heading" tabindex="-1">
            {move || format!("Report: {}", flow.report.with(|r| r.as_ref().map(|r| display_text(&r.sample_name, 120).text).unwrap_or_default()))}
        </h2>
        <div role="tablist" aria-label="Report sections" class="tablist" on:keydown=on_tab_key>
            {TABS.iter().enumerate().map(|(index, (stem, label))| view! {
                <button
                    type="button"
                    role="tab"
                    id=format!("report-tab-{stem}")
                    aria-controls=format!("report-panel-{stem}")
                    aria-selected=move || (active.get() == index).to_string()
                    tabindex=move || if active.get() == index { "0" } else { "-1" }
                    on:click=move |_| active.set(index)
                >
                    {*label}
                </button>
            }).collect_view()}
        </div>

        <div role="tabpanel" id="report-panel-summary" aria-labelledby="report-tab-summary" hidden=move || active.get() != 0>
            {move || flow.report.get().map(|r| {
                let folder = r.output_dir.clone();
                view! {
                    <h3>"In short"</h3>
                    <p class="lead">{r.summary.clone()}</p>
                    <h3>"How it ended"</h3>
                    <ul class="runs">
                        {r.runs.iter().map(|run| view! {
                            <li>
                                <strong class="untrusted">{display_text(&run.entry, 200).text}</strong>": "{run.meaning.clone()}
                                {run.detail.clone().map(|detail| view! {
                                    <details>
                                        <summary>"Details"</summary>
                                        <pre class="untrusted detail-text">{display_text(&detail, 4000).text}</pre>
                                    </details>
                                })}
                            </li>
                        }).collect_view()}
                    </ul>
                    <h3>"Numbers"</h3>
                    <dl class="stats">
                        <dt>"Events recorded"</dt><dd>{fmt_num(r.event_count)}</dd>
                        <dt>"Parts not emulated faithfully"</dt><dd>{fmt_num(r.gaps)}</dd>
                        <dt>"Events not kept"</dt><dd>{if r.dropped.is_empty() { "None".to_string() } else {
                            r.dropped.iter().map(|(c, n)| format!("{} {c}", fmt_num(*n))).collect::<Vec<_>>().join(", ")
                        }}</dd>
                        <dt>"Sample SHA-256"</dt><dd class="untrusted hash">{r.sha256.clone()}</dd>
                    </dl>
                    <h3 id="report-folder-heading">"Where the results are"</h3>
                    <p>"The full report, the trace and every file the sample wrote or created are in this folder:"</p>
                    <pre class="command" aria-labelledby="report-folder-heading" tabindex="0"><code>{folder.clone()}</code></pre>
                    <div class="buttons">
                        <button type="button" on:click=move |_| copied.set(copied_message(copy_text(&folder)).to_string())>"Copy folder path"</button>
                        <span role="status" class="inline-status">{move || copied.get()}</span>
                    </div>
                    <p class="hint">"Files the sample wrote may be malware. Do not open them with other programs."</p>
                }
            })}
        </div>

        <div role="tabpanel" id="report-panel-indicators" aria-labelledby="report-tab-indicators" hidden=move || active.get() != 1>
            {move || flow.report.get().map(|r| {
                if r.sections.is_empty() {
                    view! { <p>"No indicators were recorded."</p> }.into_any()
                } else {
                    view! {
                        <p>"Text in these lists comes from the sample. Invisible and control characters are shown as codes such as \\x07."</p>
                        <nav aria-label="Indicator lists">
                            <ul class="jump-list">
                                {r.sections.iter().map(|s| view! {
                                    <li><a href=format!("#indicator-{}", s.id)>{format!("{} ({})", s.title, s.items.len())}</a></li>
                                }).collect_view()}
                            </ul>
                        </nav>
                        {r.sections.iter().map(|s| {
                            let all = s.items.join("\n");
                            let status = RwSignal::new("");
                            view! {
                                <section aria-labelledby=format!("indicator-{}", s.id)>
                                    <h3 id=format!("indicator-{}", s.id) tabindex="-1">{format!("{} ({})", s.title, s.items.len())}</h3>
                                    <p class="hint">{s.explanation.clone()}</p>
                                    <ul class="indicators">
                                        {s.items.iter().map(|item| {
                                            let shown = display_text(item, ITEM_CHARS);
                                            view! {
                                                <li class="untrusted">
                                                    {shown.text}
                                                    {shown.truncated.then(|| view! {
                                                        <span class="shortened">{format!(" … ({} characters in full, in the report file)", fmt_num(shown.original_chars as u64))}</span>
                                                    })}
                                                </li>
                                            }
                                        }).collect_view()}
                                    </ul>
                                    <div class="buttons">
                                        <button type="button" aria-label=format!("Copy the list: {}", s.title) on:click=move |_| status.set(copied_message(copy_text(&all)))>
                                            "Copy the list"
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
    }
}

//! Compare: two analyses side by side. How each ended, events per category, what each
//! indicator gained or lost, and which settings differ.

use crate::app::{ErrorBox, Screen, flow, report_error};
use crate::bridge;
use crate::util::{focus_id, fmt_num};
use javabyte_gui_protocol::compare::Comparison;
use javabyte_gui_protocol::views::RecentRun;
use javabyte_gui_protocol::{display_text, t};
use leptos::prelude::*;
use leptos::task::spawn_local;

/// The suggested pair: the open report (or the newest run) second, and before it the previous
/// run of the same sample, or else the next newest run.
fn suggested(runs: &[RecentRun], open_folder: Option<&str>) -> (String, String) {
    let second = open_folder.and_then(|f| runs.iter().find(|r| r.output_dir == f)).or(runs.first());
    let Some(second) = second else { return (String::new(), String::new()) };
    let first = runs
        .iter()
        .find(|r| r.id != second.id && r.sample_name == second.sample_name)
        .or_else(|| runs.iter().find(|r| r.id != second.id));
    (first.map(|r| r.id.clone()).unwrap_or_default(), second.id.clone())
}

fn signed(n: i64) -> String {
    if n > 0 { format!("+{}", fmt_num(n as u64)) } else if n < 0 { format!("−{}", fmt_num(n.unsigned_abs())) } else { "0".into() }
}

#[component]
pub fn CompareScreen() -> impl IntoView {
    let flow = flow();
    let runs = RwSignal::new(Vec::<RecentRun>::new());
    let first = RwSignal::new(String::new());
    let second = RwSignal::new(String::new());
    let result = RwSignal::new(None::<Comparison>);
    let error = RwSignal::new(None::<String>);

    // Fresh choices each time the screen opens.
    Effect::new(move |_| {
        if flow.screen.get() != Screen::Compare {
            return;
        }
        spawn_local(async move {
            let list = bridge::recent_runs().await;
            let open = flow.report.with_untracked(|r| r.as_ref().map(|r| r.output_dir.clone()));
            let (a, b) = suggested(&list, open.as_deref());
            first.set(a);
            second.set(b);
            runs.set(list);
            result.set(None);
        });
    });

    let compare = move |_| {
        let (a, b) = (first.get_untracked(), second.get_untracked());
        spawn_local(async move {
            match bridge::compare_runs(&a, &b).await {
                Ok(comparison) => {
                    error.set(None);
                    result.set(Some(comparison));
                    focus_id("compare-result-heading".into());
                }
                Err(message) => report_error(error, "compare-error", message),
            }
        });
    };

    let picker = move |id: &'static str, label: String, value: RwSignal<String>| {
        view! {
            <div class="field">
                <label for=id>{label}</label>
                <select id=id on:change=move |ev| value.set(event_target_value(&ev))>
                    {move || runs.get().into_iter().map(|run| {
                        let selected_id = run.id.clone();
                        let text = t!("compare-run-option", name = display_text(&run.sample_name, 80).text, when = run.when.clone(), outcome = run.outcome.clone());
                        view! { <option value=run.id.clone() selected=move || value.get() == selected_id>{text}</option> }
                    }).collect_view()}
                </select>
            </div>
        }
    };

    view! {
        <h2 id="compare-heading" tabindex="-1">{t!("compare-heading")}</h2>
        <p>{t!("compare-intro")}</p>
        <ErrorBox id="compare-error" message=Signal::derive(move || error.get()) />
        {move || if runs.with(|r| r.len() < 2) {
            view! { <p>{t!("compare-need-two")}</p> }.into_any()
        } else {
            view! {
                <form class="compare-choice" on:submit=move |ev| { ev.prevent_default(); compare(()); }>
                    {picker("compare-first", t!("compare-first"), first)}
                    {picker("compare-second", t!("compare-second"), second)}
                    <p class="hint" hidden=move || first.get() != second.get()>{t!("compare-same-run")}</p>
                    <button type="submit" class="primary">{t!("compare-button")}</button>
                </form>
            }
            .into_any()
        }}
        {move || result.get().map(result_view)}
    }
}

fn result_view(c: Comparison) -> impl IntoView {
    let changed: Vec<_> = c.indicators.iter().filter(|i| !i.added.is_empty() || !i.removed.is_empty()).cloned().collect();
    let unchanged: Vec<String> = c.indicators.iter().filter(|i| i.added.is_empty() && i.removed.is_empty()).map(|i| i.title.clone()).collect();
    let identical = c.identical();
    let endings: Vec<_> = (0..c.a.endings.len().max(c.b.endings.len()))
        .map(|i| (c.a.endings.get(i).cloned(), c.b.endings.get(i).cloned()))
        .collect();
    view! {
        <section class="compare-result" aria-labelledby="compare-result-heading">
            <h3 id="compare-result-heading" tabindex="-1">{t!("compare-result")}</h3>
            <p>
                {t!("compare-which", first = display_text(&c.a.sample_name, 80).text, second = display_text(&c.b.sample_name, 80).text)}" "
                {if c.same_sample { t!("compare-same-sample") } else { t!("compare-other-sample") }}
            </p>
            {identical.then(|| view! { <p><strong>{t!("compare-identical")}</strong></p> })}

            <h4 id="compare-endings">{t!("compare-endings")}</h4>
            <div class="table-wrap" tabindex="0" role="region" aria-labelledby="compare-endings">
            <table class="simple-table compact">
                <thead><tr>
                    <th scope="col">{t!("compare-part")}</th>
                    <th scope="col">{t!("compare-first")}</th>
                    <th scope="col">{t!("compare-second")}</th>
                </tr></thead>
                <tbody>
                    {endings.into_iter().map(|(a, b)| {
                        let part = a.as_ref().or(b.as_ref()).map(|e| e.0.clone()).unwrap_or_default();
                        view! {
                            <tr>
                                <th scope="row" class="untrusted">{display_text(&part, 120).text}</th>
                                <td>{a.map(|e| e.1).unwrap_or_else(|| t!("compare-not-run"))}</td>
                                <td>{b.map(|e| e.1).unwrap_or_else(|| t!("compare-not-run"))}</td>
                            </tr>
                        }
                    }).collect_view()}
                </tbody>
            </table>
            </div>

            <h4 id="compare-categories">{t!("compare-categories")}</h4>
            <div class="table-wrap" tabindex="0" role="region" aria-labelledby="compare-categories">
                <table class="simple-table compact">
                    <thead><tr>
                        <th scope="col">{t!("run-category")}</th>
                        <th scope="col" class="number">{t!("compare-first")}</th>
                        <th scope="col" class="number">{t!("compare-second")}</th>
                        <th scope="col" class="number">{t!("compare-difference")}</th>
                    </tr></thead>
                    <tbody>
                        {c.categories.iter().map(|row| view! {
                            <tr>
                                <th scope="row">{row.category.clone()}</th>
                                <td class="number">{fmt_num(row.a)}</td>
                                <td class="number">{fmt_num(row.b)}</td>
                                <td class="number">{signed(row.b as i64 - row.a as i64)}</td>
                            </tr>
                        }).collect_view()}
                    </tbody>
                </table>
            </div>

            <h4>{t!("compare-indicators")}</h4>
            {if changed.is_empty() {
                view! { <p>{t!("compare-indicators-same")}</p> }.into_any()
            } else {
                changed.into_iter().map(|i| view! {
                    <section class="indicator-diff" aria-label=i.title.clone()>
                        <h5>{i.title.clone()}</h5>
                        {(!i.added.is_empty()).then(|| view! {
                            <p>{t!("compare-added", count = i.added.len())}</p>
                            <ul class="untrusted">{i.added.iter().map(|v| view! { <li>{display_text(v, 300).text}</li> }).collect_view()}</ul>
                        })}
                        {(!i.removed.is_empty()).then(|| view! {
                            <p>{t!("compare-removed", count = i.removed.len())}</p>
                            <ul class="untrusted">{i.removed.iter().map(|v| view! { <li>{display_text(v, 300).text}</li> }).collect_view()}</ul>
                        })}
                        <p class="hint">{t!("compare-unchanged", count = i.same)}</p>
                    </section>
                }).collect_view().into_any()
            }}
            {(!unchanged.is_empty()).then(|| view! { <p class="hint">{t!("compare-no-change", lists = unchanged.join(&t!("list-separator")))}</p> })}

            <h4 id="compare-settings">{t!("compare-settings")}</h4>
            {if c.settings.is_empty() {
                view! { <p>{t!("compare-settings-same")}</p> }.into_any()
            } else {
                view! {
                    <div class="table-wrap" tabindex="0" role="region" aria-labelledby="compare-settings">
                        <table class="simple-table compact">
                            <thead><tr>
                                <th scope="col">{t!("compare-setting")}</th>
                                <th scope="col">{t!("compare-first")}</th>
                                <th scope="col">{t!("compare-second")}</th>
                            </tr></thead>
                            <tbody>
                                {c.settings.iter().map(|s| view! {
                                    <tr>
                                        <th scope="row">{s.path.clone()}</th>
                                        <td class="untrusted">{display_text(&s.a.clone().unwrap_or_else(|| t!("compare-not-set")), 300).text}</td>
                                        <td class="untrusted">{display_text(&s.b.clone().unwrap_or_else(|| t!("compare-not-set")), 300).text}</td>
                                    </tr>
                                }).collect_view()}
                            </tbody>
                        </table>
                    </div>
                }
                .into_any()
            }}
        </section>
    }
}

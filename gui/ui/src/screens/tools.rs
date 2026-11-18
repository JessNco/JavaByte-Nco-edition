//! Tools: read-only views of the built-in host policy (`javabyte policy`) and of every option
//! with its default (`javabyte defaults`), each with a search (docs/GUI-PLAN.md, screen 6).
//!
//! Both load the first time the screen opens. The tables show every match; the policy has a
//! few hundred rules and the options about a hundred, which a plain table handles well and which
//! screen readers can navigate by row and column.

use crate::app::{ErrorBox, Screen, flow, report_error};
use crate::bridge;
use crate::util::focus_id;
use javabyte_gui_protocol::i18n::tr;
use javabyte_gui_protocol::t;
use javabyte_gui_protocol::tools::{DefaultRow, PolicyListing, default_rows};
use leptos::prelude::*;
use leptos::task::spawn_local;

const TABS: [&str; 2] = ["policy", "defaults"];

/// The Tools screen.
#[component]
pub fn ToolsScreen() -> impl IntoView {
    let flow = flow();
    let tab = RwSignal::new(0usize);
    let policy = RwSignal::new(None::<PolicyListing>);
    let defaults = RwSignal::new(None::<Vec<DefaultRow>>);
    let error = RwSignal::new(None::<String>);

    // Loaded once, the first time the screen opens: both come from the tool and never change.
    Effect::new(move |_| {
        if flow.screen.get() == Screen::Tools && policy.with_untracked(Option::is_none) {
            spawn_local(async move {
                match bridge::tool_policy().await {
                    Ok(listing) => policy.set(Some(listing)),
                    Err(message) => report_error(error, "tools-error", message),
                }
                match bridge::config_schema().await {
                    Ok(schema) => defaults.set(Some(default_rows(&schema))),
                    Err(message) => report_error(error, "tools-error", message),
                }
            });
        }
    });

    let on_tab_key = move |ev: web_sys::KeyboardEvent| {
        let current = tab.get_untracked();
        let next = match ev.key().as_str() {
            "ArrowRight" => (current + 1) % TABS.len(),
            "ArrowLeft" => (current + TABS.len() - 1) % TABS.len(),
            "Home" => 0,
            "End" => TABS.len() - 1,
            _ => return,
        };
        ev.prevent_default();
        tab.set(next);
        focus_id(format!("tools-tab-{}", TABS[next]));
    };

    view! {
        <h2 id="tools-heading" tabindex="-1">{t!("tools-heading")}</h2>
        <p>{t!("tools-intro")}</p>
        <ErrorBox id="tools-error" message=Signal::derive(move || error.get()) />
        <div role="tablist" aria-label=t!("tools-tabs") class="tablist" on:keydown=on_tab_key>
            {TABS.iter().enumerate().map(|(index, stem)| view! {
                <button
                    type="button"
                    role="tab"
                    id=format!("tools-tab-{stem}")
                    aria-controls=format!("tools-panel-{stem}")
                    aria-selected=move || (tab.get() == index).to_string()
                    tabindex=move || if tab.get() == index { "0" } else { "-1" }
                    on:click=move |_| tab.set(index)
                >
                    {tr(&format!("tools-tab-{stem}"), None)}
                </button>
            }).collect_view()}
        </div>
        <div role="tabpanel" id="tools-panel-policy" aria-labelledby="tools-tab-policy" hidden=move || tab.get() != 0>
            <PolicyPanel policy />
        </div>
        <div role="tabpanel" id="tools-panel-defaults" aria-labelledby="tools-tab-defaults" hidden=move || tab.get() != 1>
            <DefaultsPanel defaults />
        </div>
    }
}

/// The search field both panels share: a label, a hint, and a live count of what matches.
fn search_field(id: &'static str, label: String, query: RwSignal<String>, count: Signal<Option<(usize, usize)>>) -> impl IntoView {
    view! {
        <div class="field">
            <label for=id>{label}</label>
            <p class="hint" id=format!("{id}-hint")>{t!("tools-search-hint")}</p>
            <input type="search" id=id aria-describedby=format!("{id}-hint {id}-count")
                prop:value=move || query.get()
                on:input=move |ev| query.set(event_target_value(&ev)) />
        </div>
        // Polite, so typing is not interrupted; the count follows each change.
        <p id=format!("{id}-count") role="status">{move || count.get().map(|(shown, total)| {
            if shown == total { t!("tools-count-all", count = total) } else { t!("tools-count-matching", shown = shown, total = total) }
        })}</p>
    }
}

/// The host policy: every rule, whether it allows or blocks, its group and why.
#[component]
fn PolicyPanel(policy: RwSignal<Option<PolicyListing>>) -> impl IntoView {
    let query = RwSignal::new(String::new());
    let matching = Memo::new(move |_| {
        let q = query.get();
        policy.with(|p| p.as_ref().map(|p| p.rules.iter().filter(|r| r.matches(&q)).cloned().collect::<Vec<_>>()))
    });
    let count = Signal::derive(move || {
        let total = policy.with(|p| p.as_ref().map(|p| p.rules.len()))?;
        Some((matching.with(|m| m.as_ref().map_or(0, Vec::len)), total))
    });
    view! {
        <p>{t!("tools-policy-intro")}</p>
        {search_field("tools-policy-search", t!("tools-policy-search"), query, count)}
        {move || match matching.get() {
            None => view! { <p>{t!("tools-loading")}</p> }.into_any(),
            Some(rules) if rules.is_empty() => view! { <p>{t!("tools-no-match")}</p> }.into_any(),
            Some(rules) => view! {
                // Focusable, so the table can be scrolled sideways by keyboard at large text sizes.
                <div class="table-wrap" tabindex="0" role="region" aria-labelledby="tools-policy-caption">
                    <table class="simple-table">
                        <caption id="tools-policy-caption">{t!("tools-policy-caption")}</caption>
                        <thead><tr>
                            <th scope="col">{t!("tools-policy-rule")}</th>
                            <th scope="col">{t!("tools-policy-effect")}</th>
                            <th scope="col">{t!("tools-policy-section")}</th>
                            <th scope="col">{t!("tools-policy-why")}</th>
                        </tr></thead>
                        <tbody>
                            {rules.into_iter().map(|r| view! {
                                <tr>
                                    <th scope="row" class="untrusted">{r.rule}</th>
                                    // Words, not colour alone: "Allowed" or "Blocked".
                                    <td class=if r.allow { "" } else { "effect-block" }>
                                        {if r.allow { t!("tools-policy-allowed") } else { t!("tools-policy-blocked") }}
                                    </td>
                                    <td>{r.section}</td>
                                    <td>{r.note}</td>
                                </tr>
                            }).collect_view()}
                        </tbody>
                    </table>
                </div>
            }.into_any(),
        }}
    }
}

/// Every option with its default, type and meaning: the reference for writing settings.
#[component]
fn DefaultsPanel(defaults: RwSignal<Option<Vec<DefaultRow>>>) -> impl IntoView {
    let query = RwSignal::new(String::new());
    let matching = Memo::new(move |_| {
        let q = query.get();
        defaults.with(|d| d.as_ref().map(|rows| rows.iter().filter(|r| r.matches(&q)).cloned().collect::<Vec<_>>()))
    });
    let count = Signal::derive(move || {
        let total = defaults.with(|d| d.as_ref().map(Vec::len))?;
        Some((matching.with(|m| m.as_ref().map_or(0, Vec::len)), total))
    });
    view! {
        <p>{t!("tools-defaults-intro")}</p>
        {search_field("tools-defaults-search", t!("tools-defaults-search"), query, count)}
        {move || match matching.get() {
            None => view! { <p>{t!("tools-loading")}</p> }.into_any(),
            Some(rows) if rows.is_empty() => view! { <p>{t!("tools-no-match")}</p> }.into_any(),
            Some(rows) => view! {
                <div class="table-wrap" tabindex="0" role="region" aria-labelledby="tools-defaults-caption">
                    <table class="simple-table">
                        <caption id="tools-defaults-caption">{t!("tools-defaults-caption")}</caption>
                        <thead><tr>
                            <th scope="col">{t!("tools-defaults-option")}</th>
                            <th scope="col">{t!("tools-defaults-default")}</th>
                            <th scope="col">{t!("tools-defaults-type")}</th>
                            <th scope="col">{t!("tools-defaults-meaning")}</th>
                        </tr></thead>
                        <tbody>
                            {rows.into_iter().map(|r| {
                                let default = if r.default.is_empty() { t!("tools-defaults-none") } else { r.default };
                                view! {
                                    <tr>
                                        <th scope="row" class="untrusted">{r.path}</th>
                                        <td class="untrusted">{default}</td>
                                        <td>{r.kind}</td>
                                        <td>{r.meaning}</td>
                                    </tr>
                                }
                            }).collect_view()}
                        </tbody>
                    </table>
                </div>
            }.into_any(),
        }}
    }
}

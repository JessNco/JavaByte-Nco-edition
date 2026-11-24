//! The event list: a virtualised ARIA grid, and a simple paged table as an alternative.
//!
//! A report can hold hundreds of thousands of events. Only the rows near the visible area exist
//! in the page; `aria-rowcount` and `aria-rowindex` tell assistive technology how many rows
//! there are and where each rendered row sits. The grid is one tab stop, moved through with the
//! arrow keys, following the ARIA Authoring Practices data grid pattern.
//!
//! Virtualised grids are the riskiest part of the design for screen-reader users, which is why
//! the paged table exists: it is an ordinary HTML table with nothing clever about it.

use crate::bridge;
use crate::util::{announcer, fmt_num, non_empty};
use javabyte_gui_protocol::i18n::tr;
use javabyte_gui_protocol::{EventFilter, EventRow, display_text, t};
use leptos::prelude::*;
use leptos::task::spawn_local;
use wasm_bindgen::JsCast;

mod details;
mod grid;
mod table;

use details::EventDetails;
use grid::EventGrid;
use table::PagedTable;

/// Rows rendered above and below the visible area, so that arrow keys and small scrolls never
/// meet an empty row.
const OVERSCAN: u64 = 20;
/// Column headings: messages `col-<stem>`.
const COLUMNS: [&str; 5] = ["number", "category", "action", "details", "location"];
const CELL_CHARS: usize = 160;
const DETAIL_CHARS: usize = 4_000;
const TABLE_PAGE: u64 = 50;
/// Categories offered in the filter; each one's label is the message `category-<name>`.
const CATEGORIES: [&str; 12] = ["process", "net", "file", "class", "native", "crypto", "string", "reflect", "thread", "console", "policy", "gap"];

/// A column's heading, from its message.
fn column_name(stem: &str) -> String {
    tr(&format!("col-{stem}"), None)
}

/// The mark after shortened text: an ellipsis people see, and words screen readers say.
fn shortened_mark() -> impl IntoView {
    view! { <span class="shortened">{t!("events-shortened-mark")}<span class="visually-hidden">{t!("events-shortened")}</span></span> }
}

#[derive(Clone, Copy, PartialEq)]
enum Layout {
    Grid,
    Table,
}

/// Matching and total event counts, once known.
type Counts = Option<(u64, u64)>;

/// The event list with its filters, layout choice, and details of the current event. `reload`
/// changes when another report is opened, which starts the list again.
#[component]
pub fn EventsPanel(visible: Signal<bool>, reload: Signal<Option<String>>) -> impl IntoView {
    let layout = RwSignal::new(Layout::Grid);
    let category = RwSignal::new(String::new());
    let action = RwSignal::new(String::new());
    let actions = RwSignal::new(Vec::<String>::new());
    let text = RwSignal::new(String::new());
    let filter = RwSignal::new(EventFilter::default());
    let counts = RwSignal::new(None::<(u64, u64)>);
    let selected = RwSignal::new(None::<EventRow>);
    // A position in the filtered list to move to, and the event number there.
    let jump = RwSignal::new(None::<(u64, u64)>);
    let jump_text = RwSignal::new(String::new());

    let apply = move || {
        filter.set(EventFilter {
            category: non_empty(category.get_untracked()),
            action: non_empty(action.get_untracked()),
            text: non_empty(text.get_untracked()),
        });
    };
    // The actions offered follow the category.
    Effect::new(move |_| {
        let chosen = category.get();
        action.set(String::new());
        if chosen.is_empty() {
            actions.set(Vec::new());
            return;
        }
        spawn_local(async move {
            actions.set(bridge::event_actions(&chosen).await);
        });
    });
    let go_to_event = move || {
        let Ok(seq) = jump_text.get_untracked().trim().replace(',', "").parse::<u64>() else {
            announcer().say(t!("events-jump-invalid"));
            return;
        };
        let current = filter.get_untracked();
        spawn_local(async move {
            match bridge::event_position(seq, &current).await {
                Some(position) => {
                    jump.set(Some((position, seq)));
                    announcer().say(t!("events-jumped", seq = seq));
                }
                None => announcer().say(t!("events-jump-hidden", seq = seq)),
            }
        });
    };
    // Another report: the counts and rows belong to the old one, so start again.
    Effect::new(move |previous: Option<()>| {
        reload.track();
        if previous.is_some() {
            counts.set(None);
            filter.set(EventFilter::default());
            selected.set(None);
        }
    });

    view! {
        <h3 id="events-heading">{t!("events-heading")}</h3>
        <p>{t!("events-intro")}</p>
        <form
            class="filters"
            role="search"
            aria-label=t!("events-filter")
            on:submit=move |ev| {
                ev.prevent_default();
                apply();
            }
        >
            <div class="field">
                <label for="filter-category">{t!("events-category")}</label>
                <select
                    id="filter-category"
                    on:change=move |ev| {
                        category.set(event_target_value(&ev));
                        apply();
                    }
                >
                    <option value="">{t!("events-all-categories")}</option>
                    {CATEGORIES
                        .iter()
                        .map(|value| view! { <option value=*value>{t!("events-category-option", label = tr(&format!("category-{value}"), None), value = *value)}</option> })
                        .collect_view()}
                </select>
            </div>
            <div class="field">
                <label for="filter-action">{t!("events-action")}</label>
                <select
                    id="filter-action"
                    aria-describedby="filter-action-hint"
                    disabled=move || category.with(String::is_empty)
                    on:change=move |ev| {
                        action.set(event_target_value(&ev));
                        apply();
                    }
                >
                    <option value="" selected=move || action.with(String::is_empty)>{t!("events-all-actions")}</option>
                    {move || actions.get().into_iter().map(|a| {
                        let shown = display_text(&a, 60).text;
                        view! { <option value=a>{shown}</option> }
                    }).collect_view()}
                </select>
                <p class="hint" id="filter-action-hint" hidden=move || !category.with(String::is_empty)>{t!("events-action-hint")}</p>
            </div>
            <div class="field">
                <label for="filter-text">{t!("events-text")}</label>
                <input
                    id="filter-text"
                    type="search"
                    autocomplete="off"
                    spellcheck="false"
                    prop:value=move || text.get()
                    on:input=move |ev| text.set(event_target_value(&ev))
                />
            </div>
            <div class="buttons">
                <button type="submit">{t!("events-apply")}</button>
                <button
                    type="button"
                    on:click=move |_| {
                        category.set(String::new());
                        action.set(String::new());
                        text.set(String::new());
                        if let Some(select) = document().get_element_by_id("filter-category") {
                            select.unchecked_into::<web_sys::HtmlInputElement>().set_value("");
                        }
                        apply();
                    }
                >
                    {t!("events-clear")}
                </button>
            </div>
        </form>
        <form class="input-row jump" on:submit=move |ev| {
            ev.prevent_default();
            go_to_event();
        }>
            <label for="jump-to-event">{t!("events-jump")}</label>
            <input id="jump-to-event" type="text" inputmode="numeric" class="input-short" autocomplete="off"
                prop:value=move || jump_text.get() on:input=move |ev| jump_text.set(event_target_value(&ev)) />
            <button type="submit">{t!("events-jump-go")}</button>
        </form>
        // A status region: announced when the count changes after filtering, not on scrolling.
        <p id="events-count" role="status">
            {move || match counts.get() {
                None => t!("events-loading"),
                Some((matching, all)) if matching == all => t!("events-showing-all", count = all),
                Some((0, all)) => t!("events-none-match", count = all),
                Some((matching, all)) => t!("events-some-match", matching = matching, count = all),
            }}
        </p>
        <fieldset class="inline">
            <legend>{t!("events-layout")}</legend>
            <div class="choice">
                <input
                    type="radio"
                    name="layout"
                    id="layout-grid"
                    prop:checked=move || layout.get() == Layout::Grid
                    on:change=move |_| layout.set(Layout::Grid)
                />
                <label for="layout-grid">{t!("events-layout-grid")}</label>
            </div>
            <div class="choice">
                <input
                    type="radio"
                    name="layout"
                    id="layout-table"
                    prop:checked=move || layout.get() == Layout::Table
                    on:change=move |_| layout.set(Layout::Table)
                />
                <label for="layout-table">{t!("events-layout-table")}</label>
            </div>
        </fieldset>
        <Show
            when=move || layout.get() == Layout::Grid
            fallback=move || view! { <PagedTable filter counts selected jump /> }
        >
            <EventGrid filter counts selected visible jump />
        </Show>
        <EventDetails selected />
    }
}

/// One cell's text: shortened and with invisible characters made visible.
fn cell_text(column: usize, row: &EventRow) -> (String, bool) {
    let raw = match column {
        0 => return (fmt_num(row.seq), false),
        1 => row.category.as_str(),
        2 => row.action.as_str(),
        3 => row.details.as_str(),
        _ => row.caller.as_deref().unwrap_or("—"),
    };
    let shown = display_text(raw, CELL_CHARS);
    (shown.text, shown.truncated)
}

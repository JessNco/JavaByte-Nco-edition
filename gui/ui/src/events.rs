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
use crate::util::{announcer, fmt_num, focus_id, non_empty};
use javabyte_gui_protocol::i18n::tr;
use javabyte_gui_protocol::{EventFilter, EventRow, PageRequest, display_text, t};
use leptos::html;
use leptos::prelude::*;
use leptos::task::spawn_local;
use wasm_bindgen::JsCast;

/// Rows rendered above and below the visible area, so that arrow keys and small scrolls never
/// meet an empty row.
const OVERSCAN: u64 = 20;
/// Column headings: messages `col-<stem>`.
const COLUMNS: [&str; 5] = ["number", "category", "action", "details", "location"];
const CELL_CHARS: usize = 160;
const DETAIL_CHARS: usize = 4_000;
const TABLE_PAGE: u64 = 50;
/// Categories offered in the filter; each one's label is the message `category-<name>`.
const CATEGORIES: [&str; 11] = ["process", "net", "file", "class", "crypto", "string", "reflect", "thread", "console", "policy", "gap"];

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
                            let _ = select.unchecked_into::<web_sys::HtmlInputElement>().set_value("");
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

#[component]
fn EventGrid(
    filter: RwSignal<EventFilter>,
    counts: RwSignal<Counts>,
    selected: RwSignal<Option<EventRow>>,
    visible: Signal<bool>,
    jump: RwSignal<Option<(u64, u64)>>,
) -> impl IntoView {
    let scroller = NodeRef::<html::Div>::new();
    let rows = RwSignal::new(Vec::<EventRow>::new());
    let rows_offset = RwSignal::new(0u64);
    let scroll_top = RwSignal::new(0.0f64);
    let viewport = RwSignal::new(480.0f64);
    let row_height = RwSignal::new(40.0f64);
    // Position (within the filtered list) and column of the cell that holds the tab stop.
    let active = RwSignal::new((0u64, 0usize));
    // Whether keyboard focus is inside the grid, so that re-rendering rows can put it back.
    let has_focus = RwSignal::new(false);
    let latest_request = StoredValue::new(0u64);
    let matching = move || counts.get().map(|c| c.0).unwrap_or(0);

    // A new filter starts again at the top.
    Effect::new(move |previous: Option<()>| {
        filter.track();
        if previous.is_some() {
            active.set((0, 0));
            rows.set(Vec::new());
            rows_offset.set(0);
            scroll_top.set(0.0);
            if let Some(element) = scroller.get_untracked() {
                element.set_scroll_top(0);
            }
        }
    });

    // Fetch the rows around the visible area whenever it moves past what is loaded.
    Effect::new(move |_| {
        if !visible.get() {
            return;
        }
        let current_filter = filter.get();
        let height = row_height.get();
        let first = (scroll_top.get() / height).floor() as u64;
        let shown = (viewport.get() / height).ceil() as u64 + 1;
        let start = first.saturating_sub(OVERSCAN);
        let end = first + shown + OVERSCAN;
        let have_start = rows_offset.get_untracked();
        let have_len = rows.with_untracked(Vec::len) as u64;
        let known_end = counts.get_untracked().map_or(end, |(m, _)| end.min(m));
        if have_len > 0 && start >= have_start && known_end <= have_start + have_len {
            return;
        }
        let id = latest_request.get_value() + 1;
        latest_request.set_value(id);
        let request = PageRequest { offset: start, limit: (end - start) as u32, filter: current_filter };
        spawn_local(async move {
            match bridge::events_page(request).await {
                // A slower, older reply must not overwrite a newer one.
                Ok(page) if latest_request.get_value() == id => {
                    counts.set(Some((page.total, page.unfiltered_total)));
                    rows_offset.set(page.offset);
                    rows.set(page.rows);
                }
                Ok(_) => {}
                Err(message) => announcer().say(t!("events-load-failed", detail = message)),
            }
        });
    });

    // Measure real sizes after each render: rows grow with the text size setting.
    Effect::new(move |_| {
        rows.track();
        visible.track();
        request_animation_frame(move || measure(scroller, viewport, row_height));
    });
    let _resize = window_event_listener(leptos::ev::resize, move |_| measure(scroller, viewport, row_height));

    // Keep focus on the active cell when the rows under it are replaced.
    Effect::new(move |_| {
        let (position, column) = active.get();
        rows.track();
        if let Some(row) = rows.with_untracked(|r| r.get(position.saturating_sub(rows_offset.get_untracked()) as usize).cloned()) {
            if position >= rows_offset.get_untracked() {
                selected.set(Some(row));
            }
        }
        if has_focus.get_untracked() {
            focus_id(format!("cell-{position}-{column}"));
        }
    });

    let scroll_to_row = move |position: u64| {
        let Some(element) = scroller.get_untracked() else { return };
        let height = row_height.get_untracked();
        let top = position as f64 * height;
        let current = element.scroll_top() as f64;
        let view = element.client_height() as f64;
        if top < current {
            element.set_scroll_top(top as i32);
        } else if top + height > current + view {
            element.set_scroll_top((top + height - view).ceil() as i32);
        }
    };

    // Go to event number: the event's first cell becomes the active one and takes focus, once
    // its row is loaded.
    Effect::new(move |_| {
        let Some((position, _)) = jump.get() else { return };
        jump.set(None);
        has_focus.set(true);
        active.set((position, 0));
        scroll_to_row(position);
        let target = format!("cell-{position}-0");
        set_timeout(move || focus_id(target), std::time::Duration::from_millis(150));
    });

    let on_scroll = move |_| {
        let Some(element) = scroller.get_untracked() else { return };
        let top = element.scroll_top() as f64;
        scroll_top.set(top);
        // Scrolling with the mouse or scroll bar moves the tab stop into view, so that tabbing
        // into the grid lands on a row that exists.
        let height = row_height.get_untracked();
        let first = (top / height).ceil() as u64;
        let last = ((top + viewport.get_untracked()) / height).floor().max(1.0) as u64 - 1;
        let (position, column) = active.get_untracked();
        if !has_focus.get_untracked() && (position < first || position > last) {
            active.set((first, column));
        }
    };

    let on_key = move |ev: leptos::ev::KeyboardEvent| {
        let total = matching();
        if total == 0 {
            return;
        }
        let (row, column) = active.get_untracked();
        let page = ((viewport.get_untracked() / row_height.get_untracked()).floor() as u64).max(1);
        let control = ev.ctrl_key() || ev.meta_key();
        let last_column = COLUMNS.len() - 1;
        let (next_row, next_column) = match ev.key().as_str() {
            "ArrowDown" => ((row + 1).min(total - 1), column),
            "ArrowUp" => (row.saturating_sub(1), column),
            "ArrowRight" => (row, (column + 1).min(last_column)),
            "ArrowLeft" => (row, column.saturating_sub(1)),
            "PageDown" => ((row + page).min(total - 1), column),
            "PageUp" => (row.saturating_sub(page), column),
            "Home" if control => (0, 0), // not-ui-text: key name
            "End" if control => (total - 1, last_column), // not-ui-text: key name
            "Home" => (row, 0),
            "End" => (row, last_column),
            _ => return,
        };
        ev.prevent_default();
        active.set((next_row, next_column));
        scroll_to_row(next_row);
    };

    let on_focus_out = move |ev: leptos::ev::FocusEvent| {
        // Focus that goes nowhere (the focused cell was re-rendered away) is not focus leaving
        // the grid; only a move to another element is.
        let Some(next) = ev.related_target() else { return };
        let inside = scroller
            .get_untracked()
            .and_then(|grid| grid.parent_element())
            .zip(next.dyn_ref::<web_sys::Node>().cloned())
            .is_some_and(|(grid, node)| grid.contains(Some(&node)));
        if !inside {
            has_focus.set(false);
        }
    };

    view! {
        <p id="grid-help" class="hint">
            {t!("events-grid-help")}
        </p>
        <div
            role="grid"
            class="grid"
            aria-labelledby="events-heading"
            aria-describedby="grid-help"
            aria-rowcount=move || (matching() + 1).to_string()
            aria-colcount=COLUMNS.len().to_string()
            on:keydown=on_key
            on:focusin=move |_| has_focus.set(true)
            on:focusout=on_focus_out
        >
            <div role="rowgroup" class="grid-head">
                <div role="row" aria-rowindex="1" class="grid-row">
                    {COLUMNS
                        .iter()
                        .enumerate()
                        .map(|(i, stem)| {
                                view! {
                                <div role="columnheader" class=format!("col col-{i}") aria-colindex=(i + 1).to_string()>
                                    {column_name(stem)}
                                </div>
                            }
                        })
                        .collect_view()}
                </div>
            </div>
            <div role="rowgroup" class="grid-body" node_ref=scroller on:scroll=on_scroll>
                <div role="presentation" class="spacer" style:height=move || format!("{}px", rows_offset.get() as f64 * row_height.get())></div>
                <For
                    each=move || {
                        let offset = rows_offset.get();
                        rows.get().into_iter().enumerate().map(move |(i, row)| (offset + i as u64, row)).collect::<Vec<_>>()
                    }
                    key=|(position, row)| (*position, row.seq)
                    children=move |(position, row)| {
                        view! {
                            <div role="row" class="grid-row" aria-rowindex=(position + 2).to_string()>
                                {(0..COLUMNS.len())
                                    .map(|column| {
                                        let (text, truncated) = cell_text(column, &row);
                                        view! {
                                            <div
                                                role="gridcell"
                                                id=format!("cell-{position}-{column}")
                                                class=format!("col col-{column}")
                                                aria-colindex=(column + 1).to_string()
                                                tabindex=move || if active.get() == (position, column) { "0" } else { "-1" }
                                                on:click=move |_| active.set((position, column))
                                                on:focus=move |_| {
                                                    if active.get_untracked() != (position, column) {
                                                        active.set((position, column));
                                                    }
                                                }
                                            >
                                                {text}
                                                {truncated.then(shortened_mark)}
                                            </div>
                                        }
                                    })
                                    .collect_view()}
                            </div>
                        }
                    }
                />
                <div
                    role="presentation"
                    class="spacer"
                    style:height=move || {
                        let after = matching().saturating_sub(rows_offset.get() + rows.with(Vec::len) as u64);
                        format!("{}px", after as f64 * row_height.get())
                    }
                ></div>
            </div>
        </div>
    }
}

fn measure(scroller: NodeRef<html::Div>, viewport: RwSignal<f64>, row_height: RwSignal<f64>) {
    let Some(element) = scroller.get_untracked() else { return };
    let height = element.client_height() as f64;
    if height > 0.0 && (height - viewport.get_untracked()).abs() > 0.5 {
        viewport.set(height);
    }
    if let Some(row) = element.query_selector(".grid-row").ok().flatten().and_then(|r| r.dyn_into::<web_sys::HtmlElement>().ok()) {
        let measured = row.offset_height() as f64;
        if measured > 0.0 && (measured - row_height.get_untracked()).abs() > 0.5 {
            row_height.set(measured);
        }
    }
}

#[component]
fn PagedTable(
    filter: RwSignal<EventFilter>,
    counts: RwSignal<Counts>,
    selected: RwSignal<Option<EventRow>>,
    jump: RwSignal<Option<(u64, u64)>>,
) -> impl IntoView {
    let page = RwSignal::new(0u64);
    // The event to show in the details once its page has loaded.
    let wanted = StoredValue::new(None::<u64>);
    let rows = RwSignal::new(Vec::<EventRow>::new());
    let go_to = RwSignal::new(String::new());
    let pages = move || counts.get().map_or(1, |(m, _)| m.div_ceil(TABLE_PAGE).max(1));

    Effect::new(move |previous: Option<()>| {
        filter.track();
        if previous.is_some() {
            page.set(0);
        }
    });
    Effect::new(move |_| {
        let request = PageRequest { offset: page.get() * TABLE_PAGE, limit: TABLE_PAGE as u32, filter: filter.get() };
        spawn_local(async move {
            match bridge::events_page(request).await {
                Ok(result) => {
                    counts.set(Some((result.total, result.unfiltered_total)));
                    let shown = wanted.get_value().and_then(|seq| result.rows.iter().find(|r| r.seq == seq).cloned());
                    wanted.set_value(None);
                    selected.set(shown.or_else(|| result.rows.first().cloned()));
                    rows.set(result.rows);
                }
                Err(message) => announcer().say(t!("events-load-failed", detail = message)),
            }
        });
    });

    Effect::new(move |_| {
        let Some((position, seq)) = jump.get() else { return };
        jump.set(None);
        wanted.set_value(Some(seq));
        page.set(position / TABLE_PAGE);
    });

    let show_page = move |target: u64| {
        let target = target.min(pages() - 1);
        page.set(target);
        let first = target * TABLE_PAGE + 1;
        let last = ((target + 1) * TABLE_PAGE).min(counts.get_untracked().map_or(0, |c| c.0));
        announcer().say(t!("events-page-announce", page = target + 1, pages = pages(), first = first, last = last));
    };

    view! {
        <nav aria-label=t!("events-pages") class="pager">
            <button type="button" disabled=move || page.get() == 0 on:click=move |_| show_page(page.get_untracked().saturating_sub(1))>
                {t!("events-previous")}
            </button>
            <span>{move || t!("events-page", page = page.get() + 1, pages = pages())}</span>
            <button type="button" disabled=move || { page.get() + 1 >= pages() } on:click=move |_| show_page(page.get_untracked() + 1)>
                {t!("events-next")}
            </button>
            <form
                class="go-to"
                on:submit=move |ev| {
                    ev.prevent_default();
                    match go_to.get_untracked().trim().replace(',', "").parse::<u64>() {
                        Ok(n) if n >= 1 && n <= pages() => show_page(n - 1),
                        _ => announcer().say(t!("events-page-range", pages = pages())),
                    }
                }
            >
                <label for="go-to-page">{t!("events-page-number")}</label>
                <input
                    id="go-to-page"
                    type="text"
                    inputmode="numeric"
                    class="input-short"
                    autocomplete="off"
                    prop:value=move || go_to.get()
                    on:input=move |ev| go_to.set(event_target_value(&ev))
                />
                <button type="submit">{t!("events-go")}</button>
            </form>
        </nav>
        <div class="table-wrap" tabindex="0" role="region" aria-labelledby="table-caption">
            <table class="simple-table">
                <caption id="table-caption">{move || t!("events-table-caption", page = page.get() + 1, pages = pages())}</caption>
                <thead>
                    <tr>{COLUMNS.iter().map(|stem| view! { <th scope="col">{column_name(stem)}</th> }).collect_view()}</tr>
                </thead>
                <tbody>
                    <For
                        each=move || rows.get()
                        key=|row| row.seq
                        children=move |row| {
                            let details = row.clone();
                            view! {
                                <tr>
                                    <th scope="row">
                                        <button type="button" class="link-button" on:click=move |_| selected.set(Some(details.clone()))>
                                            {fmt_num(row.seq)}
                                            <span class="visually-hidden">{t!("events-show-details")}</span>
                                        </button>
                                    </th>
                                    {(1..COLUMNS.len())
                                        .map(|column| {
                                            let (text, truncated) = cell_text(column, &row);
                                            view! {
                                                <td>
                                                    {text}
                                                    {truncated.then(shortened_mark)}
                                                </td>
                                            }
                                        })
                                        .collect_view()}
                                </tr>
                            }
                        }
                    />
                </tbody>
            </table>
        </div>
    }
}

/// The current event in full. Not a live region: people move through hundreds of rows, and
/// hearing every one twice would be unbearable. It is read when people go to it.
#[component]
fn EventDetails(selected: RwSignal<Option<EventRow>>) -> impl IntoView {
    let show_all = RwSignal::new(false);
    Effect::new(move |_| {
        selected.track();
        show_all.set(false);
    });
    view! {
        <section class="details" aria-labelledby="details-heading">
            <h4 id="details-heading">{t!("details-heading")}</h4>
            {move || match selected.get() {
                None => view! { <p>{t!("details-none")}</p> }.into_any(),
                Some(row) => {
                    let limit = if show_all.get() { usize::MAX } else { DETAIL_CHARS };
                    let details = display_text(&row.details, limit);
                    let location = display_text(&row.caller.clone().unwrap_or_else(|| t!("details-location-unknown")), 400);
                    view! {
                        <dl>
                            <dt>{t!("details-number")}</dt>
                            <dd>{fmt_num(row.seq)}</dd>
                            <dt>{t!("details-category")}</dt>
                            <dd>{row.category.clone()}</dd>
                            <dt>{t!("details-action")}</dt>
                            <dd>{row.action.clone()}</dd>
                            <dt>{t!("details-location")}</dt>
                            <dd class="untrusted">{location.text}</dd>
                            <dt>{t!("details-details")}</dt>
                            <dd class="untrusted">
                                {details.text}
                                {details.truncated.then(|| view! {
                                    <p>
                                        {t!("details-showing", shown = DETAIL_CHARS, count = details.original_chars)}
                                        <button type="button" on:click=move |_| show_all.set(true)>{t!("details-show-all")}</button>
                                    </p>
                                })}
                            </dd>
                        </dl>
                        <p class="hint">{t!("details-untrusted")}</p>
                    }
                        .into_any()
                }
            }}
        </section>
    }
}

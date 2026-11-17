//! The virtualised event grid: one tab stop, arrow keys between cells, rows fetched as the
//! view moves, and `aria-rowcount` so screen readers know the real size.

use super::{COLUMNS, Counts, OVERSCAN, cell_text, column_name, shortened_mark};
use leptos::prelude::*;
use crate::bridge;
use crate::util::{announcer, focus_id};
use javabyte_gui_protocol::{EventFilter, EventRow, PageRequest, t};
use leptos::html;
use leptos::task::spawn_local;
use wasm_bindgen::JsCast;

/// The virtualised grid: only rows near the view exist in the page.
#[component]
pub(super) fn EventGrid(
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
        if let Some(row) = rows.with_untracked(|r| r.get(position.saturating_sub(rows_offset.get_untracked()) as usize).cloned())
            && position >= rows_offset.get_untracked() {
                selected.set(Some(row));
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

/// Measures the real row height and view height, which change with the text size setting.
pub(super) fn measure(scroller: NodeRef<html::Div>, viewport: RwSignal<f64>, row_height: RwSignal<f64>) {
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

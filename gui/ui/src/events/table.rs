//! The simple alternative to the grid: an ordinary table, 50 events a page, with a pager.

use super::{COLUMNS, Counts, TABLE_PAGE, cell_text, column_name, shortened_mark};
use leptos::prelude::*;
use crate::bridge;
use crate::util::{announcer, fmt_num};
use javabyte_gui_protocol::{EventFilter, EventRow, PageRequest, t};
use leptos::task::spawn_local;

/// The paged table: an ordinary table, 50 events at a time.
#[component]
pub(super) fn PagedTable(
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

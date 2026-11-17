//! The current event's details, below the grid or table.

use super::DETAIL_CHARS;
use leptos::prelude::*;
use crate::util::fmt_num;
use javabyte_gui_protocol::{EventRow, display_text, t};

/// The current event in full. Not a live region: people move through hundreds of rows, and
/// hearing every one twice would be unbearable. It is read when people go to it.
#[component]
pub(super) fn EventDetails(selected: RwSignal<Option<EventRow>>) -> impl IntoView {
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

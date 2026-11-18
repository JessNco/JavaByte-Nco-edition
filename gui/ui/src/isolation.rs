//! The question before a run that cannot be isolated from the network (Linux, or Windows with
//! the sandbox add-on; see docs/gui/PHASE5-PLAN.md): why, what JavaByte still does, and how to
//! get isolation. Cancel has focus, as in every warning in this app. "Don't ask again" makes the
//! answer stick, and Preferences can undo it.
//!
//! One dialog serves every Analyse button, so it lives in the app's frame and is opened with
//! [`ask`].

use crate::bridge::{self, PreferenceChange};
use crate::util::focus_id;
use javabyte_gui_protocol::t;
use leptos::html::Dialog;
use leptos::prelude::*;
use leptos::task::spawn_local;
use std::cell::{OnceCell, RefCell};
use wasm_bindgen::JsCast;

/// The dialog's state, shared page-wide like the flow (see `app::flow`).
#[derive(Clone, Copy)]
struct Question {
    reason: RwSignal<String>,
    dont_ask: RwSignal<bool>,
    dialog: NodeRef<Dialog>,
}

/// What to do when the person agrees, and where focus was before the dialog opened.
type Pending = (Box<dyn FnOnce()>, Option<web_sys::HtmlElement>);

thread_local! {
    static QUESTION: OnceCell<Question> = const { OnceCell::new() };
    static PENDING: RefCell<Option<Pending>> = const { RefCell::new(None) };
}

fn question() -> Question {
    QUESTION.with(|cell| *cell.get_or_init(|| Question { reason: RwSignal::new(String::new()), dont_ask: RwSignal::new(false), dialog: NodeRef::new() }))
}

/// Asks before a run that cannot be isolated; calls `proceed` only if the person agrees.
pub fn ask(reason: String, proceed: impl FnOnce() + 'static) {
    let q = question();
    q.reason.set(reason);
    q.dont_ask.set(false);
    let return_to = document().active_element().and_then(|e| e.dyn_into::<web_sys::HtmlElement>().ok());
    PENDING.with(|p| *p.borrow_mut() = Some((Box::new(proceed), return_to)));
    if let Some(dialog) = q.dialog.get() {
        let _ = dialog.show_modal();
        focus_id("isolation-cancel".into());
    }
}

/// Closes the dialog; on agreement, saves "don't ask again" if ticked and goes on with the run.
fn answer(agreed: bool) {
    let q = question();
    let Some((proceed, return_to)) = PENDING.with(|p| p.borrow_mut().take()) else { return };
    if let Some(dialog) = q.dialog.get_untracked() {
        dialog.close();
    }
    if agreed {
        if q.dont_ask.get_untracked() {
            spawn_local(async {
                let _ = bridge::change_preference(PreferenceChange::AskWithoutIsolation(false)).await;
            });
        }
        proceed();
    } else if let Some(element) = return_to {
        let _ = element.focus();
    }
}

/// The dialog itself, placed once in the app's frame.
#[component]
pub fn IsolationQuestion() -> impl IntoView {
    let q = question();
    view! {
        <dialog node_ref=q.dialog class="alert" role="alertdialog" aria-modal="true" aria-labelledby="isolation-title"
            aria-describedby="isolation-reason"
            // Escape closes the dialog: the same as Cancel. (After an answer nothing is pending,
            // so the close that answer() causes does nothing more.)
            on:close=move |_| answer(false)>
            <h2 id="isolation-title">{t!("isolation-question-title")}</h2>
            <p id="isolation-reason">{move || q.reason.get()}</p>
            <p>{t!("isolation-question-still-safe")}</p>
            <div class="choice">
                <input type="checkbox" id="isolation-dont-ask" prop:checked=move || q.dont_ask.get()
                    on:change=move |ev| q.dont_ask.set(event_target_checked(&ev)) />
                <label for="isolation-dont-ask">{t!("isolation-question-dont-ask")}</label>
            </div>
            <div class="buttons">
                <button type="button" id="isolation-cancel" class="primary" on:click=move |_| answer(false)>{t!("isolation-question-cancel")}</button>
                <button type="button" id="isolation-run" on:click=move |_| answer(true)>{t!("isolation-question-run")}</button>
            </div>
        </dialog>
    }
}

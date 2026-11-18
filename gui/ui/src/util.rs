//! Small helpers shared by the screens.

use javabyte_gui_protocol::t;
use leptos::prelude::*;
use wasm_bindgen::JsCast;

/// Formats a count with thousands separators, as people read numbers ("300,000").
pub fn fmt_num(n: u64) -> String {
    let digits = n.to_string();
    let mut out = String::with_capacity(digits.len() + digits.len() / 3);
    for (i, c) in digits.chars().enumerate() {
        if i > 0 && (digits.len() - i).is_multiple_of(3) {
            out.push(',');
        }
        out.push(c);
    }
    out
}

/// Moves keyboard focus to an element by id, after the current render has reached the page.
pub fn focus_id(id: String) {
    request_animation_frame(move || {
        if let Some(element) = document().get_element_by_id(&id).and_then(|e| e.dyn_into::<web_sys::HtmlElement>().ok()) {
            let _ = element.focus();
        }
    });
}

/// `None` for empty text, so an empty filter field means "no filter".
pub fn non_empty(text: String) -> Option<String> {
    let trimmed = text.trim();
    (!trimmed.is_empty()).then(|| trimmed.to_string())
}

/// Speaks a message through the page's single polite live region.
///
/// One region for the whole app means messages never compete, and clearing it before each
/// message makes screen readers announce a repeat of the same text.
#[derive(Clone, Copy)]
pub struct Announcer(RwSignal<String>);

impl Announcer {
    /// An announcer with nothing said yet.
    pub fn new() -> Self {
        Self(RwSignal::new(String::new()))
    }

    /// Says a message politely. The region is emptied first, so saying the same thing twice is
    /// still announced.
    pub fn say(&self, message: impl Into<String>) {
        let message = message.into();
        let text = self.0;
        text.set(String::new());
        set_timeout(move || text.set(message), std::time::Duration::from_millis(80));
    }

    /// What is being said now.
    pub fn text(&self) -> String {
        self.0.get()
    }
}

thread_local! {
    static ANNOUNCER: std::cell::OnceCell<Announcer> = const { std::cell::OnceCell::new() };
}

/// The app's announcer. Stored for the whole page rather than in Leptos context, because it is
/// used from timers and asynchronous replies, which run outside any component and so cannot
/// see context (looking it up there panics and stops the screens).
pub fn announcer() -> Announcer {
    ANNOUNCER.with(|cell| *cell.get_or_init(Announcer::new))
}

/// Copies text to the clipboard. False where the web view does not allow it, so the screen can
/// say how to copy by hand instead.
pub fn copy_text(text: &str) -> bool {
    // Reached through reflection: the Clipboard API is not in every web-sys build, and the
    // promise's result is not needed to tell the person something useful.
    let Some(window) = web_sys::window() else { return false };
    let window: wasm_bindgen::JsValue = window.into();
    let Ok(navigator) = js_sys::Reflect::get(&window, &"navigator".into()) else { return false };
    let Ok(clipboard) = js_sys::Reflect::get(&navigator, &"clipboard".into()) else { return false };
    if clipboard.is_undefined() {
        return false;
    }
    let Ok(write) = js_sys::Reflect::get(&clipboard, &"writeText".into()) else { return false };
    let Ok(write) = write.dyn_into::<js_sys::Function>() else { return false };
    write.call1(&clipboard, &text.into()).is_ok()
}

/// The message after a copy attempt.
pub fn copied_message(done: bool) -> String {
    if done { t!("copied") } else { t!("copy-unavailable") }
}

/// File size for people: "13.6 KB".
pub fn fmt_size(bytes: u64) -> String {
    match bytes {
        0..=1023 => t!("size-bytes", count = bytes),
        1024..=1_048_575 => t!("size-kb", size = format!("{:.1}", bytes as f64 / 1024.0)),
        _ => t!("size-mb", size = format!("{:.1}", bytes as f64 / 1_048_576.0)),
    }
}

/// A button that shows or hides what follows it (the WAI-ARIA disclosure pattern). Used instead
/// of `<details>`, which WebKitGTK reports to screen readers with no role and no open or closed
/// state; a button with `aria-expanded` is announced properly everywhere.
#[component]
pub fn Disclosure(label: String, #[prop(optional)] class: &'static str, children: Children) -> impl IntoView {
    static NEXT: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
    let id = format!("disclosure-{}", NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed));
    let open = RwSignal::new(false);
    view! {
        <div class=format!("disclosure {class}")>
            <button
                type="button"
                class="disclosure-toggle"
                aria-expanded=move || open.get().to_string()
                aria-controls=id.clone()
                on:click=move |_| open.update(|o| *o = !*o)
            >
                <span class="marker" aria-hidden="true">{move || if open.get() { "▾" } else { "▸" }}</span>
                {label}
            </button>
            <div class="disclosure-body" id=id.clone() hidden=move || !open.get()>{children()}</div>
        </div>
    }
}

/// Opens every closed disclosure around an element, so that it can be shown and take focus.
pub fn open_disclosures_around(element: &web_sys::Element) {
    let mut node = element.parent_element();
    while let Some(parent) = node {
        if parent.class_list().contains("disclosure-body") && parent.has_attribute("hidden")
            && let Some(toggle) = parent.previous_element_sibling().and_then(|t| t.dyn_into::<web_sys::HtmlElement>().ok()) {
                toggle.click();
            }
        node = parent.parent_element();
    }
}

/// One screen's tabs, as an enum. Each value's stem names its tab's element ids
/// (`<prefix>-tab-<stem>` and `<prefix>-panel-<stem>`), so a panel follows its tab by name
/// rather than by a position that must be kept in step with a list by hand.
pub trait Tab: Copy + PartialEq + Send + Sync + 'static {
    /// Every tab, in the order the tab list shows them.
    const ALL: &'static [Self];
    /// The tab's stem, as used in its element ids and its label's message id.
    fn stem(self) -> &'static str;
}

/// Where a key press in a tab list moves the selection, as a position among `len` tabs: the
/// arrow keys move one tab with wraparound, Home and End go to the ends. None for any other key,
/// which the tab list leaves alone.
pub fn next_tab_index(current: usize, len: usize, key: &str) -> Option<usize> {
    if len == 0 {
        return None;
    }
    match key {
        "ArrowRight" => Some((current + 1) % len),
        "ArrowLeft" => Some((current + len - 1) % len),
        "Home" => Some(0),
        "End" => Some(len - 1),
        _ => None,
    }
}

/// A tab list in the WAI-ARIA tabs pattern with automatic activation, as tested in phase 0:
/// one button per tab with `role="tab"`, `aria-controls` and `aria-selected`, a roving
/// `tabindex`, and the arrow, Home and End keys moving both selection and focus. `prefix` starts
/// every id; `label` gives each tab's text, and is called reactively so it may change.
///
/// Shared by the Report, Tools and Settings screens so the keyboard behaviour lives in one place.
pub fn tab_list<T: Tab>(prefix: &'static str, aria_label: String, active: RwSignal<T>, label: impl Fn(T) -> String + Copy + Send + Sync + 'static) -> impl IntoView {
    let on_key = move |ev: web_sys::KeyboardEvent| {
        let current = T::ALL.iter().position(|t| *t == active.get_untracked()).unwrap_or(0);
        let Some(next) = next_tab_index(current, T::ALL.len(), &ev.key()) else { return };
        ev.prevent_default();
        let tab = T::ALL[next];
        active.set(tab);
        focus_id(format!("{prefix}-tab-{}", tab.stem()));
    };
    view! {
        <div role="tablist" aria-label=aria_label class="tablist" on:keydown=on_key>
            {T::ALL.iter().map(|&tab| view! {
                <button
                    type="button"
                    role="tab"
                    id=format!("{prefix}-tab-{}", tab.stem())
                    aria-controls=format!("{prefix}-panel-{}", tab.stem())
                    aria-selected=move || (active.get() == tab).to_string()
                    tabindex=move || if active.get() == tab { "0" } else { "-1" }
                    on:click=move |_| active.set(tab)
                >
                    {move || label(tab)}
                </button>
            }).collect_view()}
        </div>
    }
}

#[cfg(test)]
mod tests {
    use super::next_tab_index;

    #[test]
    fn tab_keys_wrap_and_reach_the_ends() {
        assert_eq!(next_tab_index(0, 6, "ArrowRight"), Some(1));
        assert_eq!(next_tab_index(5, 6, "ArrowRight"), Some(0));
        assert_eq!(next_tab_index(0, 6, "ArrowLeft"), Some(5));
        assert_eq!(next_tab_index(3, 6, "Home"), Some(0));
        assert_eq!(next_tab_index(3, 6, "End"), Some(5));
        assert_eq!(next_tab_index(3, 6, "Enter"), None);
        assert_eq!(next_tab_index(0, 0, "ArrowRight"), None);
    }
}

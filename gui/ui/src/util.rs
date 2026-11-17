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
    /// /// still announced.
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

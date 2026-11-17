//! Small helpers shared by the screens.

use leptos::prelude::*;
use wasm_bindgen::JsCast;

/// Formats a count with thousands separators, as people read numbers ("300,000").
pub fn fmt_num(n: u64) -> String {
    let digits = n.to_string();
    let mut out = String::with_capacity(digits.len() + digits.len() / 3);
    for (i, c) in digits.chars().enumerate() {
        if i > 0 && (digits.len() - i) % 3 == 0 {
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
    pub fn new() -> Self {
        Self(RwSignal::new(String::new()))
    }

    pub fn say(&self, message: impl Into<String>) {
        let message = message.into();
        let text = self.0;
        text.set(String::new());
        set_timeout(move || text.set(message), std::time::Duration::from_millis(80));
    }

    pub fn text(&self) -> String {
        self.0.get()
    }
}

pub fn announcer() -> Announcer {
    expect_context::<Announcer>()
}

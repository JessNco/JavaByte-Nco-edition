//! The YAML tab: a plain text area, with line numbers beside it that screen readers skip.

use crate::settings::{replace_text, settings};
use javabyte_gui_protocol::t;
use leptos::prelude::*;

/// The YAML tab: a plain text area, with line numbers beside it that screen readers skip.
#[component]
pub(super) fn YamlEditor() -> impl IntoView {
    let s = settings();
    let area = NodeRef::<leptos::html::Textarea>::new();
    let gutter = NodeRef::<leptos::html::Pre>::new();
    let pending = StoredValue::new(0u64);
    // Put the text into the area when it changes elsewhere (forms, presets); not while typing,
    // which would move the caret.
    Effect::new(move |_| {
        let text = s.text.get();
        if let Some(area) = area.get()
            && area.value() != text {
                area.set_value(&text);
            }
    });
    let numbers = move || (1..=s.text.with(|t| t.lines().count().max(1))).map(|n| n.to_string()).collect::<Vec<_>>().join("\n");
    view! {
        <div class="field">
            <label for="settings-yaml">{t!("yaml-label")}</label>
            <p class="hint" id="settings-yaml-hint">{t!("yaml-hint")}</p>
            <div class="yaml-editor">
                <pre class="gutter" aria-hidden="true" node_ref=gutter>{numbers}</pre>
                <textarea
                    id="settings-yaml"
                    node_ref=area
                    rows="24"
                    wrap="off"
                    spellcheck="false"
                    autocomplete="off"
                    aria-describedby="settings-yaml-hint"
                    on:scroll=move |_| {
                        if let (Some(area), Some(gutter)) = (area.get(), gutter.get()) {
                            gutter.set_scroll_top(area.scroll_top());
                        }
                    }
                    on:input=move |ev| {
                        // The forms follow after a short pause, not on every key.
                        let text = event_target_value(&ev);
                        let generation = pending.get_value() + 1;
                        pending.set_value(generation);
                        set_timeout(move || {
                            if pending.get_value() == generation {
                                replace_text(text);
                            }
                        }, std::time::Duration::from_millis(400));
                    }
                ></textarea>
            </div>
        </div>
    }
}

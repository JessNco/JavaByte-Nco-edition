//! Import, export, and the same settings as a command line.

use crate::app::flow;
use crate::bridge;
use crate::settings::{self, replace_text, settings};
use crate::util::{announcer, copied_message, copy_text};
use javabyte_gui_protocol::t;
use leptos::prelude::*;
use leptos::task::spawn_local;

/// Import, export and the command line.
#[component]
pub(super) fn Share(status: RwSignal<String>) -> impl IntoView {
    let s = settings();
    let flow = flow();
    let copied = RwSignal::new(String::new());
    let _ = status;
    view! {
        <h3 id="share-heading">{t!("settings-share-heading")}</h3>
        <div class="buttons">
            <button type="button" on:click=move |_| {
                spawn_local(async move {
                    match bridge::import_settings().await {
                        Ok(Some(imported)) => {
                            let sample = flow.sample.get_untracked();
                            let text = javabyte_gui_backend::session::with_sample(&imported.text, sample.as_ref());
                            s.undo.set(Some(s.text.get_untracked()));
                            replace_text(text);
                            let other = imported.sample.filter(|p| sample.as_ref().is_none_or(|s| s.path != *p));
                            announcer().say(match other {
                                Some(path) => t!("settings-import-other-sample", sample = path),
                                None => t!("settings-imported"),
                            });
                        }
                        Ok(None) => {}
                        Err(message) => announcer().say(message),
                    }
                });
            }>{t!("settings-import")}</button>
            <button type="button" on:click=move |_| {
                spawn_local(async move {
                    match bridge::export_settings(&s.text.get_untracked()).await {
                        Ok(true) => announcer().say(t!("settings-exported")),
                        Ok(false) => {}
                        Err(message) => announcer().say(message),
                    }
                });
            }>{t!("settings-export")}</button>
            <button type="button" on:click=move |_| copied.set(copied_message(copy_text(&settings::command_line())))>{t!("settings-copy-command")}</button>
            <span role="status" class="inline-status">{move || copied.get()}</span>
        </div>
        <h4 id="settings-command-heading">{t!("settings-command-heading")}</h4>
        <pre class="command untrusted" aria-labelledby="settings-command-heading" tabindex="0"><code>{settings::command_line}</code></pre>
    }
}

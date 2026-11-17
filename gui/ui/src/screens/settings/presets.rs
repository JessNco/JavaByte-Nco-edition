//! Presets: built-in next steps that fill in fields people can see, and saved presets.

use crate::app::flow;
use crate::bridge;
use crate::settings::{edit, replace_text, settings};
use crate::util::{Disclosure, announcer, copied_message, copy_text, focus_id, open_disclosures_around};
use javabyte_gui_backend::config::ConfigPath;
use javabyte_gui_protocol::form::{self};
use javabyte_gui_protocol::i18n::tr;
use javabyte_gui_protocol::{display_text, t};
use leptos::prelude::*;
use leptos::task::spawn_local;
use serde_json::{Value, json};
use super::items::items_len;

/// Built-in and saved presets.
#[component]
pub(super) fn Presets() -> impl IntoView {
    let s = settings();
    let flow = flow();
    let saved = RwSignal::new(Vec::<javabyte_gui_protocol::views::Preset>::new());
    let folder = RwSignal::new(String::new());
    let new_name = RwSignal::new(String::new());
    let copied = RwSignal::new(String::new());
    let refresh = move || {
        spawn_local(async move {
            saved.set(bridge::presets().await);
            folder.set(bridge::presets_folder().await);
        });
    };
    refresh();

    // What the last report of this sample offers.
    let follow_ups = move || {
        let sample = flow.sample.get()?;
        flow.report.with(|r| r.as_ref().filter(|r| r.sample_path == sample.path).map(|r| r.follow_ups.clone()))
    };

    let builtins = move || {
        let follow = follow_ups().unwrap_or_default();
        let sleeps_now = s.value(&ConfigPath::parse("limits.maxSleeps")).and_then(|v| v.as_u64()).unwrap_or(40);
        let mut list: Vec<(&'static str, String)> = Vec::new();
        if let Some(args) = follow.relaunch_args.first() {
            list.push(("relaunch", t!("preset-relaunch-help", args = args.join(" "))));
        }
        if !follow.missing_files.is_empty() {
            list.push(("files", t!("preset-files-help", count = follow.missing_files.len())));
        }
        if !follow.unanswered_urls.is_empty() {
            list.push(("urls", t!("preset-urls-help", count = follow.unanswered_urls.len())));
        }
        list.push(("sleeps", t!("preset-sleeps-help", from = sleeps_now, to = (sleeps_now * 5).max(200))));
        list.push(("sample-path", t!("preset-sample-path-help")));
        list.push(("hook", t!("preset-hook-help")));
        list.push(("sweep", t!("preset-sweep-help")));
        list.push(("clock", t!("preset-clock-help")));
        list.push(("native", t!("preset-native-help")));
        list
    };

    view! {
        <Disclosure label=t!("presets-heading") class="presets">
            <p>{t!("presets-intro")}</p>
            <h3 id="presets-builtin">{t!("presets-builtin")}</h3>
            <ul class="preset-list" aria-labelledby="presets-builtin">
                {move || builtins().into_iter().map(|(id, help)| {
                    let help_id = format!("preset-{id}-help");
                    let follow = follow_ups().unwrap_or_default();
                    view! {
                        <li>
                            <button type="button" aria-describedby=help_id.clone() on:click=move |_| apply_builtin(id, &follow)>
                                {tr(&format!("preset-{id}"), None)}
                            </button>
                            <p class="hint untrusted" id=help_id>{display_text(&help, 300).text}</p>
                        </li>
                    }
                }).collect_view()}
            </ul>
            <Show when=move || s.undo.with(Option::is_some)>
                <button type="button" on:click=move |_| {
                    if let Some(before) = s.undo.get_untracked() {
                        s.undo.set(None);
                        replace_text(before);
                        announcer().say(t!("presets-undone"));
                    }
                }>{t!("presets-undo")}</button>
            </Show>

            <h3 id="presets-saved">{t!("presets-saved")}</h3>
            {move || {
                let list = saved.get();
                if list.is_empty() {
                    return view! { <p>{t!("presets-saved-none")}</p> }.into_any();
                }
                view! {
                    <ul class="preset-list" aria-labelledby="presets-saved">
                        {list.into_iter().map(|preset| {
                            let (apply_name, delete_name) = (preset.name.clone(), preset.name.clone());
                            view! {
                                <li class="untrusted">
                                    {display_text(&preset.name, 80).text}" "
                                    <button type="button" aria-label=t!("presets-apply-label", name = preset.name.clone()) on:click=move |_| {
                                        let name = apply_name.clone();
                                        spawn_local(async move {
                                            match bridge::load_preset(&name).await {
                                                Ok(text) => {
                                                    s.undo.set(Some(s.text.get_untracked()));
                                                    replace_text(text);
                                                    announcer().say(t!("presets-applied", name = name));
                                                }
                                                Err(message) => announcer().say(message),
                                            }
                                        });
                                    }>{t!("presets-apply")}</button>
                                    <button type="button" aria-label=t!("presets-delete-label", name = preset.name.clone()) on:click=move |_| {
                                        let name = delete_name.clone();
                                        spawn_local(async move {
                                            match bridge::delete_preset(&name).await {
                                                Ok(()) => {
                                                    announcer().say(t!("presets-deleted", name = name));
                                                    saved.set(bridge::presets().await);
                                                    focus_id("preset-new-name".into());
                                                }
                                                Err(message) => announcer().say(message),
                                            }
                                        });
                                    }>{t!("presets-delete")}</button>
                                </li>
                            }
                        }).collect_view()}
                    </ul>
                }
                .into_any()
            }}
            <form class="input-row" on:submit=move |ev| {
                ev.prevent_default();
                let name = new_name.get_untracked();
                spawn_local(async move {
                    match bridge::save_preset(&name, &s.text.get_untracked()).await {
                        Ok(preset) => {
                            new_name.set(String::new());
                            saved.set(bridge::presets().await);
                            announcer().say(t!("presets-saved-done", name = preset.name));
                        }
                        Err(message) => announcer().say(message),
                    }
                });
            }>
                <label for="preset-new-name">{t!("presets-save-name")}</label>
                <input id="preset-new-name" type="text" class="input-long" autocomplete="off" prop:value=move || new_name.get() on:input=move |ev| new_name.set(event_target_value(&ev)) />
                <button type="submit">{t!("presets-save")}</button>
            </form>
            <p>{t!("presets-folder")}</p>
            <pre class="command untrusted" tabindex="0" aria-label=t!("presets-folder")><code>{move || folder.get()}</code></pre>
            <div class="buttons">
                <button type="button" on:click=move |_| copied.set(copied_message(copy_text(&folder.get_untracked())))>{t!("presets-copy-folder")}</button>
                <span role="status" class="inline-status">{move || copied.get()}</span>
            </div>
        </Disclosure>
    }
}

/// Escapes a web address so that, as a pattern, it matches only itself.
pub(super) fn literal_pattern(text: &str) -> String {
    let mut out = String::with_capacity(text.len() + 8);
    for c in text.chars() {
        if "\\.^$|?*+()[]{}".contains(c) {
            out.push('\\');
        }
        out.push(c);
    }
    out
}

/// Applies a built-in preset: it only fills in fields, then shows the first one it changed.
pub(super) fn apply_builtin(id: &'static str, follow: &javabyte_gui_protocol::views::FollowUps) {
    let s = settings();
    let before = s.text.get_untracked();
    let entry_points = ConfigPath::parse("entryPoints");
    let mut focus: Option<String> = None;
    let mut open: Option<(String, usize)> = None;
    let result = match id {
        "relaunch" => {
            let args: Vec<Value> = follow.relaunch_args.first().cloned().unwrap_or_default().into_iter().map(Value::String).collect();
            focus = Some(form::field_element_id("entryPoints[0].args"));
            open = Some(("entryPoints".into(), 0));
            edit(|doc| {
                if doc.get(&entry_points).and_then(|v| v.as_array().map(Vec::len)).unwrap_or(0) == 0 {
                    doc.push(&entry_points, &json!({"type": "main"}))?;
                }
                doc.set(&entry_points.clone().index(0).key("args"), &Value::Array(args))
            })
        }
        "files" => {
            let files = ConfigPath::parse("environment.files");
            focus = Some(form::field_element_id("environment.files"));
            let existing: Vec<String> = s
                .value(&files)
                .and_then(|v| v.as_array().cloned())
                .unwrap_or_default()
                .iter()
                .filter_map(|f| f.get("path").and_then(Value::as_str).map(str::to_string))
                .collect();
            edit(|doc| {
                for path in follow.missing_files.iter().filter(|p| !existing.contains(p)) {
                    doc.push(&files, &json!({"path": path, "text": ""}))?;
                }
                Ok(())
            })
        }
        "urls" => {
            let http = ConfigPath::parse("network.http");
            focus = Some(form::field_element_id("network.http"));
            edit(|doc| {
                for url in &follow.unanswered_urls {
                    doc.push(&http, &json!({"url": literal_pattern(url), "status": 200, "body": ""}))?;
                }
                Ok(())
            })
        }
        "sleeps" => {
            let now = s.value(&ConfigPath::parse("limits.maxSleeps")).and_then(|v| v.as_u64()).unwrap_or(40);
            focus = Some(form::field_element_id("limits.maxSleeps"));
            edit(|doc| doc.set(&ConfigPath::parse("limits.maxSleeps"), &json!((now * 5).max(200))))
        }
        "sample-path" => {
            let name = flow().sample.get_untracked().map(|s| s.name).unwrap_or_default();
            let windows = s.value(&ConfigPath::parse("environment.os")).and_then(|v| v.as_str().map(str::to_string)).is_none_or(|os| os == "windows");
            let user = s.value(&ConfigPath::parse("environment.userName")).and_then(|v| v.as_str().map(str::to_string)).unwrap_or_else(|| "admin".into());
            let path = if windows { format!("C:\\Users\\{user}\\AppData\\Roaming\\{name}") } else { format!("/home/{user}/.{name}") };
            focus = Some(form::field_element_id("environment.samplePath"));
            edit(|doc| doc.set(&ConfigPath::parse("environment.samplePath"), &json!(path)))
        }
        "hook" => {
            let hooks = ConfigPath::parse("hooks");
            let count = items_len(&hooks);
            open = Some(("hooks".into(), count));
            focus = Some(form::field_element_id(&format!("hooks[{count}].method")));
            edit(|doc| doc.push(&hooks, &json!({"method": "", "action": "return", "value": false})))
        }
        "sweep" => {
            focus = Some(form::field_element_id("entryPoints"));
            edit(|doc| {
                if doc.get(&entry_points).and_then(|v| v.as_array().map(Vec::len)).unwrap_or(0) == 0 {
                    doc.push(&entry_points, &json!({"type": "main"}))?;
                }
                doc.push(&entry_points, &json!({"type": "clinit-sweep"}))
            })
        }
        "clock" => {
            focus = Some(form::field_element_id("environment.clockStart"));
            edit(|doc| doc.set(&ConfigPath::parse("environment.clockStart"), &json!("2024-01-01T09:00:00Z")))
        }
        "native" => {
            focus = Some(form::field_element_id("plugins.native-bridge"));
            edit(|doc| {
                if doc.get(&ConfigPath::parse("plugins").key("native-bridge")).is_some() {
                    Ok(())
                } else {
                    doc.set(&ConfigPath::parse("plugins").key("native-bridge"), &json!({}))
                }
            })
        }
        _ => Ok(()),
    };
    match result {
        Ok(()) => {
            s.undo.set(Some(before));
            s.tab.set(0);
            if let Some(open) = open {
                s.editing.set(Some(open));
            }
            announcer().say(t!("presets-applied", name = tr(&format!("preset-{id}"), None)));
            if let Some(id) = focus {
                request_animation_frame(move || {
                    if let Some(element) = document().get_element_by_id(&id) {
                        open_disclosures_around(&element);
                    }
                    focus_id(id);
                });
            }
        }
        Err(message) => announcer().say(message),
    }
}

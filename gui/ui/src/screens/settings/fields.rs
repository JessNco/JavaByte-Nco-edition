//! One option's field, for each kind of widget: text, numbers, choices, tick boxes, lists of
//! values, names and values, and plugins.

use crate::app::flow;
use crate::bridge;
use crate::settings::{set_value, settings};
use javabyte_gui_backend::config::ConfigPath;
use javabyte_gui_protocol::form::{self, Widget};
use javabyte_gui_protocol::i18n::tr;
use javabyte_gui_protocol::schema::{ConfigOption, Schema};
use javabyte_gui_protocol::{display_text, t};
use leptos::prelude::*;
use leptos::task::spawn_local;
use serde_json::{Value, json};
use super::items::items_view;

/// The text a value shows in a field.
pub(super) fn shown(value: &Value) -> String {
    match value {
        Value::String(s) => s.clone(),
        Value::Null => String::new(),
        other => other.to_string(),
    }
}

/// A field's label: its message, or for a plugin the tool's own setting name.
pub(super) fn label_for(schema_path: &str) -> String {
    let id = form::label_id(schema_path);
    if javabyte_gui_protocol::i18n::has(&id) { tr(&id, None) } else { schema_path.rsplit('.').next().unwrap_or(schema_path).to_string() }
}

/// One option's field at a concrete path.
pub(super) fn field_view(schema: &Schema, option: ConfigOption, path: ConfigPath) -> AnyView {
    let s = settings();
    let path_text = path.to_string();
    let schema_path = option.path.clone();
    let id = form::field_element_id(&path_text);
    let label = label_for(&schema_path);
    let help_id = format!("{id}-help");
    let error_id = format!("{id}-error");
    let default_text = match &option.default {
        Some(Value::Array(a)) if a.is_empty() => None,
        Some(Value::Object(m)) if m.is_empty() => None,
        Some(value) => Some(t!("settings-default", value = shown(value))),
        None => None,
    };
    let error = {
        let path_text = path_text.clone();
        Memo::new(move |_| s.problems().into_iter().find(|p| p.key == path_text).map(|p| p.message))
    };
    let described = {
        let (help_id, error_id) = (help_id.clone(), error_id.clone());
        move || if error.get().is_some() { format!("{help_id} {error_id}") } else { help_id.clone() }
    };
    let help = view! {
        <p class="hint" id=help_id.clone()>
            {option.description.clone()}
            {default_text.clone().map(|d| view! { " " <span class="default">{d}</span> })}
            {(schema_path == "output.directory").then(|| view! { " " {t!("output-directory-note")} })}
        </p>
    };
    let error_view = {
        let error_id = error_id.clone();
        move || error.get().map(|message| view! {
            <p class="field-error" id=error_id.clone()>{t!("field-problem", message = display_text(&message, 300).text)}</p>
        })
    };
    let current = {
        let path = path.clone();
        move || s.value(&path)
    };
    let reset = {
        let path = path.clone();
        let label = label.clone();
        let current = current.clone();
        move || current().is_some().then(|| {
            let path = path.clone();
            view! {
                <button type="button" class="link-button reset" aria-label=t!("settings-reset-label", name = label.clone()) on:click=move |_| set_value(&path, None)>
                    {t!("settings-reset")}
                </button>
            }
        })
    };

    match form::widget(&option) {
        Widget::ReadOnly => view! {
            <div class="field">
                <p><strong>{label}</strong>": "<span class="untrusted">{move || flow().sample.with(|s| s.as_ref().map(|s| display_text(&s.path, 300).text).unwrap_or_default())}</span></p>
                <p class="hint">{t!("settings-sample-hint")}</p>
            </div>
        }
        .into_any(),
        Widget::Text | Widget::Number | Widget::Value => {
            let kind = form::widget(&option);
            let (is_number, is_value) = (kind == Widget::Number, kind == Widget::Value);
            let host_file = form::HOST_FILES.contains(&schema_path.as_str());
            let commit = {
                let path = path.clone();
                move |raw: String| {
                    let value = if raw.is_empty() {
                        None
                    } else {
                        Some(match kind {
                            // Not a whole number: kept as written, and the tool's check says so.
                            Widget::Number => raw.trim().parse::<i64>().map_or(Value::String(raw.clone()), |n| json!(n)),
                            Widget::Value => match raw.trim() {
                                "true" => json!(true),
                                "false" => json!(false),
                                "null" => Value::Null,
                                other => other.parse::<i64>().map_or(Value::String(raw.clone()), |n| json!(n)),
                            },
                            _ => Value::String(raw),
                        })
                    };
                    set_value(&path, value);
                }
            };
            let choose = host_file.then(|| {
                let commit = commit.clone();
                let label = label.clone();
                view! {
                    <button type="button" aria-label=t!("settings-choose-file-label", name = label) on:click=move |_| {
                        let commit = commit.clone();
                        spawn_local(async move {
                            if let Some(path) = bridge::choose_host_file().await {
                                commit(path);
                            }
                        });
                    }>{t!("settings-choose-file")}</button>
                }
            });
            let value_hint = is_value.then(|| view! { <p class="hint">{t!("settings-value-hint")}</p> });
            view! {
                <div class="field" class:has-error=move || error.get().is_some()>
                    <label for=id.clone()>{label.clone()}</label>
                    {help}
                    {value_hint}
                    {error_view}
                    <div class="input-row">
                        <input
                            id=id.clone()
                            type="text"
                            inputmode=if is_number { "numeric" } else { "text" }
                            class=if is_number { "input-short" } else { "input-long" }
                            autocomplete="off"
                            spellcheck="false"
                            aria-describedby=described
                            aria-invalid=move || error.get().is_some().then_some("true")
                            prop:value=move || current().map(|v| shown(&v)).unwrap_or_default()
                            on:change=move |ev| commit(event_target_value(&ev))
                        />
                        {choose}
                        {reset}
                    </div>
                </div>
            }
            .into_any()
        }
        Widget::Check => {
            let default = option.default.as_ref().and_then(Value::as_bool).unwrap_or(false);
            let path = path.clone();
            view! {
                <div class="field">
                    <div class="choice">
                        <input
                            type="checkbox"
                            id=id.clone()
                            aria-describedby=described
                            prop:checked=move || current().and_then(|v| v.as_bool()).unwrap_or(default)
                            on:change=move |ev| set_value(&path, Some(json!(event_target_checked(&ev))))
                        />
                        <label for=id.clone()>{label}</label>
                    </div>
                    {help}
                    {error_view}
                    {reset}
                </div>
            }
            .into_any()
        }
        Widget::Radio(values) => {
            let default = option.default.as_ref().and_then(Value::as_str).map(str::to_string);
            let name = id.clone();
            view! {
                <fieldset class="field" id=id.clone() tabindex="-1" aria-describedby=described>
                    <legend>{label}</legend>
                    {help}
                    {error_view}
                    {values.into_iter().map(|value| {
                        let option_id = format!("{name}-{value}");
                        let choice_label = tr(&form::choice_id(&schema_path, &value), None);
                        let (path, default, current) = (path.clone(), default.clone(), current.clone());
                        let checked_value = value.clone();
                        view! {
                            <div class="choice">
                                <input
                                    type="radio"
                                    name=name.clone()
                                    id=option_id.clone()
                                    prop:checked=move || current().and_then(|v| v.as_str().map(str::to_string)).or(default.clone()).as_deref() == Some(checked_value.as_str())
                                    on:change=move |_| set_value(&path, Some(json!(value)))
                                />
                                <label for=option_id>{choice_label}</label>
                            </div>
                        }
                    }).collect_view()}
                    {reset}
                </fieldset>
            }
            .into_any()
        }
        Widget::Select(values) => {
            let default = option.default.as_ref().and_then(Value::as_str).map(str::to_string);
            let path = path.clone();
            view! {
                <div class="field">
                    <label for=id.clone()>{label}</label>
                    {help}
                    {error_view}
                    <select id=id.clone() aria-describedby=described on:change=move |ev| set_value(&path, Some(json!(event_target_value(&ev))))>
                        {values.into_iter().map(|value| {
                            let (default, current) = (default.clone(), current.clone());
                            let selected_value = value.clone();
                            view! {
                                <option value=value.clone() selected=move || current().and_then(|v| v.as_str().map(str::to_string)).or(default.clone()).as_deref() == Some(selected_value.as_str())>
                                    {tr(&form::choice_id(&schema_path, &value), None)}
                                </option>
                            }
                        }).collect_view()}
                    </select>
                    {reset}
                </div>
            }
            .into_any()
        }
        Widget::Lines => {
            let path = path.clone();
            let lines = move || {
                let items: Vec<Value> = current().and_then(|v| v.as_array().cloned()).unwrap_or_default();
                items.iter().map(shown).collect::<Vec<String>>().join("\n")
            };
            view! {
                <div class="field">
                    <label for=id.clone()>{label}</label>
                    {help}
                    <p class="hint">{t!("settings-lines-hint")}</p>
                    {error_view}
                    <textarea
                        id=id.clone()
                        rows="3"
                        spellcheck="false"
                        aria-describedby=described
                        prop:value=lines
                        on:change=move |ev| commit_lines(&path, &event_target_value(&ev))
                    ></textarea>
                </div>
            }
            .into_any()
        }
        Widget::Pairs => pairs_view(id, label, help.into_any(), path).into_any(),
        Widget::Items => items_view(schema.clone(), id, label, help.into_any(), path, schema_path).into_any(),
        Widget::Plugins => plugins_view(schema.clone()).into_any(),
        Widget::Group => ().into_any(),
    }
}

/// Names and values (properties, environment variables, host names, headers).
pub(super) fn pairs_view(id: String, label: String, help: AnyView, path: ConfigPath) -> impl IntoView {
    let s = settings();
    let new_name = RwSignal::new(String::new());
    let new_value = RwSignal::new(String::new());
    let entries = {
        let path = path.clone();
        move || s.value(&path).and_then(|v| v.as_object().cloned()).unwrap_or_default()
    };
    let add = {
        let path = path.clone();
        move || {
            let name = new_name.get_untracked().trim().to_string();
            if name.is_empty() {
                return;
            }
            set_value(&path.clone().key(name), Some(json!(new_value.get_untracked())));
            new_name.set(String::new());
            new_value.set(String::new());
        }
    };
    let heading_id = format!("{id}-label");
    let labelled_by = heading_id.clone();
    view! {
        <div class="field pairs" role="group" aria-labelledby=labelled_by id=id.clone() tabindex="-1">
            <p class="label" id=heading_id>{label.clone()}</p>
            {help}
            {move || {
                let entries = entries();
                if entries.is_empty() {
                    return view! { <p>{t!("settings-pairs-empty")}</p> }.into_any();
                }
                let path = path.clone();
                view! {
                    <div class="table-wrap">
                        <table class="simple-table compact">
                            <thead><tr>
                                <th scope="col">{t!("settings-pairs-name")}</th>
                                <th scope="col">{t!("settings-pairs-value")}</th>
                                <th scope="col"><span class="visually-hidden">{t!("items-actions")}</span></th>
                            </tr></thead>
                            <tbody>
                                {entries.into_iter().map(|(key, value)| {
                                    let path = path.clone().key(key.clone());
                                    view! {
                                        <tr>
                                            <th scope="row" class="untrusted">{display_text(&key, 200).text}</th>
                                            <td class="untrusted">{display_text(&shown(&value), 200).text}</td>
                                            <td>
                                                <button type="button" aria-label=t!("settings-pairs-remove-label", key = key.clone()) on:click=move |_| set_value(&path, None)>
                                                    {t!("settings-pairs-remove")}
                                                </button>
                                            </td>
                                        </tr>
                                    }
                                }).collect_view()}
                            </tbody>
                        </table>
                    </div>
                }
                .into_any()
            }}
            <div class="input-row">
                <label for=format!("{id}-new-name")>{t!("settings-pairs-name")}</label>
                <input id=format!("{id}-new-name") type="text" class="input-short" autocomplete="off" spellcheck="false" prop:value=move || new_name.get() on:input=move |ev| new_name.set(event_target_value(&ev)) />
                <label for=format!("{id}-new-value")>{t!("settings-pairs-value")}</label>
                <input id=format!("{id}-new-value") type="text" class="input-short" autocomplete="off" spellcheck="false" prop:value=move || new_value.get() on:input=move |ev| new_value.set(event_target_value(&ev)) />
                <button type="button" aria-label=t!("settings-pairs-add-label", name = label) on:click=move |_| add()>{t!("settings-pairs-add")}</button>
            </div>
        </div>
    }
}

/// A list of single values from text, one per line; empty lines are left out.
pub(super) fn commit_lines(path: &ConfigPath, text: &str) {
    let lines: Vec<Value> = text.lines().map(str::trim).filter(|l| !l.is_empty()).map(|l| json!(l)).collect();
    set_value(path, (!lines.is_empty()).then_some(Value::Array(lines)));
}

/// Plugins: a switch for each, and its settings when it is on.
pub(super) fn plugins_view(schema: Schema) -> impl IntoView {
    let s = settings();
    schema
        .plugins
        .into_iter()
        .map(|plugin| {
            let base = ConfigPath::parse("plugins").key(plugin.id.clone());
            let id = form::field_element_id(&base.to_string());
            let name_id = format!("plugin-{}", plugin.id);
            let name = if javabyte_gui_protocol::i18n::has(&name_id) { tr(&name_id, None) } else { plugin.id.clone() };
            let enabled = {
                let base = base.clone();
                move || s.value(&base).is_some()
            };
            let toggle_path = base.clone();
            let settings_views = {
                let base = base.clone();
                let plugin = plugin.clone();
                let enabled = enabled.clone();
                move || {
                    enabled().then(|| {
                        plugin
                            .settings
                            .iter()
                            .map(|setting| {
                                let mut option = setting.clone();
                                option.path = format!("plugins.{}.{}", plugin.id, setting.path);
                                let schema = Schema { schema_version: 1, options: vec![], plugins: vec![] };
                                field_view(&schema, option, base.clone().key(setting.path.clone()))
                            })
                            .collect_view()
                    })
                }
            };
            view! {
                <div class="field plugin">
                    <div class="choice">
                        <input
                            type="checkbox"
                            id=id.clone()
                            aria-describedby=format!("{id}-help")
                            prop:checked=enabled
                            on:change=move |ev| set_value(&toggle_path, event_target_checked(&ev).then(|| json!({})))
                        />
                        <label for=id.clone()>{t!("plugin-use", name = name)}</label>
                    </div>
                    <p class="hint" id=format!("{id}-help")>{plugin.description.clone()}</p>
                    <div class="indented">{settings_views}</div>
                </div>
            }
        })
        .collect_view()
}

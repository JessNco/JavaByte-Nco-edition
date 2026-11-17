//! Settings: forms built from the tool's schema, and the same settings as YAML.
//!
//! Every field has a visible label, the tool's one-line description, its default, and its own
//! error message tied to it with `aria-describedby`. Lists of items are tables with buttons for
//! every action (no dragging). Problems found by the tool are listed at the top, each linking to
//! its field. See docs/gui/PHASE3-PLAN.md.

use crate::app::{ErrorBox, Screen, flow, report_error, show};
use crate::bridge;
use crate::settings::{self, check_now, edit, replace_text, set_value, settings};
use crate::util::{Disclosure, announcer, copied_message, copy_text, focus_id, open_disclosures_around};
use javabyte_gui_backend::config::{ConfigPath, Step};
use javabyte_gui_protocol::form::{self, Widget};
use javabyte_gui_protocol::i18n::tr;
use javabyte_gui_protocol::schema::{ConfigOption, Schema};
use javabyte_gui_protocol::{display_text, t};
use leptos::prelude::*;
use leptos::task::spawn_local;
use serde_json::{Value, json};
use wasm_bindgen::JsCast;

const TABS: [&str; 2] = ["forms", "yaml"];

#[component]
pub fn SettingsScreen() -> impl IntoView {
    let flow = flow();
    let s = settings();
    let error = RwSignal::new(None::<String>);
    let busy = RwSignal::new(false);
    let status = RwSignal::new(String::new());

    let on_tab_key = move |ev: web_sys::KeyboardEvent| {
        let current = s.tab.get_untracked();
        let next = match ev.key().as_str() {
            "ArrowRight" => (current + 1) % TABS.len(),
            "ArrowLeft" => (current + TABS.len() - 1) % TABS.len(),
            "Home" => 0,
            "End" => TABS.len() - 1,
            _ => return,
        };
        ev.prevent_default();
        s.tab.set(next);
        focus_id(format!("settings-tab-{}", TABS[next]));
    };

    let name = move || flow.sample.with(|s| s.as_ref().map(|s| display_text(&s.name, 120).text).unwrap_or_default());

    view! {
        <h2 id="settings-heading" tabindex="-1">{move || t!("settings-heading", name = name())}</h2>
        <p>{t!("settings-intro")}</p>
        <ErrorBox id="settings-error" message=Signal::derive(move || error.get()) />
        <SyntaxSummary />
        <ProblemSummary />
        <Presets />

        <div role="tablist" aria-label=t!("settings-tabs") class="tablist" on:keydown=on_tab_key>
            {TABS.iter().enumerate().map(|(index, stem)| view! {
                <button
                    type="button"
                    role="tab"
                    id=format!("settings-tab-{stem}")
                    aria-controls=format!("settings-panel-{stem}")
                    aria-selected=move || (s.tab.get() == index).to_string()
                    tabindex=move || if s.tab.get() == index { "0" } else { "-1" }
                    on:click=move |_| s.tab.set(index)
                >
                    {move || if index == 1 && !s.checked() && !s.text.with(String::is_empty) {
                        t!("settings-tab-yaml-changed")
                    } else {
                        tr(&format!("settings-tab-{stem}"), None)
                    }}
                </button>
            }).collect_view()}
        </div>
        <div role="tabpanel" id="settings-panel-forms" aria-labelledby="settings-tab-forms" hidden=move || s.tab.get() != 0>
            <Forms />
        </div>
        <div role="tabpanel" id="settings-panel-yaml" aria-labelledby="settings-tab-yaml" hidden=move || s.tab.get() != 1>
            <YamlEditor />
        </div>

        <div class="buttons action-bar">
            <button type="button" on:click=move |_| { spawn_local(async move { check_now().await; }); }>{t!("check-button")}</button>
            <button
                type="button"
                class="primary"
                disabled=move || busy.get()
                on:click=move |_| settings::analyse(busy, move |message| report_error(error, "settings-error", message))
            >
                {t!("settings-analyse")}
            </button>
            <button type="button" on:click=move |_| show(Screen::Overview)>{t!("settings-back")}</button>
        </div>
        <p class="inline-status" role="status">{move || {
            if s.checking.get() {
                t!("check-checking")
            } else if let Some(problem) = s.check_error.get() {
                problem
            } else if s.checked() {
                let count = s.problems().len();
                if count == 0 { t!("check-ok") } else { t!("check-problems", count = count) }
            } else {
                String::new()
            }
        }}</p>
        <Share status />
    }
}

/// The YAML cannot be read: where, with a link to the line.
#[component]
fn SyntaxSummary() -> impl IntoView {
    let s = settings();
    view! {
        <Show when=move || !s.syntax.with(Vec::is_empty)>
            <div class="error-summary" role="region" aria-labelledby="syntax-title">
                <h3 id="syntax-title" tabindex="-1">{t!("syntax-heading")}</h3>
                <ul>
                    {move || s.syntax.get().into_iter().take(5).map(|p| {
                        let line = p.line;
                        view! {
                            <li>
                                {t!("syntax-problem", line = p.line, column = p.column, message = p.message.clone())}" "
                                <a href="#settings-yaml" on:click=move |ev| { ev.prevent_default(); go_to_line(line); }>
                                    {t!("syntax-go", line = line)}
                                </a>
                            </li>
                        }
                    }).collect_view()}
                </ul>
                <p>{t!("syntax-forms-off")}</p>
            </div>
        </Show>
    }
}

/// Problems the tool found, each linking to its field.
#[component]
fn ProblemSummary() -> impl IntoView {
    let s = settings();
    view! {
        <Show when=move || !s.problems().is_empty()>
            <div class="error-summary" role="region" aria-labelledby="settings-problems-title">
                <h3 id="settings-problems-title" tabindex="-1">{move || t!("check-problems", count = s.problems().len())}</h3>
                <p>{t!("check-before-analyse")}</p>
                <ul>
                    {move || s.problems().into_iter().map(|p| {
                        let key = p.key.clone();
                        let shown = if p.key.is_empty() { p.message.clone() } else { format!("{}: {}", p.key, p.message) };
                        view! {
                            <li>
                                <a href=format!("#{}", form::field_element_id(&p.key)) class="untrusted" on:click=move |ev| {
                                    ev.prevent_default();
                                    go_to_problem(&key);
                                }>{display_text(&shown, 300).text}</a>
                            </li>
                        }
                    }).collect_view()}
                </ul>
            </div>
        </Show>
    }
}

/// Moves focus to the field a problem is about: opening its list item or its "More settings"
/// group if needed, or the YAML tab for problems no field shows.
fn go_to_problem(key: &str) {
    let s = settings();
    let path = ConfigPath::parse(key);
    // A field inside a list item: open that item's editor first.
    if let Some(at) = path.0.iter().position(|step| matches!(step, Step::Index(_))) {
        if let Step::Index(index) = path.0[at] {
            let list = ConfigPath(path.0[..at].to_vec()).to_string();
            s.tab.set(0);
            s.editing.set(Some((list, index)));
        }
    }
    let id = form::field_element_id(key);
    let key = key.to_string();
    request_animation_frame(move || {
        let element = document().get_element_by_id(&id);
        match element {
            Some(element) => {
                s.tab.set(0);
                open_disclosures_around(&element);
                focus_id(id);
            }
            None => {
                let line = line_of_key(&s.text.get_untracked(), &key);
                go_to_line(line);
            }
        }
    });
}

/// The line where the last part of a key (`netwrk` in `netwrk.mode`) is written, or 1.
fn line_of_key(text: &str, key: &str) -> usize {
    let first = key.split(['.', '[']).next().unwrap_or(key);
    text.lines().position(|l| l.trim_start().starts_with(&format!("{first}:"))).map_or(1, |i| i + 1)
}

/// Shows the YAML tab with the caret at the start of a line.
fn go_to_line(line: usize) {
    let s = settings();
    s.tab.set(1);
    let text = s.text.get_untracked();
    let offset: usize = text.split_inclusive('\n').take(line.saturating_sub(1)).map(|l| l.encode_utf16().count()).sum();
    request_animation_frame(move || {
        if let Some(area) = document().get_element_by_id("settings-yaml").and_then(|e| e.dyn_into::<web_sys::HtmlTextAreaElement>().ok()) {
            let _ = area.focus();
            let _ = area.set_selection_range(offset as u32, offset as u32);
        }
    });
}

#[component]
fn Forms() -> impl IntoView {
    let s = settings();
    view! {
        {move || {
            if !s.syntax.with(Vec::is_empty) {
                return view! { <p>{t!("syntax-forms-off")}</p> }.into_any();
            }
            let Some(schema) = s.schema.get() else { return view! { <p>{t!("settings-loading")}</p> }.into_any() };
            view! {
                <nav aria-label=t!("settings-jump") class="jump-list">
                    <ul>
                        {form::SECTIONS.iter().map(|(id, _)| view! {
                            <li><a href=format!("#section-{id}")>{tr(&format!("section-{id}"), None)}</a></li>
                        }).collect_view()}
                    </ul>
                </nav>
                {form::SECTIONS.iter().map(|(id, _)| section_view(&schema, id)).collect_view()}
            }
            .into_any()
        }}
    }
}

/// The fields directly in a section: top-level options and the fields of its groups, not the
/// fields of list items (those are in each item's editor).
fn section_options<'a>(schema: &'a Schema, section: &str) -> Vec<&'a ConfigOption> {
    schema
        .options
        .iter()
        .filter(|o| form::section_of(&o.path) == Some(section))
        .filter(|o| !o.path.contains("[]") && !o.path.contains(".*."))
        .filter(|o| form::widget(o) != Widget::Group)
        .collect()
}

fn section_view(schema: &Schema, section: &'static str) -> impl IntoView {
    let options = section_options(schema, section);
    let (common, more): (Vec<_>, Vec<_>) = options.into_iter().partition(|o| form::is_common(&o.path));
    let schema = schema.clone();
    let common_views = common.into_iter().map(|o| field_view(&schema, o.clone(), ConfigPath::parse(&o.path))).collect_view();
    let more_views = (!more.is_empty()).then(|| {
        let views = more.into_iter().map(|o| field_view(&schema, o.clone(), ConfigPath::parse(&o.path))).collect_view();
        view! {
            <Disclosure label=t!("settings-more") class="more">
                {views}
            </Disclosure>
        }
    });
    view! {
        <section class="settings-section" aria-labelledby=format!("section-{section}")>
            <h3 id=format!("section-{section}") tabindex="-1">{tr(&format!("section-{section}"), None)}</h3>
            {common_views}
            {more_views}
        </section>
    }
}

/// The text a value shows in a field.
fn shown(value: &Value) -> String {
    match value {
        Value::String(s) => s.clone(),
        Value::Null => String::new(),
        other => other.to_string(),
    }
}

/// A field's label: its message, or for a plugin the tool's own setting name.
fn label_for(schema_path: &str) -> String {
    let id = form::label_id(schema_path);
    if javabyte_gui_protocol::i18n::has(&id) { tr(&id, None) } else { schema_path.rsplit('.').next().unwrap_or(schema_path).to_string() }
}

/// One option's field at a concrete path.
fn field_view(schema: &Schema, option: ConfigOption, path: ConfigPath) -> AnyView {
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
fn pairs_view(id: String, label: String, help: AnyView, path: ConfigPath) -> impl IntoView {
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

/// A list of items: a table with buttons, and the item being edited below it.
fn items_view(schema: Schema, id: String, label: String, help: AnyView, path: ConfigPath, schema_path: String) -> impl IntoView {
    let s = settings();
    let list_text = path.to_string();
    let item_name = tr(&form::item_id(&schema_path), None);
    let columns: Vec<&'static str> = form::ITEM_COLUMNS.iter().find(|(l, _)| *l == schema_path).map(|(_, c)| c.to_vec()).unwrap_or_default();
    let item_options: Vec<ConfigOption> =
        schema.options.iter().filter(|o| o.path.strip_prefix(&format!("{schema_path}[].")).is_some_and(|rest| !rest.contains('.'))).cloned().collect();
    let items = {
        let path = path.clone();
        move || s.value(&path).and_then(|v| v.as_array().cloned()).unwrap_or_default()
    };
    let heading_id = format!("{id}-label");
    let slug = id.clone();

    let add = {
        let (path, item_options, item_name, list_text) = (path.clone(), item_options.clone(), item_name.clone(), list_text.clone());
        let first = columns.first().copied();
        move |_| {
            // A new item starts with its first field: its default, or empty for the person to fill.
            let mut item = serde_json::Map::new();
            if let Some(first) = first {
                let default = item_options.iter().find(|o| o.path.ends_with(&format!("[].{first}"))).and_then(|o| o.default.clone());
                item.insert(first.to_string(), default.unwrap_or_else(|| json!("")));
            }
            let count = items_len(&path);
            if let Err(message) = edit(|doc| doc.push(&path, &Value::Object(item))) {
                announcer().say(message);
                return;
            }
            s.editing.set(Some((list_text.clone(), count)));
            announcer().say(t!("items-added", item = item_name.clone(), number = count + 1));
            let first_field = first.map(|f| form::field_element_id(&format!("{list_text}[{count}].{f}")));
            request_animation_frame(move || {
                if let Some(id) = first_field {
                    focus_id(id);
                }
            });
        }
    };

    let table = {
        let (path, item_name, slug, list_text) = (path.clone(), item_name.clone(), slug.clone(), list_text.clone());
        let columns = columns.clone();
        move || {
            let items = items();
            if items.is_empty() {
                return view! { <p>{t!("items-empty")}</p> }.into_any();
            }
            let count = items.len();
            let (path, item_name, slug, list_text) = (path.clone(), item_name.clone(), slug.clone(), list_text.clone());
            let columns = columns.clone();
            view! {
                <div class="table-wrap">
                    <table class="simple-table compact">
                        <thead><tr>
                            <th scope="col">{t!("items-number")}</th>
                            {columns.iter().map(|c| view! { <th scope="col">{label_for(&format!("{schema_path}[].{c}"))}</th> }).collect_view()}
                            <th scope="col"><span class="visually-hidden">{t!("items-actions")}</span></th>
                        </tr></thead>
                        <tbody>
                            {items.into_iter().enumerate().map(|(index, item)| {
                                let number = index + 1;
                                let (path, item_name, slug, list_text) = (path.clone(), item_name.clone(), slug.clone(), list_text.clone());
                                let (p_up, p_down, p_remove) = (path.clone(), path.clone(), path.clone());
                                let (n_up, n_down, n_remove) = (item_name.clone(), item_name.clone(), item_name.clone());
                                let (s_up, s_down) = (slug.clone(), slug.clone());
                                let list_for_edit = list_text.clone();
                                let list_for_remove = list_text.clone();
                                view! {
                                    <tr>
                                        <th scope="row">{number}</th>
                                        {columns.iter().map(|c| view! {
                                            <td class="untrusted">{display_text(&item.get(*c).map(shown).unwrap_or_default(), 120).text}</td>
                                        }).collect_view()}
                                        <td class="row-actions">
                                            <button type="button" id=format!("{slug}-edit-{index}") aria-label=t!("items-edit-label", item = item_name.clone(), number = number)
                                                on:click=move |_| {
                                                    s.editing.set(Some((list_for_edit.clone(), index)));
                                                    focus_id(format!("{}-editor", form::field_element_id(&list_for_edit)));
                                                }>{t!("items-edit")}</button>
                                            <button type="button" id=format!("{slug}-up-{index}") disabled=index == 0 aria-label=t!("items-up-label", item = item_name.clone(), number = number)
                                                on:click=move |_| move_item(&p_up, index, index - 1, &n_up, &s_up)>{t!("items-up")}</button>
                                            <button type="button" id=format!("{slug}-down-{index}") disabled=index + 1 == count aria-label=t!("items-down-label", item = item_name.clone(), number = number)
                                                on:click=move |_| move_item(&p_down, index, index + 1, &n_down, &s_down)>{t!("items-down")}</button>
                                            <button type="button" aria-label=t!("items-remove-label", item = item_name.clone(), number = number)
                                                on:click=move |_| {
                                                    if s.editing.get_untracked().is_some_and(|(l, i)| l == list_for_remove && i == index) {
                                                        s.editing.set(None);
                                                    }
                                                    if let Err(message) = edit(|doc| doc.remove_item(&p_remove, index)) {
                                                        announcer().say(message);
                                                        return;
                                                    }
                                                    announcer().say(t!("items-removed", item = n_remove.clone(), number = number));
                                                    focus_id(format!("{}-add", form::field_element_id(&list_for_remove)));
                                                }>{t!("items-remove")}</button>
                                        </td>
                                    </tr>
                                }
                            }).collect_view()}
                        </tbody>
                    </table>
                </div>
            }
            .into_any()
        }
    };

    let editor = {
        let (path, item_name, list_text) = (path.clone(), item_name.clone(), list_text.clone());
        move || {
            let (list, index) = s.editing.get()?;
            if list != list_text {
                return None;
            }
            // Read without tracking: the editor is rebuilt only when another item is opened.
            // Rebuilding it on every change would take keyboard focus away from the field
            // being edited.
            let count = untrack(|| items_len(&path));
            if index >= count {
                return None;
            }
            let item_path = path.clone().index(index);
            let fields = item_options.iter().map(|o| field_view(&schema, o.clone(), item_path.clone().key(o.path.rsplit('.').next().unwrap_or_default()))).collect_view();
            let editor_id = format!("{}-editor", form::field_element_id(&list_text));
            let labelled_by = editor_id.clone();
            let done_target = format!("{}-edit-{index}", form::field_element_id(&list_text));
            Some(view! {
                <div class="item-editor" role="group" aria-labelledby=labelled_by>
                    <h4 id=editor_id tabindex="-1">{t!("items-editing", item = item_name.clone(), number = index + 1, count = count)}</h4>
                    {fields}
                    <button type="button" on:click=move |_| {
                        s.editing.set(None);
                        focus_id(done_target.clone());
                    }>{t!("items-done")}</button>
                </div>
            })
        }
    };

    let labelled_by = heading_id.clone();
    view! {
        <div class="field items" role="group" aria-labelledby=labelled_by id=id.clone() tabindex="-1">
            <p class="label" id=heading_id>{label}</p>
            {help}
            {table}
            <button type="button" id=format!("{slug}-add") on:click=add>{t!("items-add", item = item_name.clone())}</button>
            {editor}
        </div>
    }
}

/// A list of single values from text, one per line; empty lines are left out.
fn commit_lines(path: &ConfigPath, text: &str) {
    let lines: Vec<Value> = text.lines().map(str::trim).filter(|l| !l.is_empty()).map(|l| json!(l)).collect();
    set_value(path, (!lines.is_empty()).then_some(Value::Array(lines)));
}

fn items_len(path: &ConfigPath) -> usize {
    settings().value(path).and_then(|v| v.as_array().map(Vec::len)).unwrap_or(0)
}

fn move_item(path: &ConfigPath, from: usize, to: usize, item_name: &str, slug: &str) {
    if let Err(message) = edit(|doc| doc.move_item(path, from, to)) {
        announcer().say(message);
        return;
    }
    let s = settings();
    if let Some((list, index)) = s.editing.get_untracked() {
        if list == path.to_string() && index == from {
            s.editing.set(Some((list, to)));
        }
    }
    announcer().say(t!("items-moved", item = item_name.to_string(), number = to + 1));
    // Keep focus on the same action of the moved item, or the other one at the ends.
    let direction = if to < from { "up" } else { "down" };
    let at_end = (direction == "up" && to == 0) || (direction == "down" && to + 1 == items_len(path));
    let target = format!("{slug}-{}-{to}", if at_end { if direction == "up" { "down" } else { "up" } } else { direction });
    request_animation_frame(move || focus_id(target));
}

/// Plugins: a switch for each, and its settings when it is on.
fn plugins_view(schema: Schema) -> impl IntoView {
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

/// The YAML tab: a plain text area, with line numbers beside it that screen readers skip.
#[component]
fn YamlEditor() -> impl IntoView {
    let s = settings();
    let area = NodeRef::<leptos::html::Textarea>::new();
    let gutter = NodeRef::<leptos::html::Pre>::new();
    let pending = StoredValue::new(0u64);
    // Put the text into the area when it changes elsewhere (forms, presets); not while typing,
    // which would move the caret.
    Effect::new(move |_| {
        let text = s.text.get();
        if let Some(area) = area.get() {
            if area.value() != text {
                area.set_value(&text);
            }
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

/// Built-in and saved presets.
#[component]
fn Presets() -> impl IntoView {
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
fn literal_pattern(text: &str) -> String {
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
fn apply_builtin(id: &'static str, follow: &javabyte_gui_protocol::views::FollowUps) {
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

/// Import, export and the command line.
#[component]
fn Share(status: RwSignal<String>) -> impl IntoView {
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

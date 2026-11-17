//! Lists of items (web answers, files, hooks, ...): a table with buttons for every action,
//! and the item being edited below it. Nothing is reordered by dragging.

use crate::settings::{edit, settings};
use crate::util::{announcer, focus_id};
use javabyte_gui_backend::config::ConfigPath;
use javabyte_gui_protocol::form::{self};
use javabyte_gui_protocol::i18n::tr;
use javabyte_gui_protocol::schema::{ConfigOption, Schema};
use javabyte_gui_protocol::{display_text, t};
use leptos::prelude::*;
use serde_json::{Value, json};
use super::fields::{field_view, label_for, shown};

/// A list of items: a table with buttons, and the item being edited below it.
pub(super) fn items_view(schema: Schema, id: String, label: String, help: AnyView, path: ConfigPath, schema_path: String) -> impl IntoView {
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

/// Items in the list at `path`.
pub(super) fn items_len(path: &ConfigPath) -> usize {
    settings().value(path).and_then(|v| v.as_array().map(Vec::len)).unwrap_or(0)
}

/// Moves an item, keeps keyboard focus on the moved item's button, and announces it.
pub(super) fn move_item(path: &ConfigPath, from: usize, to: usize, item_name: &str, slug: &str) {
    if let Err(message) = edit(|doc| doc.move_item(path, from, to)) {
        announcer().say(message);
        return;
    }
    let s = settings();
    if let Some((list, index)) = s.editing.get_untracked()
        && list == path.to_string() && index == from {
            s.editing.set(Some((list, to)));
        }
    announcer().say(t!("items-moved", item = item_name.to_string(), number = to + 1));
    // Keep focus on the same action of the moved item, or the other one at the ends.
    let direction = if to < from { "up" } else { "down" };
    let at_end = (direction == "up" && to == 0) || (direction == "down" && to + 1 == items_len(path));
    let target = format!("{slug}-{}-{to}", if at_end { if direction == "up" { "down" } else { "up" } } else { direction });
    request_animation_frame(move || focus_id(target));
}

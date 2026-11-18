//! Settings: forms built from the tool's schema, and the same settings as YAML.
//!
//! Every field has a visible label, the tool's one-line description, its default, and its own
//! error message tied to it with `aria-describedby`. Lists of items are tables with buttons for
//! every action (no dragging). Problems found by the tool are listed at the top, each linking to
//! its field. See docs/gui/PHASE3-PLAN.md.

use crate::app::{ErrorBox, Screen, flow, report_error, show};
use crate::settings::{self, SettingsTab, check_now, settings};
use crate::util::{Disclosure, Tab, focus_id, open_disclosures_around, tab_list};
use javabyte_gui_backend::config::{ConfigPath, Step};
use javabyte_gui_protocol::form::{self, Widget};
use javabyte_gui_protocol::i18n::tr;
use javabyte_gui_protocol::schema::{ConfigOption, Schema};
use javabyte_gui_protocol::{display_text, t};
use leptos::prelude::*;
use leptos::task::spawn_local;
use wasm_bindgen::JsCast;

mod fields;
mod items;
mod presets;
mod share;
mod yaml;

use fields::field_view;
use presets::Presets;
use share::Share;
use yaml::YamlEditor;

/// The Settings screen.
#[component]
pub fn SettingsScreen() -> impl IntoView {
    let flow = flow();
    let s = settings();
    let error = RwSignal::new(None::<String>);
    let busy = RwSignal::new(false);

    let name = move || flow.sample.with(|s| s.as_ref().map(|s| display_text(&s.name, 120).text).unwrap_or_default());

    view! {
        <h2 id="settings-heading" tabindex="-1">{move || t!("settings-heading", name = name())}</h2>
        <p>{t!("settings-intro")}</p>
        <ErrorBox id="settings-error" message=Signal::derive(move || error.get()) />
        <SyntaxSummary />
        <ProblemSummary />
        <Presets />

        // The YAML tab says when its text has changed since the last check.
        {tab_list("settings", t!("settings-tabs"), s.tab, move |tab| {
            if tab == SettingsTab::Yaml && !s.checked() && !s.text.with(String::is_empty) {
                t!("settings-tab-yaml-changed")
            } else {
                tr(&format!("settings-tab-{}", tab.stem()), None)
            }
        })}
        <div role="tabpanel" id="settings-panel-forms" aria-labelledby="settings-tab-forms" hidden=move || s.tab.get() != SettingsTab::Forms>
            <Forms />
        </div>
        <div role="tabpanel" id="settings-panel-yaml" aria-labelledby="settings-tab-yaml" hidden=move || s.tab.get() != SettingsTab::Yaml>
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
        <Share />
    }
}

/// The YAML cannot be read: where, with a link to the line.
#[component]
pub(super) fn SyntaxSummary() -> impl IntoView {
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
pub(super) fn ProblemSummary() -> impl IntoView {
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
pub(super) fn go_to_problem(key: &str) {
    let s = settings();
    let path = ConfigPath::parse(key);
    // A field inside a list item: open that item's editor first.
    if let Some(at) = path.0.iter().position(|step| matches!(step, Step::Index(_)))
        && let Step::Index(index) = path.0[at] {
            let list = ConfigPath(path.0[..at].to_vec()).to_string();
            s.tab.set(SettingsTab::Forms);
            s.editing.set(Some((list, index)));
        }
    let id = form::field_element_id(key);
    let key = key.to_string();
    request_animation_frame(move || {
        let element = document().get_element_by_id(&id);
        match element {
            Some(element) => {
                s.tab.set(SettingsTab::Forms);
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

/// The line where the first (top-level) part of a key (`netwrk` in `netwrk.mode`) is written,
/// or 1.
pub(super) fn line_of_key(text: &str, key: &str) -> usize {
    let first = key.split(['.', '[']).next().unwrap_or(key);
    text.lines().position(|l| l.trim_start().starts_with(&format!("{first}:"))).map_or(1, |i| i + 1)
}

/// Shows the YAML tab with the caret at the start of a line.
pub(super) fn go_to_line(line: usize) {
    let s = settings();
    s.tab.set(SettingsTab::Yaml);
    let text = s.text.get_untracked();
    let offset: usize = text.split_inclusive('\n').take(line.saturating_sub(1)).map(|l| l.encode_utf16().count()).sum();
    request_animation_frame(move || {
        if let Some(area) = document().get_element_by_id("settings-yaml").and_then(|e| e.dyn_into::<web_sys::HtmlTextAreaElement>().ok()) {
            let _ = area.focus();
            let _ = area.set_selection_range(offset as u32, offset as u32);
        }
    });
}

/// The Forms tab: every section, or a message while the YAML cannot be read.
#[component]
pub(super) fn Forms() -> impl IntoView {
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
pub(super) fn section_options<'a>(schema: &'a Schema, section: &str) -> Vec<&'a ConfigOption> {
    schema
        .options
        .iter()
        .filter(|o| form::section_of(&o.path) == Some(section))
        .filter(|o| !o.path.contains("[]") && !o.path.contains(".*."))
        .filter(|o| form::widget(o) != Widget::Group)
        .collect()
}

/// One section: its common settings, then the rest under More settings.
pub(super) fn section_view(schema: &Schema, section: &'static str) -> impl IntoView {
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

//! Parity with the command line (docs/GUI-PLAN.md, "Command-line parity"): every option the tool
//! describes in `schema --json` has a field in the settings forms, with a label, a section and,
//! for choices, words for each value. The schema here is the tool's real output, kept in step
//! with the Java side by the drift check, so a new option fails this test until the forms
//! cover it.

use javabyte_gui_protocol::form::{self, Widget};
use javabyte_gui_protocol::i18n;
use javabyte_gui_protocol::schema::Schema;

fn schema() -> Schema {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/tool-output/schema.json");
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

#[test]
fn every_option_has_a_field_label_and_section() {
    let schema = schema();
    let mut missing = Vec::new();
    for option in &schema.options {
        let label = form::label_id(&option.path);
        if !i18n::has(&label) {
            missing.push(format!("{}: no label message {label}", option.path));
        }
        if form::section_of(&option.path).is_none() {
            missing.push(format!("{}: in no section of form::SECTIONS", option.path));
        }
        if let Widget::Radio(values) | Widget::Select(values) = form::widget(option) {
            for value in values {
                let id = form::choice_id(&option.path, &value);
                if !i18n::has(&id) {
                    missing.push(format!("{}: no words for the choice {value} ({id})", option.path));
                }
            }
        }
        if form::widget(option) == Widget::Items && !i18n::has(&form::item_id(&option.path)) {
            missing.push(format!("{}: no name for its items ({})", option.path, form::item_id(&option.path)));
        }
    }
    for id in form::BUNDLED_PLUGINS {
        assert!(schema.plugins.iter().any(|p| p.id == *id), "the bundled plugin {id} is not in the schema");
    }
    for plugin in schema.plugins.iter().filter(|p| form::BUNDLED_PLUGINS.contains(&p.id.as_str())) {
        if !i18n::has(&format!("plugin-{}", plugin.id)) {
            missing.push(format!("plugin {}: no name message plugin-{}", plugin.id, plugin.id));
        }
        for setting in &plugin.settings {
            let label = form::label_id(&format!("plugins.{}.{}", plugin.id, setting.path));
            if !i18n::has(&label) {
                missing.push(format!("plugin {} setting {}: no label {label}", plugin.id, setting.path));
            }
        }
    }
    assert!(missing.is_empty(), "the settings forms do not cover the command line:\n{}", missing.join("\n"));
}

#[test]
fn the_form_lists_name_only_real_options() {
    let schema = schema();
    let known = |path: &str| schema.option(path).is_some();
    for path in form::COMMON.iter().chain(form::HOST_FILES) {
        assert!(known(path), "{path} is listed in form.rs but is not an option");
    }
    for (list, columns) in form::ITEM_COLUMNS {
        assert_eq!(schema.option(list).map(form::widget), Some(Widget::Items), "{list} is not a list of items");
        for column in *columns {
            assert!(known(&format!("{list}[].{column}")), "{list}[].{column} is not an option");
        }
    }
    for option in schema.options.iter().filter(|o| form::widget(o) == Widget::Items) {
        assert!(form::ITEM_COLUMNS.iter().any(|(l, _)| *l == option.path), "{} has no table columns in ITEM_COLUMNS", option.path);
    }
    // Each top-level option is in exactly one section.
    for option in schema.options.iter().filter(|o| !o.path.contains(['.', '['])) {
        let sections = form::SECTIONS.iter().filter(|(_, keys)| keys.contains(&option.path.as_str())).count();
        assert_eq!(sections, 1, "{} is in {sections} sections", option.path);
    }
}

#[test]
fn field_ids_are_safe_and_distinct() {
    assert_eq!(form::field_element_id("network.http[2].url"), "field-network-http-2-url");
    assert_eq!(form::field_element_id("environment.properties.java.version"), "field-environment-properties-java-version");
    assert_eq!(form::label_id("network.http[].url"), "opt-network-http-url");
}

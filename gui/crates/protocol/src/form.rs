//! How the settings forms are laid out: sections, which settings show at first, and the kind of
//! field for each option. The options themselves come from `javabyte schema --json`; this file
//! only arranges them. A test (tests/form_parity.rs) fails if an option in the schema has no
//! field, label or section here, so the app cannot quietly fall behind the command line.

use crate::schema::ConfigOption;

/// Sections in the order the form shows them, each with the top-level options it holds. The
/// order follows docs/CONFIG.md.
pub const SECTIONS: &[(&str, &[&str])] = &[
    ("start", &["sample", "entryPoints", "classpath"]),
    ("limits", &["limits"]),
    ("environment", &["environment"]),
    ("network", &["network"]),
    ("processes", &["processes"]),
    ("policy", &["policy"]),
    ("hooks", &["hooks"]),
    ("trace", &["trace"]),
    ("threads", &["threads"]),
    ("output", &["output"]),
    ("plugins", &["plugins"]),
];

/// Settings shown when a section opens. Everything else is under the section's closed
/// **More settings** group, still reachable by keyboard and screen readers once opened.
pub const COMMON: &[&str] = &[
    "sample",
    "entryPoints",
    "classpath",
    "limits.timeoutSeconds",
    "limits.maxSleeps",
    "environment.os",
    "environment.userName",
    "environment.samplePath",
    "environment.files",
    "network.mode",
    "network.http",
    "processes",
    "policy.onBlocked",
    "hooks",
    "trace.strings",
    "output.dumpDroppedFiles",
    "output.dumpDefinedClasses",
    "plugins",
];

/// Plugins the app ships with (docs/GUI-PLAN.md: the native bridge is bundled). They have names
/// and setting labels in the resource files; any other plugin the tool reports is shown with
/// its id and the tool's own description.
pub const BUNDLED_PLUGINS: &[&str] = &["native-bridge"];

/// Fields holding a path to a real file on this computer: they get a **Choose…** button.
pub const HOST_FILES: &[&str] = &["environment.files[].hostFile", "network.http[].bodyFile", "network.sockets[].file"];

/// For each list of items, the fields shown as columns in its table (the rest are seen when an
/// item is edited).
pub const ITEM_COLUMNS: &[(&str, &[&str])] = &[
    ("entryPoints", &["type", "class", "method"]),
    ("environment.files", &["path"]),
    ("network.http", &["url", "status"]),
    ("network.sockets", &["endpoint"]),
    ("processes", &["command", "exitCode"]),
    ("hooks", &["method", "action", "value"]),
];

/// The kind of field an option gets.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Widget {
    /// Shown, not edited here: the sample is chosen on the Start screen.
    ReadOnly,
    Text,
    /// A whole number.
    Number,
    Check,
    /// A choice among few values: radio buttons.
    Radio(Vec<String>),
    /// A choice among more values: a list box.
    Select(Vec<String>),
    /// A list of single values, one per line.
    Lines,
    /// Names and values (a map of text).
    Pairs,
    /// A list of groups of settings, as a table of items.
    Items,
    /// A value of any type, written as YAML (`true`, `42`, `text`).
    Value,
    /// The plugins section, built from the schema's plugin list.
    Plugins,
    /// A group heading (an object whose options are fields of their own).
    Group,
}

/// Choices with this many values or fewer are radio buttons.
const RADIO_LIMIT: usize = 4;

pub fn widget(option: &ConfigOption) -> Widget {
    if option.path == "sample" {
        return Widget::ReadOnly;
    }
    if option.path == "plugins" {
        return Widget::Plugins;
    }
    match (option.kind.as_str(), option.element.as_deref()) {
        ("string", _) if !option.allowed.is_empty() => {
            if option.allowed.len() <= RADIO_LIMIT {
                Widget::Radio(option.allowed.clone())
            } else {
                Widget::Select(option.allowed.clone())
            }
        }
        ("string", _) => Widget::Text,
        ("integer", _) => Widget::Number,
        ("boolean", _) => Widget::Check,
        ("any", _) => Widget::Value,
        ("list", Some("object")) => Widget::Items,
        ("list", _) => Widget::Lines,
        ("map", Some("string")) => Widget::Pairs,
        ("object", _) => Widget::Group,
        _ => Widget::Value,
    }
}

/// The section a schema path belongs to.
pub fn section_of(path: &str) -> Option<&'static str> {
    let top = path.split(['.', '[']).next()?;
    SECTIONS.iter().find(|(_, keys)| keys.contains(&top)).map(|(id, _)| *id)
}

/// Whether a schema path shows when its section opens. Fields of list items always show in
/// the item's editor.
pub fn is_common(path: &str) -> bool {
    path.contains("[]") || COMMON.contains(&path)
}

/// The message naming an option: `network.http[].url` gives `opt-network-http-url`.
pub fn label_id(path: &str) -> String {
    format!("opt-{}", path.replace("[]", "").replace('.', "-"))
}

/// The message naming one value of a choice: `choice-network-mode-offline`.
pub fn choice_id(path: &str, value: &str) -> String {
    format!("choice-{}-{}", path.replace("[]", "").replace('.', "-"), value)
}

/// The message naming one item of a list, as in "HTTP answer 2 of 3": `item-network-http`.
pub fn item_id(path: &str) -> String {
    format!("item-{}", path.replace("[]", "").replace('.', "-"))
}

/// An id for the HTML element of a field at a concrete path: `network.http[2].url` gives
/// `field-network-http-2-url`. Error links use it to move focus to the field.
pub fn field_element_id(path: &str) -> String {
    let mut out = String::from("field-");
    for c in path.chars() {
        match c {
            '.' | '[' => out.push('-'),
            ']' => {}
            c if c.is_ascii_alphanumeric() || c == '-' || c == '_' => out.push(c),
            _ => out.push('_'),
        }
    }
    out
}

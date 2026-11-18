//! The Tools screen's data: the host policy (`javabyte policy --json`) and the options with their
//! defaults (from `javabyte schema --json`), and the search both views share.

use crate::schema::{ConfigOption, Schema};
use serde::{Deserialize, Serialize};

/// `javabyte policy --json`: the built-in list of JDK members guest code may call for real.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct PolicyListing {
    /// Every rule, in the order of the policy file.
    pub rules: Vec<PolicyRule>,
}

/// One rule of the host policy.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct PolicyRule {
    /// The rule as written, such as `java/lang/String` or `java/util/Formatter#<init>`.
    pub rule: String,
    /// True when it allows; false when it blocks (a block always wins).
    pub allow: bool,
    /// The policy file's section heading, such as `core language types`.
    pub section: String,
    /// The policy file's explanation of the rule's group; empty when there is none.
    pub note: String,
}

/// One row of the Defaults view: an option, its default and what it means.
#[derive(Clone, Debug, PartialEq)]
pub struct DefaultRow {
    /// The option's path, such as `limits.timeoutSeconds`, or `plugins.<id>.<setting>`.
    pub path: String,
    /// The default as YAML would show it; empty when the option has none.
    pub default: String,
    /// The option's type, such as `integer` or `list of objects`.
    pub kind: String,
    /// What the option does, in the tool's words.
    pub meaning: String,
}

/// Every option of the schema, plugin settings included, as rows of the Defaults view.
pub fn default_rows(schema: &Schema) -> Vec<DefaultRow> {
    let row = |path: String, option: &ConfigOption| DefaultRow {
        path,
        default: option.default.as_ref().map(show_value).unwrap_or_default(),
        kind: match &option.element {
            Some(element) => format!("{} of {element}", option.kind), // not-ui-text: a type name, as the tool writes it
            None => option.kind.clone(),
        },
        meaning: option.description.clone(),
    };
    let mut rows: Vec<DefaultRow> = schema.options.iter().map(|o| row(o.path.clone(), o)).collect();
    for plugin in &schema.plugins {
        rows.extend(plugin.settings.iter().map(|s| row(format!("plugins.{}.{}", plugin.id, s.path), s)));
    }
    rows
}

/// A default as people read it in YAML: text without quotes, lists and maps as JSON.
fn show_value(value: &serde_json::Value) -> String {
    match value {
        serde_json::Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

/// Whether every word of `query` appears, ignoring case, in one of `fields`. An empty query
/// matches everything. Words, not the whole query, so "thread start" finds a rule whose note
/// says "starts threads".
pub fn matches(query: &str, fields: &[&str]) -> bool {
    let haystack = fields.join("\n").to_lowercase();
    query.split_whitespace().all(|word| haystack.contains(&word.to_lowercase()))
}

impl PolicyRule {
    /// Whether the rule matches a search, by its rule, section or note.
    pub fn matches(&self, query: &str) -> bool {
        matches(query, &[&self.rule, &self.section, &self.note])
    }
}

impl DefaultRow {
    /// Whether the row matches a search, by its path, default, type or meaning.
    pub fn matches(&self, query: &str) -> bool {
        matches(query, &[&self.path, &self.default, &self.kind, &self.meaning])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn search_needs_every_word_and_ignores_case() {
        let rule = PolicyRule {
            rule: "java/util/Timer".into(),
            allow: false,
            section: "collections and utilities".into(),
            note: "Exceptions to the java/util tree: anything that starts threads".into(),
        };
        assert!(rule.matches(""));
        assert!(rule.matches("TIMER"));
        assert!(rule.matches("thread util"));
        assert!(!rule.matches("timer socket"));
    }
}

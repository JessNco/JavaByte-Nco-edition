//! `javabyte schema --json`: every config option. The settings forms are built from this.

use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Schema {
    pub schema_version: u32,
    pub options: Vec<ConfigOption>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct ConfigOption {
    /// Dots for nesting and `[]` for list items, as in `network.http[].url`.
    pub path: String,
    /// `string`, `integer`, `boolean`, `object`, `list`, `map` or `any`.
    #[serde(rename = "type")]
    pub kind: String,
    /// For lists and maps: the type of their items.
    #[serde(default)]
    pub element: Option<String>,
    #[serde(default)]
    pub default: Option<serde_json::Value>,
    /// For options that are a choice: every accepted value.
    #[serde(default)]
    pub allowed: Vec<String>,
    pub description: String,
}

impl Schema {
    pub fn option(&self, path: &str) -> Option<&ConfigOption> {
        self.options.iter().find(|o| o.path == path)
    }
}

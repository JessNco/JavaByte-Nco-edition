//! `javabyte schema --json`: every config option. The settings forms are built from this.

use serde::{Deserialize, Serialize};

/// Every config option and plugin setting the tool has.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Schema {
    /// The schema layout's version.
    pub schema_version: u32,
    /// Every option, parents before their children.
    pub options: Vec<ConfigOption>,
    /// Plugins the tool has, with their settings (paths relative to `plugins.<id>`).
    #[serde(default)]
    pub plugins: Vec<PluginSchema>,
}

/// A plugin the tool has, with its settings.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PluginSchema {
    /// The id used under `plugins` in the config.
    pub id: String,
    /// What the plugin does, in a sentence.
    pub description: String,
    /// Its settings; paths are relative to `plugins.<id>`.
    #[serde(default)]
    pub settings: Vec<ConfigOption>,
}

/// One config option.
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
    /// The default value, when it has one.
    #[serde(default)]
    pub default: Option<serde_json::Value>,
    /// For options that are a choice: every accepted value.
    #[serde(default)]
    pub allowed: Vec<String>,
    /// What the option does, in a sentence, from the tool's `@ConfigDoc`.
    pub description: String,
}

impl Schema {
    /// The option at a schema path such as `network.http[].url`.
    pub fn option(&self, path: &str) -> Option<&ConfigOption> {
        self.options.iter().find(|o| o.path == path)
    }
}

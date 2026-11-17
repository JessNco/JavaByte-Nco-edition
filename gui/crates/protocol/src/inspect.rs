//! `javabyte inspect --json`: a description of a sample made without running it.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Inspection {
    pub path: String,
    pub size: u64,
    pub class_count: u64,
    #[serde(default)]
    pub main_class: Option<String>,
    /// Set for Java agents, which start from `premain` instead of `main`.
    #[serde(default)]
    pub agent_class: Option<String>,
    #[serde(default)]
    pub main_candidates: Vec<String>,
    #[serde(default)]
    pub class_loaders: Vec<String>,
    pub native_method_count: u64,
    #[serde(default)]
    pub native_methods: Vec<String>,
    #[serde(default)]
    pub native_libraries: BTreeMap<String, NativeLibrary>,
    /// Calls hidden behind the sample's own `invokedynamic` bootstrap, a sign of obfuscation.
    pub obfuscated_call_sites: u64,
    #[serde(default)]
    pub largest_resources: BTreeMap<String, u64>,
    #[serde(default)]
    pub unparseable_classes: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeLibrary {
    pub format: String,
    #[serde(default)]
    pub architecture: Option<String>,
    pub export_count: u64,
    pub jni_export_count: u64,
    pub has_jni_on_load: bool,
    #[serde(default)]
    pub jni_exports: Vec<String>,
}

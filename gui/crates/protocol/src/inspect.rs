//! `javabyte inspect --json`: a description of a sample made without running it.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// What `inspect` found, without running anything.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Inspection {
    /// The path the tool was given.
    pub path: String,
    /// Size in bytes.
    pub size: u64,
    /// Classes in the sample.
    pub class_count: u64,
    /// The manifest's `Main-Class`, with dots, when there is one.
    #[serde(default)]
    pub main_class: Option<String>,
    /// Set for Java agents, which start from `premain` instead of `main`.
    #[serde(default)]
    pub agent_class: Option<String>,
    /// Classes with a `main` method, with slashes.
    #[serde(default)]
    pub main_candidates: Vec<String>,
    /// Classes that define class loaders: a common way to unpack hidden stages.
    #[serde(default)]
    pub class_loaders: Vec<String>,
    /// Methods declared `native`.
    pub native_method_count: u64,
    /// Those methods, as `class.method(descriptor)`.
    #[serde(default)]
    pub native_methods: Vec<String>,
    /// Native libraries bundled in the sample, by name.
    #[serde(default)]
    pub native_libraries: BTreeMap<String, NativeLibrary>,
    /// Calls hidden behind the sample's own `invokedynamic` bootstrap, a sign of obfuscation.
    pub obfuscated_call_sites: u64,
    /// The largest resource files, by name, with their sizes in bytes.
    #[serde(default)]
    pub largest_resources: BTreeMap<String, u64>,
    /// Entries that look like classes but could not be read.
    #[serde(default)]
    pub unparseable_classes: Vec<String>,
}

/// A native library bundled in the sample.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeLibrary {
    /// `PE`, `ELF`, `Mach-O`, `MZ (not PE)` or `malformed`.
    pub format: String,
    /// Such as `x86-64`, when the format says.
    #[serde(default)]
    pub architecture: Option<String>,
    /// Functions the library exports.
    pub export_count: u64,
    /// Exports that Java can call (`Java_...`).
    pub jni_export_count: u64,
    /// Whether it has `JNI_OnLoad`, which runs when the library is loaded.
    pub has_jni_on_load: bool,
    /// The `Java_...` exports, by name.
    #[serde(default)]
    pub jni_exports: Vec<String>,
}

//! The chosen sample's settings: reading, checking and changing them, presets, import and
//! export, and the same analysis as a command line.

use super::{Session, sample_override};
use crate::config::{self, ConfigDoc, ConfigPath, Shell};
use crate::runner::Launcher;
use javabyte_gui_protocol::schema::Schema;
use javabyte_gui_protocol::t;
use javabyte_gui_protocol::validate::Validation;
use javabyte_gui_protocol::views::{Imported, Preset, SampleChoice};
use std::path::{Path, PathBuf};

/// Largest config file that may be imported. Configs are small; this stops a wrong pick (a
/// sample, a disk image) from being read into memory.
const IMPORT_LIMIT: u64 = 1 << 20;

impl Session {
    /// The settings for the next run, as YAML.
    pub fn settings_text(&self) -> Option<String> {
        self.settings.as_ref().map(|(_, doc)| doc.text().to_string())
    }

    /// Replaces the settings, as the settings screen sends them after each change.
    pub fn set_settings_text(&mut self, text: String) {
        if let Some((_, doc)) = self.settings.as_mut() {
            doc.set_text(text);
        }
    }

    /// Every option the tool has, for the forms. Asked once, then kept.
    pub fn schema(&mut self) -> Result<Schema, String> {
        if self.schema.is_none() {
            self.schema = Some(self.launcher()?.schema()?);
        }
        Ok(self.schema.clone().expect("just set"))
    }

    /// Every problem in some settings, as the tool sees them. The sample is the chosen one.
    pub fn check(&self, text: &str) -> Result<Validation, String> {
        self.checker()?.check(text)
    }

    /// What a check needs, so that the tool can be run without holding the session.
    pub fn checker(&self) -> Result<Checker, String> {
        Ok(Checker {
            launcher: self.launcher()?.clone(),
            folder: self.data_dir(),
            sample: self.settings.as_ref().map(|(s, _)| s.clone()),
        })
    }

    /// The same analysis as a command, so people can repeat or share it outside the app.
    pub fn command_line(&self) -> String {
        let Some((sample, doc)) = self.settings.as_ref() else { return String::new() };
        let json = doc.to_json().unwrap_or_default();
        config::command_line(&sample.path, &json, Shell::native())
    }

    /// Where saved presets are kept, for showing to people.
    pub fn presets_folder(&self) -> PathBuf {
        self.data_dir().join("presets")
    }

    /// Saved presets, by name.
    pub fn presets(&self) -> Vec<Preset> {
        let mut names: Vec<String> = std::fs::read_dir(self.presets_folder())
            .into_iter()
            .flatten()
            .flatten()
            .filter_map(|e| {
                let path = e.path();
                let stem = path.file_stem()?.to_string_lossy().into_owned();
                path.extension().is_some_and(|x| x == "yaml").then_some(stem)
            })
            .collect();
        names.sort_by_key(|n| n.to_lowercase());
        names.into_iter().map(|name| Preset { name }).collect()
    }

    /// Saves settings as a preset, without the sample. The name becomes the file name.
    pub fn save_preset(&self, name: &str, text: &str) -> Result<Preset, String> {
        let name = preset_name(name).ok_or_else(|| t!("error-preset-name"))?;
        let mut doc = ConfigDoc::new(text);
        doc.remove(&ConfigPath::parse("sample")).map_err(|_| t!("error-preset-unreadable"))?;
        std::fs::create_dir_all(self.presets_folder()).map_err(|e| t!("error-preset-save", detail = e.to_string()))?;
        std::fs::write(self.presets_folder().join(format!("{name}.yaml")), doc.text()).map_err(|e| t!("error-preset-save", detail = e.to_string()))?;
        Ok(Preset { name })
    }

    /// A preset's settings, for the chosen sample.
    pub fn load_preset(&self, name: &str) -> Result<String, String> {
        let name = preset_name(name).ok_or_else(|| t!("error-preset-name"))?;
        let text = std::fs::read_to_string(self.presets_folder().join(format!("{name}.yaml"))).map_err(|e| t!("error-preset-read", detail = e.to_string()))?;
        Ok(with_sample(&text, self.settings.as_ref().map(|(s, _)| s)))
    }

    /// Deletes a saved preset.
    pub fn delete_preset(&self, name: &str) -> Result<(), String> {
        let name = preset_name(name).ok_or_else(|| t!("error-preset-name"))?;
        std::fs::remove_file(self.presets_folder().join(format!("{name}.yaml"))).map_err(|e| t!("error-preset-delete", detail = e.to_string()))
    }

    /// Reads a config file someone chose: YAML as it is, JSON turned into YAML.
    pub fn import(path: &Path) -> Result<Imported, String> {
        let size = std::fs::metadata(path).map_err(|e| t!("error-import", detail = e.to_string()))?.len();
        if size > IMPORT_LIMIT {
            return Err(t!("error-import-too-big"));
        }
        let raw = std::fs::read_to_string(path).map_err(|e| t!("error-import", detail = e.to_string()))?;
        let text = match serde_json::from_str::<serde_json::Value>(&raw) {
            Ok(value @ serde_json::Value::Object(_)) => config::render_document(&value),
            _ => raw,
        };
        // A file that cannot be read as YAML is still offered: the YAML tab shows where.
        let doc = ConfigDoc::new(text.clone());
        let sample = doc.get(&ConfigPath::parse("sample")).and_then(|v| v.as_str().map(str::to_string));
        Ok(Imported { text, sample })
    }

    /// Writes the settings to a file someone chose.
    pub fn export(path: &Path, text: &str) -> Result<(), String> {
        std::fs::write(path, text).map_err(|e| t!("error-export", detail = e.to_string()))
    }
}

/// Checks settings with the tool. Taken from the session by [`Session::checker`].
pub struct Checker {
    launcher: Launcher,
    folder: PathBuf,
    sample: Option<SampleChoice>,
}

impl Checker {
    /// Checks settings with the tool. The sample is the chosen one.
    pub fn check(&self, text: &str) -> Result<Validation, String> {
        std::fs::create_dir_all(&self.folder).map_err(|e| t!("error-results-folder", detail = e.to_string()))?;
        // One file per check, so two checks at once cannot read each other's settings.
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let n = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let file = self.folder.join(format!("check-{}-{n}.yaml", std::process::id()));
        std::fs::write(&file, text).map_err(|e| t!("error-results-folder", detail = e.to_string()))?;
        let overrides: Vec<String> = self.sample.iter().map(sample_override).collect();
        let result = self.launcher.validate(Some(&file), &overrides);
        let _ = std::fs::remove_file(&file);
        result
    }
}

/// Settings text with its `sample` set to the chosen one (or left out if none is chosen).
pub fn with_sample(text: &str, sample: Option<&SampleChoice>) -> String {
    let mut doc = ConfigDoc::new(text);
    let result = match sample {
        Some(sample) => doc.set(&ConfigPath::parse("sample"), &serde_json::Value::String(sample.path.clone())),
        None => doc.remove(&ConfigPath::parse("sample")),
    };
    if result.is_ok() { doc.text().to_string() } else { text.to_string() }
}

/// A preset name that is also a safe file name: letters, digits, spaces, dash, underscore,
/// up to 60 characters. `None` if nothing is left.
pub(super) fn preset_name(name: &str) -> Option<String> {
    let cleaned: String = name.trim().chars().filter(|c| c.is_alphanumeric() || " -_".contains(*c)).take(60).collect();
    let cleaned = cleaned.trim().to_string();
    (!cleaned.is_empty()).then_some(cleaned)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn settings_take_the_chosen_sample() {
        let sample = SampleChoice { path: "C:\\samples\\b.jar".into(), name: "b.jar".into() };
        let text = "# from another sample\nsample: /old/a.jar   # was this\nlimits:\n  maxSleeps: 3\n";
        let moved = with_sample(text, Some(&sample));
        assert!(moved.contains("sample: 'C:\\samples\\b.jar'   # was this"), "{moved}");
        assert!(moved.contains("# from another sample"));
        assert!(!with_sample(text, None).contains("sample:"));
        assert_eq!(preset_name("  My preset: v2! "), Some("My preset v2".into()));
        assert_eq!(preset_name("../.."), None);
    }
}

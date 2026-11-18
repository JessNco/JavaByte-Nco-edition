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

/// Settings that name a program on this computer for the tool to start, by plugin. The
/// `native-bridge` plugin starts its Python with `plugins.native-bridge.python`; without it,
/// the plugin uses the Python the app bundles (`JAVABYTE_BRIDGE_PYTHON`).
const HOST_PROGRAMS: &[(&str, &str)] = &[("plugins.native-bridge", "python")];

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

    /// The built-in host policy, for the Tools screen.
    pub fn policy(&self) -> Result<javabyte_gui_protocol::tools::PolicyListing, String> {
        self.launcher()?.policy()
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

    /// A preset's settings, for the chosen sample. A preset is a file anyone could have put
    /// in the presets folder, so settings that name a program to start are left out.
    pub fn load_preset(&self, name: &str) -> Result<String, String> {
        let name = preset_name(name).ok_or_else(|| t!("error-preset-name"))?;
        let text = std::fs::read_to_string(self.presets_folder().join(format!("{name}.yaml"))).map_err(|e| t!("error-preset-read", detail = e.to_string()))?;
        Ok(without_host_programs(&with_sample(&text, self.settings.as_ref().map(|(s, _)| s))))
    }

    /// Deletes a saved preset.
    pub fn delete_preset(&self, name: &str) -> Result<(), String> {
        let name = preset_name(name).ok_or_else(|| t!("error-preset-name"))?;
        std::fs::remove_file(self.presets_folder().join(format!("{name}.yaml"))).map_err(|e| t!("error-preset-delete", detail = e.to_string()))
    }

    /// Reads a config file someone chose: YAML as it is, JSON turned into YAML. The file may
    /// come from anyone, so settings that name a program to start are left out
    /// ([`without_host_programs`]).
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
        let text = without_host_programs(&text);
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

/// Settings text without the settings that name a program on this computer to start
/// ([`HOST_PROGRAMS`]), for settings that come from a file the app did not write: a report
/// folder, an imported file or a preset. Such a file could otherwise make a run start any
/// program, or on Windows reach another computer through a `\\host\share` path. The plugin
/// itself stays on, and uses the Python the app bundles. Text that cannot be read as YAML is
/// returned as it is: the tool refuses it too.
pub fn without_host_programs(text: &str) -> String {
    let mut doc = ConfigDoc::new(text);
    for (plugin, setting) in HOST_PROGRAMS {
        let plugin = ConfigPath::parse(plugin);
        let program = plugin.clone().key(*setting);
        if doc.get(&program).is_none() {
            continue;
        }
        let _ = doc.remove(&program);
        // Removing the last setting removes the plugin's entry, which would turn it off.
        if doc.get(&plugin).is_none() {
            let _ = doc.set(&plugin, &serde_json::Value::Object(serde_json::Map::new()));
        }
    }
    doc.text().to_string()
}

/// The program on this computer that settings tell the tool to start ([`HOST_PROGRAMS`]), as
/// the settings name it, or `None` if they name none. A value that is not text is given as the
/// settings spell it, so the person still sees what is there. Settings that cannot be read
/// give `None`: the session refuses to start a run with them at all.
pub fn host_program(text: &str) -> Option<String> {
    let doc = ConfigDoc::new(text);
    HOST_PROGRAMS.iter().find_map(|(plugin, setting)| {
        let value = doc.get(&ConfigPath::parse(plugin).key(*setting))?;
        Some(value.as_str().map_or_else(|| value.to_string(), str::to_string))
    })
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

    /// The Python the native bridge is told to start is found wherever the YAML puts it.
    #[test]
    fn the_program_settings_name_is_found() {
        assert_eq!(host_program("limits:\n  maxSleeps: 3\n"), None);
        assert_eq!(host_program("plugins:\n  native-bridge: {}\n"), None);
        assert_eq!(host_program("plugins:\n  native-bridge:\n    python: /opt/py/bin/python3\n"), Some("/opt/py/bin/python3".into()));
        assert_eq!(host_program("plugins: {native-bridge: {python: 'C:\\Py\\python.exe'}}\n"), Some("C:\\Py\\python.exe".into()));
        assert_eq!(host_program("plugins:\n  native-bridge:\n    python: 3\n"), Some("3".into()));
        // Taken out, nothing is left to ask about.
        assert_eq!(host_program(&without_host_programs("plugins:\n  native-bridge:\n    python: /x\n")), None);
    }

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

    /// Settings from a file the app did not write may not name a program for a run to start;
    /// the plugin stays on and the rest is kept as it was.
    #[test]
    fn settings_from_files_name_no_program_to_start() {
        let crafted = "limits:\n  maxSleeps: 3\nplugins:\n  native-bridge:\n    python: '\\\\host\\share\\x.exe'   # sent to you\n";
        let cleaned = without_host_programs(crafted);
        assert!(!cleaned.contains("python") && !cleaned.contains("host"), "{cleaned}");
        let doc = ConfigDoc::new(cleaned.clone());
        assert_eq!(doc.get(&ConfigPath::parse("plugins.native-bridge")), Some(serde_json::json!({})), "{cleaned}");
        assert_eq!(doc.get(&ConfigPath::parse("limits.maxSleeps")), Some(serde_json::json!(3)));

        let with_others = "plugins:\n  native-bridge:\n    python: /usr/bin/evil\n    other: 1\n";
        let cleaned = without_host_programs(with_others);
        assert_eq!(ConfigDoc::new(cleaned.clone()).get(&ConfigPath::parse("plugins.native-bridge")), Some(serde_json::json!({"other": 1})), "{cleaned}");

        // Nothing to remove: the text is untouched, comments and all.
        let plain = "# mine\nlimits:\n  maxSleeps: 3   # three\n";
        assert_eq!(without_host_programs(plain), plain);
        assert_eq!(without_host_programs("not: [valid"), "not: [valid");
    }

    /// An imported settings file loses the program setting too.
    #[test]
    fn imported_settings_name_no_program_to_start() {
        let dir = std::env::temp_dir().join(format!("javabyte-import-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let file = dir.join("crafted.json");
        std::fs::write(&file, r#"{"sample": "/s/x.jar", "plugins": {"native-bridge": {"python": "/tmp/evil"}}}"#).unwrap();
        let imported = Session::import(&file).unwrap();
        assert!(!imported.text.contains("evil"), "{}", imported.text);
        assert!(imported.text.contains("native-bridge"), "{}", imported.text);
        assert_eq!(imported.sample.as_deref(), Some("/s/x.jar"));
        let _ = std::fs::remove_dir_all(dir);
    }
}

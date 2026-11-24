//! The chosen sample's settings: reading, checking and changing them, presets, import and
//! export, and the same analysis as a command line.

use super::Session;
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

    /// Every problem in some settings, as the tool sees them.
    pub fn check(&self, text: &str) -> Result<Validation, String> {
        self.checker()?.check(text)
    }

    /// What a check needs, so that the tool can be run without holding the session.
    pub fn checker(&self) -> Result<Checker, String> {
        Ok(Checker {
            launcher: self.launcher()?.clone(),
            folder: self.data_dir(),
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
    /// in the presets folder, so settings that reach this computer are left out
    /// ([`without_host_access`]).
    pub fn load_preset(&self, name: &str) -> Result<String, String> {
        let name = preset_name(name).ok_or_else(|| t!("error-preset-name"))?;
        let text = std::fs::read_to_string(self.presets_folder().join(format!("{name}.yaml"))).map_err(|e| t!("error-preset-read", detail = e.to_string()))?;
        Ok(without_host_access(&with_sample(&text, self.settings.as_ref().map(|(s, _)| s))))
    }

    /// Deletes a saved preset.
    pub fn delete_preset(&self, name: &str) -> Result<(), String> {
        let name = preset_name(name).ok_or_else(|| t!("error-preset-name"))?;
        std::fs::remove_file(self.presets_folder().join(format!("{name}.yaml"))).map_err(|e| t!("error-preset-delete", detail = e.to_string()))
    }

    /// Reads a config file someone chose: YAML as it is, JSON turned into YAML. The file may
    /// come from anyone, so settings that reach this computer are left out
    /// ([`without_host_access`]).
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
        let text = without_host_access(&text);
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
}

impl Checker {
    /// Checks settings with the tool. Checking never looks at the sample itself.
    pub fn check(&self, text: &str) -> Result<Validation, String> {
        std::fs::create_dir_all(&self.folder).map_err(|e| t!("error-results-folder", detail = e.to_string()))?;
        // One file per check, so two checks at once cannot read each other's settings.
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let n = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let file = self.folder.join(format!("check-{}-{n}.yaml", std::process::id()));
        std::fs::write(&file, text).map_err(|e| t!("error-results-folder", detail = e.to_string()))?;
        let result = self.launcher.validate(Some(&file), &[]);
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

/// Settings that give a run access to this computer beyond the sample itself, as whole keys.
/// `policy.allow` lets the sample call parts of Java for real that the tool otherwise
/// simulates or blocks (one broad rule there undoes the tool's protection), and `classpath`
/// names further files on this computer to load as code.
const HOST_ACCESS_KEYS: &[&str] = &["policy.allow", "classpath"];

/// Settings inside list items that name a file on this computer to read into the sample's
/// world: the list, and the key in each of its items. A crafted value would hand the sample
/// any file this user can read (its content then lands in the report), or on Windows reach
/// another computer through a `\\host\share` path.
const HOST_FILE_KEYS: &[(&str, &str)] = &[("environment.files", "hostFile"), ("network.http", "bodyFile"), ("network.sockets", "file")];

/// Settings text without the settings that reach this computer: those that name a program to
/// start ([`HOST_PROGRAMS`]), that widen what the sample may really do ([`HOST_ACCESS_KEYS`])
/// or that name a file here to read ([`HOST_FILE_KEYS`]). For settings that come from a file
/// the app did not write: a report folder, an imported file or a preset. Such a file could
/// otherwise make a run start any program, switch the tool's protection off, or feed the
/// sample this computer's files. The person can still set any of these by hand afterwards.
/// A plugin whose program is removed stays on, and uses the Python the app bundles.
///
/// The settings are taken apart as a value, not edited as text. A file written to be read
/// two ways (a key given twice, a tagged key, a line break only some YAML readers honour)
/// cannot keep a setting in a place the editing would miss: what comes back, when anything
/// was removed, is written afresh from the value that was checked. Text with nothing to remove
/// is returned as it is, comments and all; a run is started from the same value in any case
/// ([`Session::start`]). Text that cannot be read is returned as it is: no run starts from it.
pub fn without_host_access(text: &str) -> String {
    let Some(mut value) = ConfigDoc::new(text).to_json() else { return text.to_string() };
    if strip_host_access(&mut value) { config::render_document(&value) } else { text.to_string() }
}

/// Removes the settings [`without_host_access`] names from a settings value. True if any was there.
fn strip_host_access(value: &mut serde_json::Value) -> bool {
    use serde_json::Value;
    let mut removed = false;
    for (plugin, setting) in HOST_PROGRAMS {
        // Only the program goes: an empty entry keeps the plugin on.
        if let Some(Value::Object(settings)) = at_mut(value, plugin) {
            removed |= settings.shift_remove(*setting).is_some();
        }
    }
    for key in HOST_ACCESS_KEYS {
        let (parent, name) = key.rsplit_once('.').unwrap_or(("", key));
        if let Some(Value::Object(map)) = at_mut(value, parent) {
            removed |= map.shift_remove(name).is_some();
        }
    }
    for (list, key) in HOST_FILE_KEYS {
        if let Some(Value::Array(items)) = at_mut(value, list) {
            let before = items.len();
            // An item is of no use without its content: a file to plant with no bytes, an
            // answer with no body. It goes whole, so the run does not quietly use an empty one.
            items.retain(|item| item.get(*key).is_none());
            removed |= items.len() != before;
        }
    }
    removed
}

/// The value at a dotted path of keys (`""` is the value itself), if every step is there.
fn at_mut<'a>(value: &'a mut serde_json::Value, path: &str) -> Option<&'a mut serde_json::Value> {
    path.split('.').filter(|step| !step.is_empty()).try_fold(value, |current, step| current.get_mut(step))
}

/// The program on this computer that settings tell the tool to start ([`HOST_PROGRAMS`]), as
/// the settings name it, or `None` if they name none. A value that is not text is given as the
/// settings spell it, so the person still sees what is there. Read from the same value a run
/// is started from, so what is asked about is what the tool will be told. Settings that
/// cannot be read give `None`: the session refuses to start a run with them at all.
pub fn host_program(text: &str) -> Option<String> {
    let mut value = ConfigDoc::new(text).to_json()?;
    HOST_PROGRAMS.iter().find_map(|(plugin, setting)| {
        let value = at_mut(&mut value, plugin)?.get(*setting)?;
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
        assert_eq!(host_program(&without_host_access("plugins:\n  native-bridge:\n    python: /x\n")), None);
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
        let cleaned = without_host_access(crafted);
        assert!(!cleaned.contains("python") && !cleaned.contains("host"), "{cleaned}");
        let doc = ConfigDoc::new(cleaned.clone());
        assert_eq!(doc.get(&ConfigPath::parse("plugins.native-bridge")), Some(serde_json::json!({})), "{cleaned}");
        assert_eq!(doc.get(&ConfigPath::parse("limits.maxSleeps")), Some(serde_json::json!(3)));

        let with_others = "plugins:\n  native-bridge:\n    python: /usr/bin/evil\n    other: 1\n";
        let cleaned = without_host_access(with_others);
        assert_eq!(ConfigDoc::new(cleaned.clone()).get(&ConfigPath::parse("plugins.native-bridge")), Some(serde_json::json!({"other": 1})), "{cleaned}");

        // Nothing to remove: the text is untouched, comments and all.
        let plain = "# mine\nlimits:\n  maxSleeps: 3   # three\n";
        assert_eq!(without_host_access(plain), plain);
        assert_eq!(without_host_access("not: [valid"), "not: [valid");
    }

    /// Settings from a file the app did not write may not widen what the sample can really
    /// do, load more code, or read this computer's files into the sample's world.
    #[test]
    fn settings_from_files_get_no_access_to_this_computer() {
        let crafted = concat!(
            "limits:\n  maxSleeps: 3\n",
            "policy:\n  allow: ['**']\n  deny: ['java/util/Random']\n  onBlocked: throw\n",
            "classpath: ['\\\\host\\share\\lib.jar']\n",
            "environment:\n  files:\n    - path: 'C:\\a.txt'\n      text: kept\n    - path: 'C:\\key'\n      hostFile: /home/me/.ssh/id_rsa\n",
            "network:\n  http:\n    - url: gate\n      bodyFile: /etc/passwd\n    - url: other\n      body: ok\n",
            "  sockets:\n    - endpoint: 'c2:443'\n      file: /etc/shadow\n",
        );
        let cleaned = without_host_access(crafted);
        for gone in ["'**'", "lib.jar", "id_rsa", "passwd", "shadow", "hostFile", "bodyFile"] {
            assert!(!cleaned.contains(gone), "{gone} is still there:\n{cleaned}");
        }
        let doc = ConfigDoc::new(cleaned.clone());
        assert_eq!(doc.get(&ConfigPath::parse("policy.allow")), None, "{cleaned}");
        assert_eq!(doc.get(&ConfigPath::parse("classpath")), None, "{cleaned}");
        // What narrows a run, and everything unrelated, is kept.
        assert_eq!(doc.get(&ConfigPath::parse("policy.deny")), Some(serde_json::json!(["java/util/Random"])), "{cleaned}");
        assert_eq!(doc.get(&ConfigPath::parse("policy.onBlocked")), Some(serde_json::json!("throw")), "{cleaned}");
        assert_eq!(doc.get(&ConfigPath::parse("limits.maxSleeps")), Some(serde_json::json!(3)));
        assert_eq!(doc.get(&ConfigPath::parse("environment.files")), Some(serde_json::json!([{"path": "C:\\a.txt", "text": "kept"}])), "{cleaned}");
        assert_eq!(doc.get(&ConfigPath::parse("network.http")), Some(serde_json::json!([{"url": "other", "body": "ok"}])), "{cleaned}");
        assert!(doc.syntax_problems().is_empty(), "{cleaned}");
    }

    /// Files written to be read one way by the app and another by the tool. The tool reads YAML
    /// 1.1, where U+2028, U+2029 and U+0085 end a line; it takes the last of a repeated key;
    /// and it ignores a tag on a key. Whatever the app's reader makes of such a file, nothing
    /// that reaches this computer may be left in what comes back.
    #[test]
    fn settings_written_to_be_read_two_ways_keep_nothing() {
        let repeated = [
            "policy:\n  onBlocked: phantom\npolicy:\n  allow: ['**']\n",
            "policy:\n  allow: ['a']\n  allow: ['**']\n",
            "classpath: [a.jar]\nclasspath: ['/etc']\n",
            "plugins:\n  native-bridge:\n    python: /a\n    python: /tmp/evil\n",
            "environment:\n  files:\n    - {path: a, text: b}\n  files:\n    - {path: k, hostFile: /etc/hostname}\n",
        ];
        for crafted in repeated {
            let cleaned = without_host_access(crafted);
            for gone in ["'**'", "allow", "classpath", "evil", "python", "hostFile"] {
                assert!(!cleaned.contains(gone), "{gone} survived in:\n{cleaned}\nfrom:\n{crafted}");
            }
            assert_eq!(host_program(&cleaned), None, "{cleaned}");
        }
        // Where the two readers disagree about what the file says, the app's reading decides
        // what is run: the run is started from the value, written out afresh.
        for crafted in [
            "limits:\n  maxSleeps: 3 # note\u{2028}policy: {allow: ['**']}\n",
            "environment:\n  userName: bob\u{85}policy: {allow: ['**']}\n",
            "!!str policy:\n  allow: ['**']\n",
            "limits: {maxSleeps: 3}\n!!str plugins:\n  native-bridge:\n    python: /tmp/evil\n",
        ] {
            let Some(value) = ConfigDoc::new(without_host_access(crafted)).to_json() else { continue };
            let run_from = config::render_document(&value);
            // One line per key the app saw, and no line break of any kind inside a value.
            assert!(!run_from.contains(['\u{2028}', '\u{2029}', '\u{85}']), "{run_from:?}");
            let reread = ConfigDoc::new(run_from.clone()).to_json().expect("the app can read what it wrote");
            assert_eq!(reread, value, "{run_from}");
            assert_eq!(reread.pointer("/policy/allow"), None, "{run_from}");
            assert_eq!(reread.pointer("/plugins/native-bridge/python"), None, "{run_from}");
            assert_eq!(host_program(&run_from), None);
        }
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

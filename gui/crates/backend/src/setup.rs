//! What the installed app brings with it, and the preferences that choose between that and the
//! person's own: the Java runtime, the JavaByte jar, the native bridge's Python, the output
//! folder and network isolation (docs/gui/PHASE5-PLAN.md).
//!
//! Installers put the bundled parts in the app's resource folder:
//!
//! ```text
//! runtime/          a jlink Java runtime (bin/java or bin\java.exe)
//! javabyte.jar      the tool
//! bridge/python/    a standalone Python with speakeasy-emulator (bridge installers only)
//! ```
//!
//! A developer build has none of these and falls back to `JAVABYTE_JAR`, a jar next to the
//! app, or the `target/` folder of a source checkout, and to `JAVABYTE_JAVA`, `JAVA_HOME` or
//! `java` on the path.

use crate::runner::Launcher;
use javabyte_gui_protocol::t;
use javabyte_gui_protocol::views::{AppPreferences, Isolation, SetupStatus};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

/// Where the app looks for its parts and keeps its preferences.
#[derive(Clone, Debug)]
pub struct Setup {
    /// The app's resource folder (Tauri's resource directory).
    pub resources: PathBuf,
    /// The folder of the app's executable, for developer builds.
    pub exe_dir: Option<PathBuf>,
    /// The app's data folder: preferences, recent runs, presets.
    pub data: PathBuf,
    /// Documents/JavaByte: where runs go unless the preferences say otherwise.
    pub default_output: PathBuf,
}

impl Setup {
    /// The preferences file.
    fn preferences_file(&self) -> PathBuf {
        self.data.join("preferences.json")
    }

    /// The saved preferences, or the defaults when there are none or they cannot be read.
    pub fn preferences(&self) -> AppPreferences {
        std::fs::read_to_string(self.preferences_file()).ok().and_then(|text| serde_json::from_str(&text).ok()).unwrap_or_default()
    }

    /// Saves the preferences after checking them: a chosen Java must start and be 17 or later.
    pub fn save_preferences(&self, preferences: &AppPreferences) -> Result<(), String> {
        if let Some(java) = &preferences.java {
            let version = java_version(Path::new(java)).ok_or_else(|| t!("error-java-chosen-start", path = java.as_str()))?;
            if major_version(&version) < 17 {
                return Err(t!("error-java-chosen-old", version = version));
            }
        }
        std::fs::create_dir_all(&self.data).map_err(|e| t!("error-preferences-save", detail = e.to_string()))?;
        let text = serde_json::to_string_pretty(preferences).expect("preferences serialise");
        std::fs::write(self.preferences_file(), text).map_err(|e| t!("error-preferences-save", detail = e.to_string()))
    }

    /// The folder runs are saved in.
    pub fn output_folder(&self, preferences: &AppPreferences) -> PathBuf {
        preferences.output_folder.as_ref().map_or_else(|| self.default_output.clone(), PathBuf::from)
    }

    /// The bundled Java runtime, if this is an installed app.
    pub fn bundled_java(&self) -> Option<PathBuf> {
        let java = self.resources.join("runtime").join("bin").join(if cfg!(windows) { "java.exe" } else { "java" });
        java.is_file().then_some(java)
    }

    /// The bundled native bridge's Python, if this app came with one.
    pub fn bundled_bridge(&self) -> Option<PathBuf> {
        let python = self.resources.join("bridge").join("python");
        let python = if cfg!(windows) { python.join("python.exe") } else { python.join("bin").join("python3") };
        python.is_file().then_some(python)
    }

    /// The JavaByte jar: bundled, then `JAVABYTE_JAR`, then next to the app, then a source
    /// checkout's `target/` folder.
    pub fn jar(&self) -> Option<PathBuf> {
        let mut candidates = vec![self.resources.join("javabyte.jar")];
        candidates.extend(std::env::var_os("JAVABYTE_JAR").map(PathBuf::from));
        if let Some(dir) = &self.exe_dir {
            candidates.push(dir.join("javabyte.jar"));
            for ancestor in dir.ancestors() {
                if let Ok(entries) = std::fs::read_dir(ancestor.join("target")) {
                    candidates.extend(entries.flatten().map(|e| e.path()).filter(|p| {
                        p.file_name().is_some_and(|n| {
                            let n = n.to_string_lossy();
                            n.starts_with("javabyte-") && n.ends_with(".jar") && !n.contains("original")
                        })
                    }));
                }
            }
        }
        candidates.into_iter().find(|p| p.is_file())
    }

    /// The engine as the preferences ask for it, or why it cannot be used. `isolation` is the
    /// result of [`isolation_status`], checked once at start.
    pub fn launcher(&self, preferences: &AppPreferences, isolation: &Isolation) -> Result<Launcher, String> {
        let jar = self.jar().ok_or_else(|| t!("error-engine-missing"))?;
        let mut launcher = match (&preferences.java, self.bundled_java()) {
            (Some(chosen), _) => Launcher::new(chosen, jar),
            (None, Some(bundled)) => Launcher::new(bundled, jar),
            (None, None) => Launcher::find(jar)?,
        };
        // Java must answer before anything is promised to the person.
        if java_version(&launcher.java).is_none() {
            return Err(t!("error-java-missing"));
        }
        launcher.isolate = preferences.isolate && isolation.available;
        launcher.bridge_python = self.bundled_bridge();
        Ok(launcher)
    }

    /// Everything the Preferences screen and `--self-check` show.
    pub fn status(&self, preferences: &AppPreferences, isolation: &Isolation) -> SetupStatus {
        let launcher = self.launcher(preferences, isolation);
        let java = launcher.as_ref().map(|l| l.java.clone()).ok().or_else(|| preferences.java.as_ref().map(PathBuf::from)).or_else(|| self.bundled_java());
        SetupStatus {
            version: env!("CARGO_PKG_VERSION").to_string(),
            os: std::env::consts::OS.to_string(),
            java_bundled: preferences.java.is_none() && self.bundled_java().is_some(),
            java_version: java.as_deref().and_then(java_version),
            java: java.map(|p| p.display().to_string()).unwrap_or_default(),
            jar: self.jar().map(|p| p.display().to_string()).unwrap_or_default(),
            output_folder: self.output_folder(preferences).display().to_string(),
            bridge: self.bundled_bridge().map(|p| p.display().to_string()),
            isolation: isolation.clone(),
            preferences: preferences.clone(),
        }
    }
}

/// Whether runs can be isolated from the network on this system. Takes a moment (it starts a
/// process), so the app checks once at start.
pub fn isolation_status() -> Isolation {
    #[cfg(target_os = "linux")]
    {
        match crate::isolation::check() {
            Ok(()) => Isolation { supported: true, available: true, problem: None },
            Err(error) => {
                // The AppImage cannot carry the AppArmor profile that Ubuntu 24.04 asks for.
                let problem = if std::env::var_os("APPIMAGE").is_some() {
                    t!("isolation-unavailable-appimage", detail = error.to_string())
                } else {
                    t!("isolation-unavailable", detail = error.to_string())
                };
                Isolation { supported: true, available: false, problem: Some(problem) }
            }
        }
    }
    #[cfg(not(target_os = "linux"))]
    {
        Isolation { supported: false, available: false, problem: Some(t!("isolation-not-on-this-system")) }
    }
}

/// What `java -version` reports, such as `21.0.5`; None when it does not start.
pub fn java_version(java: &Path) -> Option<String> {
    let mut command = Command::new(java);
    command.arg("-version").stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    let output = command.output().ok().filter(|o| o.status.success())?;
    parse_java_version(&String::from_utf8_lossy(&output.stderr))
}

/// The version in `java -version` output: the first quoted text, as in
/// `openjdk version "21.0.5" 2024-10-15`.
fn parse_java_version(text: &str) -> Option<String> {
    let start = text.find('"')? + 1;
    let end = start + text[start..].find('"')?;
    Some(text[start..end].to_string())
}

/// The major version: 21 for `21.0.5`, and 8 for the old style `1.8.0_402`.
fn major_version(version: &str) -> u32 {
    let mut parts = version.split(['.', '_', '-', '+']);
    match parts.next().and_then(|p| p.parse().ok()) {
        Some(1) => parts.next().and_then(|p| p.parse().ok()).unwrap_or(0),
        Some(major) => major,
        None => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn java_versions_are_read_from_both_styles() {
        let modern = "openjdk version \"21.0.5\" 2024-10-15\nOpenJDK Runtime Environment Temurin-21.0.5+11 (build 21.0.5+11)\n";
        assert_eq!(parse_java_version(modern).as_deref(), Some("21.0.5"));
        assert_eq!(major_version("21.0.5"), 21);
        assert_eq!(major_version("17"), 17);
        assert_eq!(major_version("25-ea"), 25);
        assert_eq!(major_version("1.8.0_402"), 8);
        assert_eq!(parse_java_version("no version here"), None);
    }

    fn setup(dir: &Path) -> Setup {
        Setup { resources: dir.join("resources"), exe_dir: None, data: dir.join("data"), default_output: dir.join("Documents/JavaByte") }
    }

    #[test]
    fn preferences_round_trip_and_default_when_missing() {
        let dir = std::env::temp_dir().join(format!("javabyte-setup-{}", std::process::id()));
        let setup = setup(&dir);
        assert_eq!(setup.preferences(), AppPreferences::default());
        assert!(setup.preferences().isolate, "isolation is on unless someone turns it off");
        let changed = AppPreferences { output_folder: Some("/elsewhere".into()), ask_without_isolation: false, ..AppPreferences::default() };
        setup.save_preferences(&changed).unwrap();
        assert_eq!(setup.preferences(), changed);
        assert_eq!(setup.output_folder(&changed), PathBuf::from("/elsewhere"));
        // A Java that does not start is refused, and nothing is saved.
        let bad = AppPreferences { java: Some(dir.join("no-such-java").display().to_string()), ..changed.clone() };
        assert!(setup.save_preferences(&bad).is_err());
        assert_eq!(setup.preferences(), changed);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn bundled_parts_are_found_in_the_resource_folder() {
        let dir = std::env::temp_dir().join(format!("javabyte-bundle-{}", std::process::id()));
        let setup = setup(&dir);
        assert_eq!(setup.bundled_java(), None);
        assert_eq!(setup.bundled_bridge(), None);
        let java = setup.resources.join("runtime/bin").join(if cfg!(windows) { "java.exe" } else { "java" });
        let python = if cfg!(windows) { setup.resources.join("bridge/python/python.exe") } else { setup.resources.join("bridge/python/bin/python3") };
        for file in [&java, &python, &setup.resources.join("javabyte.jar")] {
            std::fs::create_dir_all(file.parent().unwrap()).unwrap();
            std::fs::write(file, b"").unwrap();
        }
        assert_eq!(setup.bundled_java(), Some(java));
        assert_eq!(setup.bundled_bridge(), Some(python));
        assert_eq!(setup.jar(), Some(setup.resources.join("javabyte.jar")));
        let _ = std::fs::remove_dir_all(dir);
    }
}

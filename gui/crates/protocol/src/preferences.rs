//! The preference changes the screens may ask the backend for, and the rule for when a run
//! needs the person's agreement first.
//!
//! Both the desktop app's commands and the screens' browser stand-in use these, so the wire
//! format, the way a change is applied and the question rule are written once. A variant added
//! here is known to both sides at compile time.

use crate::views::{AppPreferences, Isolation};
use serde::{Deserialize, Serialize};

/// A preference change the screens may ask for. Paths are never among them: a Java runtime or
/// an output folder is chosen with the system's picker, in the app's `choose_java` and
/// `choose_output_folder` commands, so the screens cannot name a path of their own.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum PreferenceChange {
    /// Start runs with no network (Linux namespaces; on Windows an AppContainer or Windows
    /// Sandbox via the sandbox add-on).
    Isolate(bool),
    /// Ask before a run that cannot be isolated.
    AskWithoutIsolation(bool),
    /// Windows, with the sandbox add-on: use Windows Sandbox rather than an AppContainer.
    Strongest(bool),
    /// Use the Java that came with the app again.
    BundledJava,
    /// Save runs in Documents/JavaByte again.
    DefaultOutputFolder,
}

impl PreferenceChange {
    /// Makes this change to `preferences`. Saving them is the caller's job.
    pub fn apply(self, preferences: &mut AppPreferences) {
        match self {
            Self::Isolate(on) => preferences.isolate = on,
            Self::AskWithoutIsolation(ask) => preferences.ask_without_isolation = ask,
            Self::Strongest(on) => preferences.strongest_isolation = on,
            Self::BundledJava => preferences.java = None,
            Self::DefaultOutputFolder => preferences.output_folder = None,
        }
    }
}

/// Whether starting a run needs the person's agreement first, and why. It does when this
/// computer supports isolation, it is on in Preferences, it cannot be used right now, and the
/// person has not said to stop asking. The answer is the reason isolation cannot be used,
/// which may be empty when none is known.
pub fn run_question(isolation: &Isolation, preferences: &AppPreferences) -> Option<String> {
    (isolation.supported && preferences.isolate && !isolation.available && preferences.ask_without_isolation)
        .then(|| isolation.problem.clone().unwrap_or_default())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn changes_keep_their_wire_format() {
        // The screens send these through Tauri as JSON; renaming one breaks the app at run time.
        let cases = [
            (PreferenceChange::Isolate(true), r#"{"isolate":true}"#),
            (PreferenceChange::AskWithoutIsolation(false), r#"{"ask-without-isolation":false}"#),
            (PreferenceChange::Strongest(true), r#"{"strongest":true}"#),
            (PreferenceChange::BundledJava, r#""bundled-java""#),
            (PreferenceChange::DefaultOutputFolder, r#""default-output-folder""#),
        ];
        for (change, json) in cases {
            assert_eq!(serde_json::to_string(&change).unwrap(), json);
            assert_eq!(serde_json::from_str::<PreferenceChange>(json).unwrap(), change);
        }
    }

    #[test]
    fn changes_apply_to_their_preference_only() {
        let start = AppPreferences { output_folder: Some("out".into()), java: Some("java".into()), ..AppPreferences::default() };
        let after = |change: PreferenceChange| {
            let mut p = start.clone();
            change.apply(&mut p);
            p
        };
        assert_eq!(after(PreferenceChange::Isolate(false)), AppPreferences { isolate: false, ..start.clone() });
        assert_eq!(after(PreferenceChange::AskWithoutIsolation(false)), AppPreferences { ask_without_isolation: false, ..start.clone() });
        assert_eq!(after(PreferenceChange::Strongest(true)), AppPreferences { strongest_isolation: true, ..start.clone() });
        assert_eq!(after(PreferenceChange::BundledJava), AppPreferences { java: None, ..start.clone() });
        assert_eq!(after(PreferenceChange::DefaultOutputFolder), AppPreferences { output_folder: None, ..start.clone() });
    }

    #[test]
    fn asks_only_when_isolation_is_wanted_but_unavailable() {
        let unavailable = Isolation { supported: true, available: false, problem: Some("no namespaces".into()), ..Isolation::default() };
        let prefs = AppPreferences::default();
        assert_eq!(run_question(&unavailable, &prefs), Some("no namespaces".into()));
        assert_eq!(run_question(&Isolation { problem: None, ..unavailable.clone() }, &prefs), Some(String::new()));
        // Any one condition missing: no question.
        assert_eq!(run_question(&Isolation { supported: false, ..unavailable.clone() }, &prefs), None);
        assert_eq!(run_question(&Isolation { available: true, ..unavailable.clone() }, &prefs), None);
        assert_eq!(run_question(&unavailable, &AppPreferences { isolate: false, ..prefs.clone() }), None);
        assert_eq!(run_question(&unavailable, &AppPreferences { ask_without_isolation: false, ..prefs }), None);
    }
}

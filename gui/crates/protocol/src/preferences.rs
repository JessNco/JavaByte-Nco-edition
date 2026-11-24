//! The preference changes the screens may ask the backend for, and the rules for when a run
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
    /// Start runs isolated: with no network, and on Linux a read-only file system as well
    /// (Linux namespaces; on Windows an AppContainer or Windows Sandbox via the sandbox add-on).
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

/// What the person must agree to before the next run starts, as the app's `run_question`
/// command answers. The screens ask about each one that is set, in turn, and start the run only
/// if the person agrees to all of them.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunQuestions {
    /// The run cannot be isolated from the network: why, from [`run_question`].
    pub isolation: Option<String>,
    /// The settings tell the tool to start this program on this computer
    /// (`plugins.native-bridge.python`). The run starts only if the person agrees to it, and
    /// the app is told this very path when it starts the run, so settings changed in between
    /// are asked about again.
    pub program: Option<String>,
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
    fn questions_keep_their_wire_format() {
        let questions = RunQuestions { isolation: Some("no namespaces".into()), program: Some("/usr/bin/python3".into()) };
        let json = r#"{"isolation":"no namespaces","program":"/usr/bin/python3"}"#;
        assert_eq!(serde_json::to_string(&questions).unwrap(), json);
        assert_eq!(serde_json::from_str::<RunQuestions>(json).unwrap(), questions);
        assert_eq!(serde_json::to_string(&RunQuestions::default()).unwrap(), r#"{"isolation":null,"program":null}"#);
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

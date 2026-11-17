//! The same settings as a command line: `javabyte run SAMPLE -s KEY=VALUE ...`, quoted for the
//! shell people use (POSIX shells, or PowerShell on Windows).

use super::render_scalar;
use serde_json::Value;

/// The shell a command line is written for.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Shell {
    /// sh, bash, zsh: Linux and macOS terminals.
    Posix,
    /// PowerShell: the usual Windows terminal.
    PowerShell,
}

impl Shell {
    /// The usual shell where the app runs.
    pub fn native() -> Self {
        if cfg!(windows) { Shell::PowerShell } else { Shell::Posix }
    }
}

/// Quotes one argument for a shell, leaving it bare only when nothing in it is special there.
pub fn shell_quote(arg: &str, shell: Shell) -> String {
    let bare = !arg.is_empty() && arg.chars().all(|c| c.is_ascii_alphanumeric() || "-_./:=,+@%".contains(c));
    match shell {
        _ if bare => arg.to_string(),
        Shell::Posix => format!("'{}'", arg.replace('\'', r"'\''")),
        // In PowerShell single quotes are literal, and a quote inside is doubled. The typographic
        // quotes PowerShell also treats as quotes are doubled the same way.
        Shell::PowerShell => {
            let mut out = String::from("'");
            for c in arg.chars() {
                if matches!(c, '\'' | '\u{2018}' | '\u{2019}' | '\u{201a}' | '\u{201b}') {
                    out.push(c);
                }
                out.push(c);
            }
            out.push('\'');
            out
        }
    }
}

/// The `-s KEY=VALUE` arguments that give the same settings as `config` on the command line.
/// `sample` is left out (it is the command's own argument). A group is split into its settings
/// when its keys can be written in a path; otherwise (keys with dots, lists) it is passed whole,
/// as JSON, which the tool reads as YAML.
pub fn overrides(config: &Value) -> Vec<String> {
    fn simple(key: &str) -> bool {
        !key.is_empty() && key.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
    }
    fn walk(path: &str, value: &Value, out: &mut Vec<String>) {
        match value {
            Value::Object(map) if !map.is_empty() && map.keys().all(|k| simple(k)) => {
                for (key, child) in map {
                    walk(&format!("{path}.{key}"), child, out);
                }
            }
            Value::Object(_) | Value::Array(_) => out.push(format!("{path}={value}")),
            _ => out.push(format!("{path}={}", render_scalar(value))),
        }
    }
    let mut out = Vec::new();
    if let Value::Object(map) = config {
        for (key, value) in map {
            if key == "sample" {
                continue;
            }
            if simple(key) {
                walk(key, value, &mut out);
            } else {
                // A top-level key a path cannot name is not a known option; pass it anyway so
                // the tool reports it, as it would for the file.
                out.push(format!("{key}={value}"));
            }
        }
    }
    out
}

/// `javabyte run SAMPLE -s …` for the settings in `config`.
pub fn command_line(sample: &str, config: &Value, shell: Shell) -> String {
    let mut parts = vec!["javabyte".to_string(), "run".into(), shell_quote(sample, shell)];
    for setting in overrides(config) {
        parts.push("-s".into());
        parts.push(shell_quote(&setting, shell));
    }
    parts.join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn command_lines_quote_for_each_shell() {
        assert_eq!(shell_quote("plain-name.jar", Shell::Posix), "plain-name.jar");
        assert_eq!(shell_quote("C:\\x y.jar", Shell::Posix), "'C:\\x y.jar'");
        assert_eq!(shell_quote("C:\\x", Shell::Posix), "'C:\\x'", "a backslash is special in sh");
        assert_eq!(shell_quote("it's", Shell::Posix), r"'it'\''s'");
        assert_eq!(shell_quote("it's", Shell::PowerShell), "'it''s'");
        assert_eq!(shell_quote("it\u{2019}s", Shell::PowerShell), "'it\u{2019}\u{2019}s'");
        let config = json!({
            "sample": "/x.jar",
            "limits": {"maxSleeps": 10},
            "environment": {"properties": {"java.version": "17"}, "userName": "o'brien"},
            "network": {"http": [{"url": "a", "body": "b: c"}]},
            "plugins": {"native-bridge": {}},
        });
        assert_eq!(
            overrides(&config),
            vec![
                "limits.maxSleeps=10",
                "environment.properties={\"java.version\":\"17\"}",
                "environment.userName='o''brien'",
                "network.http=[{\"url\":\"a\",\"body\":\"b: c\"}]",
                "plugins.native-bridge={}",
            ]
        );
        assert!(command_line("/x.jar", &config, Shell::Posix).starts_with("javabyte run /x.jar -s limits.maxSleeps=10 -s "));
    }
}

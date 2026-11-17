//! Writing values as YAML: block style for new lists and groups, and strings quoted unless
//! they are plainly words, so that YAML 1.1 readers (the Java tool's) and YAML 1.2 readers
//! both read text as text.

use serde_json::Value;

/// A whole value as a YAML document, in block style.
pub fn render_document(value: &Value) -> String {
    let mut lines = Vec::new();
    render_lines(value, 0, &mut lines);
    let mut out = lines.join("\n");
    out.push('\n');
    out
}

fn is_nonempty_collection(value: &Value) -> bool {
    matches!(value, Value::Object(m) if !m.is_empty()) || matches!(value, Value::Array(a) if !a.is_empty())
}

/// Appends the lines of `value`, each indented by `indent` spaces.
fn render_lines(value: &Value, indent: usize, lines: &mut Vec<String>) {
    let pad = " ".repeat(indent);
    match value {
        Value::Object(map) if !map.is_empty() => {
            for (key, child) in map {
                let key = render_scalar_str(key);
                if is_nonempty_collection(child) {
                    lines.push(format!("{pad}{key}:"));
                    render_lines(child, indent + 2, lines);
                } else {
                    lines.push(format!("{pad}{key}: {}", render_scalar(child)));
                }
            }
        }
        Value::Array(items) if !items.is_empty() => {
            for item in items {
                if is_nonempty_collection(item) {
                    // The item's first line goes on the dash's line; the rest line up under it.
                    let start = lines.len();
                    render_lines(item, indent + 2, lines);
                    lines[start] = format!("{pad}- {}", &lines[start][indent + 2..]);
                } else {
                    lines.push(format!("{pad}- {}", render_scalar(item)));
                }
            }
        }
        _ => lines.push(format!("{pad}{}", render_scalar(value))),
    }
}

/// A single value, quoted when it needs to be. Empty collections are `{}` and `[]`.
pub fn render_scalar(value: &Value) -> String {
    match value {
        Value::Null => "null".into(),
        Value::Bool(b) => b.to_string(),
        Value::Number(n) => n.to_string(),
        Value::String(s) => render_scalar_str(s),
        Value::Array(_) => "[]".into(),
        Value::Object(_) => "{}".into(),
    }
}

/// Words a YAML reader of either version takes for something other than text.
const RESERVED: &[&str] = &["null", "true", "false", "yes", "no", "on", "off", "y", "n", "~"];

/// A string as YAML: plain when it is plainly a word or a dotted or slashed name, otherwise
/// single-quoted, or double-quoted with escapes if it holds characters single quotes cannot.
pub fn render_scalar_str(s: &str) -> String {
    let plain = !s.is_empty()
        && s.chars().next().is_some_and(|c| c.is_ascii_alphabetic() || c == '_' || c == '/')
        && s.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | '-' | '/'))
        && !RESERVED.contains(&s.to_ascii_lowercase().as_str())
        // `1e3`-like forms start with a digit and are already excluded; `.inf` starts with a dot.
        && !s.ends_with(['.', '-']);
    if plain {
        return s.to_string();
    }
    if s.chars().all(|c| !c.is_control() && !is_invisible_break(c)) {
        return format!("'{}'", s.replace('\'', "''"));
    }
    let mut out = String::from("\"");
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\t' => out.push_str("\\t"),
            '\r' => out.push_str("\\r"),
            c if c.is_control() || is_invisible_break(c) => {
                let code = c as u32;
                if code <= 0xff {
                    out.push_str(&format!("\\x{code:02x}"));
                } else {
                    out.push_str(&format!("\\u{code:04x}"));
                }
            }
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// Line and paragraph separators and the byte order mark, which YAML treats specially.
fn is_invisible_break(c: char) -> bool {
    matches!(c, '\u{2028}' | '\u{2029}' | '\u{feff}' | '\u{85}')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strings_that_look_like_other_things_are_quoted() {
        for s in ["yes", "No", "on", "OFF", "y", "null", "~", "true", "010", "1e3", "0x1F", ".inf", "-1", "", " x", "x ", "a: b", "#c", "- d", "[e]", "{f}", "g,h", "it's", "\"q\"", "C:\\Users", "*a", "&a", "!t", "%p", "@x", "`x", "é", "line\nbreak", "tab\tx", "bell\u{7}", "\u{2028}"] {
            let rendered = render_scalar_str(s);
            let back: serde_yaml::Value = serde_yaml::from_str(&format!("v: {rendered}")).unwrap();
            assert_eq!(back["v"], serde_yaml::Value::String(s.into()), "{s:?} rendered as {rendered}");
        }
        assert_eq!(render_scalar_str("dev.javabyte.Main"), "dev.javabyte.Main");
        assert_eq!(render_scalar_str("com/example/Checks"), "com/example/Checks");
    }
}

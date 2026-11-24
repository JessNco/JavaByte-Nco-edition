//! All user-visible text comes from the resource files (docs/GUI-PLAN.md, "Text and
//! translation"). These tests read the source of the screens, the backend and the app:
//!
//! - no string literal that reads like words for people, outside the resource files;
//! - every message the code names exists;
//! - every message in the resource files is used.
//!
//! A literal that looks like words but is not shown to people (a key name, a shell command, a
//! technical detail passed as an argument) is allowed by ending its line with
//! `// not-ui-text: <why>`.

use javabyte_gui_protocol::i18n::{self, RESOURCES};
use std::path::{Path, PathBuf};

fn gui_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..").canonicalize().unwrap()
}

/// Rust files whose text people can see. Tests are excluded: they quote text to check it.
fn sources() -> Vec<PathBuf> {
    let root = gui_root();
    let mut files = Vec::new();
    for dir in ["ui/src", "crates/backend/src", "crates/protocol/src", "app/src"] {
        collect(&root.join(dir), &mut files);
    }
    files
}

fn collect(dir: &Path, out: &mut Vec<PathBuf>) {
    for entry in std::fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            collect(&path, out);
        } else if path.extension().is_some_and(|e| e == "rs") {
            out.push(path);
        }
    }
}

/// One string literal: its text, line number, the code before it on its line, the code after it
/// on its line, and the comment ending its line.
struct Literal {
    text: String,
    line: usize,
    before: String,
    after: String,
    comment: String,
}

/// String literals outside comments and test modules (`#[cfg(test)]`, or `#[cfg(all(test, ...))]`
/// for tests that need one system). A small scanner rather than a parser: enough for this code
/// base, which uses no raw strings for text.
fn literals(source: &str) -> Vec<Literal> {
    // Unit tests at the end of a file quote text on purpose.
    let source = ["#[cfg(test)]", "#[cfg(all(test"].iter().filter_map(|marker| source.find(marker)).min().map_or(source, |end| &source[..end]);
    let mut out = Vec::new();
    for (index, line) in source.lines().enumerate() {
        let chars: Vec<char> = line.chars().collect();
        let mut i = 0;
        let mut found: Vec<(usize, usize, String)> = Vec::new();
        let mut comment = String::new();
        while i < chars.len() {
            match chars[i] {
                '/' if chars.get(i + 1) == Some(&'/') => {
                    comment = chars[i..].iter().collect();
                    break;
                }
                // A char literal such as '"' must not open a string.
                '\'' if chars.get(i + 2) == Some(&'\'') => i += 3,
                '\'' if chars.get(i + 1) == Some(&'\\') && chars.get(i + 3) == Some(&'\'') => i += 4,
                '"' => {
                    let start = i;
                    let mut text = String::new();
                    i += 1;
                    while i < chars.len() && chars[i] != '"' {
                        if chars[i] == '\\' {
                            i += 1;
                        }
                        if let Some(c) = chars.get(i) {
                            text.push(*c);
                        }
                        i += 1;
                    }
                    found.push((start, i.min(chars.len()), text));
                    i += 1;
                }
                _ => i += 1,
            }
        }
        for (start, end, text) in found {
            out.push(Literal {
                text,
                line: index + 1,
                before: chars[..start].iter().collect(),
                after: chars.get(end + 1..).map(|c| c.iter().collect()).unwrap_or_default(),
                comment: comment.clone(),
            });
        }
    }
    out
}

/// Whether a literal reads like words meant for people.
fn looks_like_words(text: &str) -> bool {
    // Placeholders count as words: "Analysing {name}" is a sentence.
    let mut plain = String::new();
    let mut depth = 0;
    for c in text.chars() {
        match c {
            '{' => {
                depth += 1;
                if depth == 1 {
                    plain.push_str("Xx");
                }
            }
            '}' => depth -= 1,
            _ if depth == 0 => plain.push(c),
            _ => {}
        }
    }
    // Only placeholders, spaces and symbols ("{} {}", "{}px"): formatting, not words.
    if plain.replace("Xx", "").chars().filter(|c| c.is_alphabetic()).count() < 3 && !plain.contains('…') {
        return false;
    }
    if plain.contains('…') {
        return true;
    }
    let words: Vec<&str> = plain.split(' ').collect();
    let is_word = |w: &str| w.trim_end_matches([',', '.', ':', '!', '?']).chars().all(|c| c.is_alphabetic() || c == '(' || c == '\'' || c == ')') && !w.is_empty();
    let has_phrase = words.windows(2).any(|pair| {
        is_word(pair[0]) && is_word(pair[1]) && (pair[0].chars().filter(|c| c.is_alphabetic()).count() >= 2 || pair[1].chars().filter(|c| c.is_alphabetic()).count() >= 2)
    });
    // A single capitalised word, as a heading or label would be ("Events", "Finished.").
    let single = words.len() == 1 && {
        let w = plain.trim_end_matches(['.', ':', '!', '?']);
        let mut c = w.chars();
        c.next().is_some_and(char::is_uppercase) && w.len() >= 3 && c.all(char::is_lowercase)
    };
    has_phrase || single
}

/// Whether the code before a literal ends with a call to `name` (`t!(`, `tr(`), and not with a
/// longer name that ends the same way (`format!(`, `attr(`).
fn calls(before: &str, name: &str) -> bool {
    before.strip_suffix(name).is_some_and(|rest| !rest.ends_with(|c: char| c.is_alphanumeric() || c == '_'))
}

/// Literals that look like words but are not text for people, by where they appear.
fn exempt(literal: &Literal) -> bool {
    let before = literal.before.trim_end();
    let after = literal.after.trim_start();
    literal.comment.contains("not-ui-text")
        // Message names.
        || calls(before, "t!(")
        || calls(before, "tr(")
        || calls(before, "tr(&format!(")
        // Values that are not text: attributes, and keys matched in code.
        || ["id=", "class=", "type=", "for=", "name=", "href=", "role=", "scope=", "autocomplete=", "value=", "inputmode="]
            .iter()
            .any(|a| before.ends_with(a))
        || before.ends_with("format!(") && (before.contains("class=") || before.contains("style:"))
        || after.starts_with("=>")
        || before.ends_with("==")
        // Panics are for developers.
        || before.ends_with(".expect(")
        || before.ends_with("panic!(")
        || before.ends_with("unreachable!(")
        || before.contains("assert!(")
        || before.ends_with("eprintln!(")
}

#[test]
fn no_text_outside_the_resource_files() {
    let mut problems = Vec::new();
    for file in sources() {
        let source = std::fs::read_to_string(&file).unwrap();
        for literal in literals(&source) {
            if looks_like_words(&literal.text) && !exempt(&literal) {
                problems.push(format!("{}:{}: {:?}", file.display(), literal.line, literal.text));
            }
        }
    }
    assert!(
        problems.is_empty(),
        "Text for people must come from gui/locales (use t!), or mark the line `// not-ui-text: why`:\n{}",
        problems.join("\n")
    );
}

/// The names of all messages in the English resources.
fn message_ids() -> Vec<String> {
    let (_, files) = RESOURCES.iter().find(|(language, _)| *language == "en").unwrap();
    files
        .iter()
        .flat_map(|(_, text)| text.lines())
        .filter_map(|line| {
            let (id, _) = line.split_once(" =")?;
            (!id.starts_with([' ', '#', '-', '*', '[']) && !id.is_empty()).then(|| id.to_string())
        })
        .collect()
}

#[test]
fn every_message_named_in_the_code_exists() {
    let mut missing = Vec::new();
    for file in sources() {
        let source = std::fs::read_to_string(&file).unwrap();
        for literal in literals(&source) {
            let before = literal.before.trim_end();
            if (calls(before, "t!(") || calls(before, "tr("))
                && !i18n::has(&literal.text) {
                    missing.push(format!("{}:{}: {}", file.display(), literal.line, literal.text));
                }
        }
    }
    assert!(missing.is_empty(), "messages not in gui/locales/en:\n{}", missing.join("\n"));
}

#[test]
fn every_message_is_used() {
    let all_code: String = sources().iter().map(|f| std::fs::read_to_string(f).unwrap()).collect();
    // Messages chosen at run time, such as `outcome-{name}`, are named by their prefix. A
    // `format!` that builds a file name (`check-{n}.yaml`, told by the dot) is not one of them,
    // and must not keep a whole family of unused messages alive.
    let dynamic_prefixes: Vec<String> = all_code
        .match_indices("format!(\"")
        .filter_map(|(at, _)| {
            let rest = &all_code[at + 9..];
            let literal = &rest[..rest.find('"')?];
            let prefix = &literal[..literal.find('{')?];
            (prefix.ends_with('-') && !prefix.contains(' ') && !literal.contains('.')).then(|| prefix.to_string())
        })
        .collect();
    let unused: Vec<String> = message_ids()
        .into_iter()
        .filter(|id| !all_code.contains(&format!("\"{id}\"")))
        .filter(|id| !dynamic_prefixes.iter().any(|p| id.starts_with(p.as_str())))
        .collect();
    assert!(unused.is_empty(), "messages in gui/locales/en that nothing uses:\n{}", unused.join("\n"));
}

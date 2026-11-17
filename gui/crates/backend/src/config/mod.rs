//! A JavaByte config as YAML text that the settings forms edit in place.
//!
//! The text is the source of truth (docs/gui/PHASE3-PLAN.md): the YAML tab shows it as it is,
//! and a change made in a form touches only the lines of the value it changes. Comments, the
//! order of keys and the layout of everything else stay as the person (or `javabyte init`)
//! wrote them.
//!
//! New values are written as block YAML. Strings are quoted unless they are plainly words, so
//! that neither YAML 1.1 readers (the Java tool's) nor YAML 1.2 readers can take `yes`, `010`,
//! `1e3` or `null` for anything but text.

use serde_json::{Map, Number, Value};
use std::str::FromStr;
use yaml_edit::{Document, Mapping, Sequence, YamlFile};

mod command_line;
mod render;

pub use command_line::{Shell, command_line, overrides, shell_quote};
pub use render::{render_document, render_scalar, render_scalar_str};

/// One step of a path into the config: a key of a mapping, or a position in a list.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Step {
    /// A key of a mapping. It may contain dots: `java.version` is one key.
    Key(String),
    /// A position in a list, from 0.
    Index(usize),
}

/// A path such as `network.http[2].url`. Keys may contain dots when given as [`Step::Key`]
/// directly (`environment.properties` keys such as `java.version`).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ConfigPath(pub Vec<Step>);

impl ConfigPath {
    /// Parses `a.b[2].c`. Only for paths whose keys contain no dots or brackets.
    pub fn parse(text: &str) -> Self {
        let mut steps = Vec::new();
        for part in text.split('.').filter(|p| !p.is_empty()) {
            let (key, rest) = part.split_once('[').map_or((part, ""), |(k, r)| (k, r));
            if !key.is_empty() {
                steps.push(Step::Key(key.to_string()));
            }
            for index in rest.split('[').filter(|r| !r.is_empty()) {
                if let Ok(i) = index.trim_end_matches(']').parse() {
                    steps.push(Step::Index(i));
                }
            }
        }
        ConfigPath(steps)
    }

    /// This path with one more key.
    pub fn key(mut self, key: impl Into<String>) -> Self {
        self.0.push(Step::Key(key.into()));
        self
    }

    /// This path with one more list position.
    pub fn index(mut self, index: usize) -> Self {
        self.0.push(Step::Index(index));
        self
    }

    /// The schema path this belongs to: indices become `[]` (`network.http[].url`).
    pub fn schema_path(&self) -> String {
        let mut out = String::new();
        for step in &self.0 {
            match step {
                Step::Key(k) => {
                    if !out.is_empty() {
                        out.push('.');
                    }
                    out.push_str(k);
                }
                Step::Index(_) => out.push_str("[]"),
            }
        }
        out
    }
}

impl std::fmt::Display for ConfigPath {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let mut first = true;
        for step in &self.0 {
            match step {
                Step::Key(k) => {
                    if !first {
                        f.write_str(".")?;
                    }
                    f.write_str(k)?;
                }
                Step::Index(i) => write!(f, "[{i}]")?,
            }
            first = false;
        }
        Ok(())
    }
}

/// Where the YAML cannot be read: 1-based line and column, and the parser's message.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SyntaxProblem {
    /// 1-based line.
    pub line: usize,
    /// 1-based column, in characters.
    pub column: usize,
    /// The YAML reader's message, in English.
    pub message: String,
}

/// Why an edit could not be made.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum EditError {
    /// The YAML has syntax errors; forms cannot edit it until they are fixed.
    Syntax(Vec<SyntaxProblem>),
    /// Something on the path is not what the path needs (a list where a mapping is needed, a
    /// position past the end of a list).
    Shape(String),
}

/// The config document.
#[derive(Clone, Debug)]
pub struct ConfigDoc {
    text: String,
}

impl ConfigDoc {
    /// A document holding `text`, which need not be valid YAML yet.
    pub fn new(text: impl Into<String>) -> Self {
        ConfigDoc { text: text.into() }
    }

    /// The YAML as it is now.
    pub fn text(&self) -> &str {
        &self.text
    }

    /// Replaces the whole text, as typing in the YAML tab does.
    pub fn set_text(&mut self, text: impl Into<String>) {
        self.text = text.into();
    }

    /// Syntax problems, first first. Empty when the text can be read.
    pub fn syntax_problems(&self) -> Vec<SyntaxProblem> {
        let parsed = YamlFile::parse(&self.text);
        parsed
            .positioned_errors()
            .iter()
            .map(|e| {
                let at = e.start_position(&self.text);
                SyntaxProblem { line: at.line, column: at.column, message: e.message.clone() }
            })
            .collect()
    }

    /// The parsed document, or the syntax problems that stop editing.
    fn document(&self) -> Result<Document, EditError> {
        let problems = self.syntax_problems();
        if !problems.is_empty() {
            return Err(EditError::Syntax(problems));
        }
        if self.text.trim().is_empty() {
            return Document::from_str("{}").map_err(|e| EditError::Shape(e.to_string()));
        }
        Document::from_str(&self.text).map_err(|e| EditError::Syntax(vec![SyntaxProblem { line: 1, column: 1, message: e.to_string() }]))
    }

    /// The whole config as JSON, for the forms. `None` if the text cannot be read.
    pub fn to_json(&self) -> Option<Value> {
        let document = self.document().ok()?;
        if self.text.trim().is_empty() {
            return Some(Value::Object(Map::new()));
        }
        Some(match (document.as_mapping(), document.as_sequence(), document.as_scalar()) {
            (Some(m), _, _) => mapping_json(&m),
            (_, Some(s), _) => sequence_json(&s),
            (_, _, Some(s)) => scalar_json(&s),
            _ => Value::Null,
        })
    }

    /// The value at a path, or `None` if it is not set.
    pub fn get(&self, path: &ConfigPath) -> Option<Value> {
        let mut value = self.to_json()?;
        for step in &path.0 {
            value = match (step, value) {
                (Step::Key(k), Value::Object(mut m)) => m.remove(k)?,
                (Step::Index(i), Value::Array(mut a)) if *i < a.len() => a.swap_remove(*i),
                _ => return None,
            };
        }
        Some(value)
    }

    /// Sets the value at a path, creating the mappings above it if they are missing. Lists are
    /// not created implicitly: add list items with [`ConfigDoc::push`].
    pub fn set(&mut self, path: &ConfigPath, value: &Value) -> Result<(), EditError> {
        let document = self.document()?;
        let Some((last, parents)) = path.0.split_last() else {
            self.text = render_document(value);
            return Ok(());
        };
        let root = root_mapping(&document)?;
        let root = match root {
            Some(root) => root,
            None => {
                // An empty document: write the whole value out.
                let mut whole = Value::Object(Map::new());
                insert_json(&mut whole, &path.0, value.clone());
                self.text = render_document(&whole);
                return Ok(());
            }
        };
        // Walk down; where a mapping is missing, set the rest of the path as one new value.
        let mut node = Container::Map(root);
        for (depth, step) in parents.iter().enumerate() {
            let next = match (&node, step) {
                (Container::Map(m), Step::Key(k)) => match m.get(k.as_str()) {
                    Some(child) => container(&child, path, depth)?,
                    None => {
                        let mut rest = Value::Object(Map::new());
                        insert_json(&mut rest, &path.0[depth + 1..], value.clone());
                        m.set(k.as_str(), parsed_node(&rest)?);
                        self.text = document.to_string();
                        return Ok(());
                    }
                },
                (Container::List(s), Step::Index(i)) => {
                    let child = s.get(*i).ok_or_else(|| EditError::Shape(javabyte_gui_protocol::t!("config-no-item", path = path.to_string(), index = *i)))?;
                    container(&child, path, depth)?
                }
                _ => return Err(EditError::Shape(javabyte_gui_protocol::t!("config-wrong-shape", path = path.to_string()))),
            };
            node = next;
        }
        match (&node, last) {
            (Container::Map(m), Step::Key(k)) => m.set(k.as_str(), parsed_node(value)?),
            (Container::List(s), Step::Index(i)) if *i < s.len() => {
                s.set(*i, parsed_node(value)?);
            }
            _ => return Err(EditError::Shape(javabyte_gui_protocol::t!("config-wrong-shape", path = path.to_string()))),
        }
        self.text = document.to_string();
        Ok(())
    }

    /// Removes the value at a path (back to its default). Mappings left empty by this are
    /// removed too, so no `threads:` with nothing under it is left behind.
    pub fn remove(&mut self, path: &ConfigPath) -> Result<(), EditError> {
        let document = self.document()?;
        let Some(root) = root_mapping(&document)? else { return Ok(()) };
        let mut chain: Vec<Container> = vec![Container::Map(root)];
        for (depth, step) in path.0.iter().enumerate().take(path.0.len().saturating_sub(1)) {
            let child = match (chain.last().unwrap(), step) {
                (Container::Map(m), Step::Key(k)) => m.get(k.as_str()),
                (Container::List(s), Step::Index(i)) => s.get(*i),
                _ => None,
            };
            match child {
                Some(child) => chain.push(container(&child, path, depth)?),
                None => return Ok(()),
            }
        }
        let Some(last) = path.0.last() else { return Ok(()) };
        remove_step(chain.last().unwrap(), last);
        // Remove mappings left empty, from the bottom up (never the root, never list items).
        for depth in (1..chain.len()).rev() {
            let empty = matches!(&chain[depth], Container::Map(m) if m.is_empty());
            if !empty {
                break;
            }
            if let (Container::Map(_), Step::Key(_)) = (&chain[depth - 1], &path.0[depth - 1]) {
                remove_step(&chain[depth - 1], &path.0[depth - 1]);
            } else {
                break;
            }
        }
        self.text = document.to_string();
        Ok(())
    }

    /// Adds an item at the end of the list at `path`, creating the list if it is missing.
    pub fn push(&mut self, path: &ConfigPath, item: &Value) -> Result<(), EditError> {
        match self.get(path) {
            Some(Value::Array(_)) => {
                let document = self.document()?;
                let list = self.list_at(&document, path)?;
                list.push(parsed_node(item)?);
                self.text = document.to_string();
                Ok(())
            }
            // Missing, empty (`http:`) or `[]`: write the list with its one item, in block style.
            None | Some(Value::Null) => self.set(path, &Value::Array(vec![item.clone()])),
            Some(_) => Err(EditError::Shape(javabyte_gui_protocol::t!("config-not-a-list", path = path.to_string()))),
        }
    }

    /// Removes item `index` of the list at `path`. An emptied list is removed.
    pub fn remove_item(&mut self, path: &ConfigPath, index: usize) -> Result<(), EditError> {
        let document = self.document()?;
        let list = self.list_at(&document, path)?;
        if index >= list.len() {
            return Err(EditError::Shape(javabyte_gui_protocol::t!("config-no-item", path = path.to_string(), index = index)));
        }
        list.remove(index);
        let emptied = list.is_empty();
        self.text = document.to_string();
        if emptied { self.remove(path) } else { Ok(()) }
    }

    /// Moves item `from` of the list at `path` to position `to`, keeping its text as it was.
    ///
    /// Done on the text: the items of one block list share their indentation, so an item's
    /// lines can be cut and pasted elsewhere in the list unchanged. (The YAML library's own
    /// remove-and-insert re-indents a moved item wrongly when it is not the last.)
    pub fn move_item(&mut self, path: &ConfigPath, from: usize, to: usize) -> Result<(), EditError> {
        let document = self.document()?;
        let list = self.list_at(&document, path)?;
        let count = list.len();
        if from >= count || to >= count {
            return Err(EditError::Shape(javabyte_gui_protocol::t!("config-no-item", path = path.to_string(), index = from.max(to))));
        }
        if from == to {
            return Ok(());
        }
        let text = document.to_string();
        let ranges: Vec<_> = (0..count).filter_map(|i| list.get(i)).map(|item| item.byte_range()).collect();
        let first_line_start = line_start(&text, ranges[0].start as usize);
        // A list written on one line (`[a, b]`) has no lines to move: write it out again.
        let one_line = ranges.windows(2).any(|pair| line_start(&text, pair[1].start as usize) == line_start(&text, pair[0].start as usize))
            || text[first_line_start..ranges[0].start as usize].trim_start().starts_with('[');
        if one_line || ranges.len() != count {
            let Some(Value::Array(mut items)) = self.get(path) else {
                return Err(EditError::Shape(javabyte_gui_protocol::t!("config-not-a-list", path = path.to_string())));
            };
            let item = items.remove(from);
            items.insert(to, item);
            return self.set(path, &Value::Array(items));
        }
        // Item i owns its lines up to the next item's first line; the last item owns its lines
        // up to the end of its value, so comments after the list stay where they are.
        let starts: Vec<usize> = ranges.iter().map(|r| line_start(&text, r.start as usize)).collect();
        let last_end = line_end(&text, ranges[count - 1].end as usize);
        let mut chunks: Vec<String> = (0..count)
            .map(|i| {
                let end = if i + 1 < count { starts[i + 1] } else { last_end };
                let mut chunk = text[starts[i]..end].to_string();
                if !chunk.ends_with('\n') {
                    chunk.push('\n');
                }
                chunk
            })
            .collect();
        let moved = chunks.remove(from);
        chunks.insert(to, moved);
        let mut out = String::with_capacity(text.len() + 1);
        out.push_str(&text[..starts[0]]);
        for chunk in &chunks {
            out.push_str(chunk);
        }
        let rest = &text[last_end..];
        if out.ends_with('\n') && rest.starts_with('\n') && !text[..last_end].ends_with('\n') {
            out.push_str(&rest[1..]);
        } else {
            out.push_str(rest);
        }
        self.text = out;
        Ok(())
    }

    /// The list at `path`, or why there is none.
    fn list_at(&self, document: &Document, path: &ConfigPath) -> Result<Sequence, EditError> {
        let shape = || EditError::Shape(javabyte_gui_protocol::t!("config-not-a-list", path = path.to_string()));
        let root = document.as_mapping().ok_or_else(shape)?;
        let mut node = Container::Map(root);
        for (depth, step) in path.0.iter().enumerate() {
            let child = match (&node, step) {
                (Container::Map(m), Step::Key(k)) => m.get(k.as_str()),
                (Container::List(s), Step::Index(i)) => s.get(*i),
                _ => None,
            }
            .ok_or_else(shape)?;
            node = container(&child, path, depth)?;
        }
        match node {
            Container::List(s) => Ok(s),
            Container::Map(_) => Err(shape()),
        }
    }
}

/// The offset where the line holding `offset` starts.
fn line_start(text: &str, offset: usize) -> usize {
    text[..offset].rfind('\n').map_or(0, |i| i + 1)
}

/// The offset just after the end of the line holding `offset` (after its line break, if any).
/// `offset` is exclusive, so the line is the one holding the byte before it.
fn line_end(text: &str, offset: usize) -> usize {
    let from = offset.saturating_sub(1).min(text.len());
    text[from..].find('\n').map_or(text.len(), |i| from + i + 1)
}

enum Container {
    Map(Mapping),
    List(Sequence),
}

/// A node as a group of settings or a list; a single value is a shape error at `depth`.
fn container(node: &yaml_edit::YamlNode, path: &ConfigPath, depth: usize) -> Result<Container, EditError> {
    if let Some(m) = node.as_mapping() {
        Ok(Container::Map(m.clone()))
    } else if let Some(s) = node.as_sequence() {
        Ok(Container::List(s.clone()))
    } else {
        let at = ConfigPath(path.0[..=depth].to_vec());
        Err(EditError::Shape(javabyte_gui_protocol::t!("config-single-value", path = at.to_string())))
    }
}

fn remove_step(container: &Container, step: &Step) {
    match (container, step) {
        (Container::Map(m), Step::Key(k)) => {
            m.remove(k.as_str());
        }
        (Container::List(s), Step::Index(i)) => {
            s.remove(*i);
        }
        _ => {}
    }
}

/// The document's top-level mapping; `None` for an empty document. A document whose top level
/// is a list or a single value is not a config.
fn root_mapping(document: &Document) -> Result<Option<Mapping>, EditError> {
    if let Some(m) = document.as_mapping() {
        // `{}` (an empty document) is replaced as a whole, so its flow style does not spread.
        if m.is_empty() { Ok(None) } else { Ok(Some(m)) }
    } else if document.as_sequence().is_some() || document.as_scalar().is_some_and(|s| !s.as_string().is_empty()) {
        Err(EditError::Shape(javabyte_gui_protocol::t!("config-not-a-mapping")))
    } else {
        Ok(None)
    }
}

/// Puts `value` at `steps` inside `target`, creating mappings on the way.
fn insert_json(target: &mut Value, steps: &[Step], value: Value) {
    match steps.split_first() {
        None => *target = value,
        Some((Step::Key(k), rest)) => {
            if !target.is_object() {
                *target = Value::Object(Map::new());
            }
            let child = target.as_object_mut().unwrap().entry(k.clone()).or_insert(Value::Null);
            insert_json(child, rest, value);
        }
        Some((Step::Index(_), rest)) => {
            // Lists are never created by a path; an index here means the value goes first.
            let mut item = Value::Null;
            insert_json(&mut item, rest, value);
            *target = Value::Array(vec![item]);
        }
    }
}

/// A value as a node that can be spliced into the document: rendered as YAML text and parsed,
/// so that new lists and mappings come out in block style.
fn parsed_node(value: &Value) -> Result<yaml_edit::YamlNode, EditError> {
    let text = render_document(value);
    let document = Document::from_str(&text).map_err(|e| EditError::Shape(e.to_string()))?;
    if let Some(m) = document.as_mapping() {
        Ok(yaml_edit::YamlNode::Mapping(m))
    } else if let Some(s) = document.as_sequence() {
        Ok(yaml_edit::YamlNode::Sequence(s))
    } else if let Some(s) = document.as_scalar() {
        Ok(yaml_edit::YamlNode::Scalar(s))
    } else {
        Err(EditError::Shape(javabyte_gui_protocol::t!("config-wrong-shape", path = value.to_string())))
    }
}

/// A mapping as JSON.
fn mapping_json(m: &Mapping) -> Value {
    let mut out = Map::new();
    for (key, value) in m.iter() {
        let key = key.as_scalar().map(|s| s.as_string()).unwrap_or_else(|| key.to_string());
        out.insert(key, node_json(&value));
    }
    Value::Object(out)
}

/// A list as JSON.
fn sequence_json(s: &Sequence) -> Value {
    Value::Array(s.values().map(|v| node_json(&v)).collect())
}

/// Any node as JSON.
fn node_json(node: &yaml_edit::YamlNode) -> Value {
    if let Some(m) = node.as_mapping() {
        mapping_json(m)
    } else if let Some(s) = node.as_sequence() {
        sequence_json(s)
    } else if let Some(s) = node.as_scalar() {
        scalar_json(s)
    } else {
        Value::String(node.to_string())
    }
}

/// A scalar's value: quoted ones are text; plain ones are read as YAML 1.2's core schema does.
fn scalar_json(s: &yaml_edit::Scalar) -> Value {
    let text = s.as_string();
    if s.is_quoted() || text.contains('\n') {
        return Value::String(text);
    }
    let trimmed = text.trim();
    match trimmed {
        "" | "~" | "null" | "Null" | "NULL" => return Value::Null, // not-ui-text: YAML words
        "true" | "True" | "TRUE" => return Value::Bool(true), // not-ui-text: YAML words
        "false" | "False" | "FALSE" => return Value::Bool(false), // not-ui-text: YAML words
        _ => {}
    }
    let digits = trimmed.strip_prefix(['-', '+']).unwrap_or(trimmed);
    if !digits.is_empty() && digits.chars().all(|c| c.is_ascii_digit())
        && let Ok(n) = trimmed.parse::<i64>() {
            return Value::Number(n.into());
        }
    if trimmed.chars().any(|c| c.is_ascii_digit())
        && trimmed.chars().all(|c| c.is_ascii_digit() || matches!(c, '.' | 'e' | 'E' | '-' | '+'))
        && let Some(n) = trimmed.parse::<f64>().ok().and_then(Number::from_f64) {
            return Value::Number(n);
        }
    Value::String(text)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const INIT: &str = "# JavaByte config for a.jar\n# Run with: javabyte run -c <this file>\nsample: /tmp/a.jar\n\nentryPoints:\n  - type: main\n    class: a.Main\n    args: []\n  # Uncomment to also run every static initialiser:\n  # - type: clinit-sweep\n\nlimits:\n  timeoutSeconds: 120\n  maxSleeps: 40        # ends beacon loops\n\nenvironment:\n  os: windows          # or linux\n  userName: admin\n  # files:             # files the sample should find\n  #   - path: 'C:\\x'\n\nnetwork:\n  mode: respond        # or offline\n\noutput:\n  directory: javabyte-out\n";

    fn comments(text: &str) -> Vec<String> {
        text.lines().filter_map(|l| l.find('#').map(|i| l[i..].to_string())).collect()
    }

    #[test]
    fn paths_parse_and_print() {
        let path = ConfigPath::parse("network.http[2].url");
        assert_eq!(path.0, vec![Step::Key("network".into()), Step::Key("http".into()), Step::Index(2), Step::Key("url".into())]);
        assert_eq!(path.to_string(), "network.http[2].url");
        assert_eq!(path.schema_path(), "network.http[].url");
    }

    #[test]
    fn a_value_changes_in_place() {
        let mut doc = ConfigDoc::new(INIT);
        doc.set(&ConfigPath::parse("limits.maxSleeps"), &json!(10)).unwrap();
        assert!(doc.text().contains("  maxSleeps: 10        # ends beacon loops\n"), "{}", doc.text());
        assert_eq!(doc.text().lines().count(), INIT.lines().count());
        assert_eq!(comments(doc.text()), comments(INIT));
    }

    #[test]
    fn missing_groups_are_created_and_removed_again() {
        let mut doc = ConfigDoc::new(INIT);
        doc.set(&ConfigPath::parse("threads.mode"), &json!("inline")).unwrap();
        assert_eq!(doc.get(&ConfigPath::parse("threads.mode")), Some(json!("inline")));
        doc.remove(&ConfigPath::parse("threads.mode")).unwrap();
        assert_eq!(doc.get(&ConfigPath::parse("threads")), None, "an emptied group goes too:\n{}", doc.text());
        assert_eq!(comments(doc.text()), comments(INIT));
    }

    #[test]
    fn keys_with_dots_are_one_key() {
        let mut doc = ConfigDoc::new(INIT);
        let path = ConfigPath::parse("environment.properties").key("java.version");
        doc.set(&path, &json!("17")).unwrap();
        assert_eq!(doc.get(&ConfigPath::parse("environment.properties")), Some(json!({"java.version": "17"})));
    }

    #[test]
    fn lists_grow_shrink_and_reorder_keeping_their_text() {
        let mut doc = ConfigDoc::new(INIT);
        let http = ConfigPath::parse("network.http");
        doc.push(&http, &json!({"url": "a\\.example/gate", "body": "OK"})).unwrap();
        doc.push(&http, &json!({"url": "b", "status": 404})).unwrap();
        doc.push(&http, &json!({"url": "c"})).unwrap();
        assert!(doc.text().contains("  http:\n    - url: "), "block style:\n{}", doc.text());
        doc.move_item(&http, 2, 0).unwrap();
        let urls: Vec<_> = (0..3).map(|i| doc.get(&http.clone().index(i).key("url")).unwrap()).collect();
        assert_eq!(urls, vec![json!("c"), json!("a\\.example/gate"), json!("b")]);
        doc.set(&http.clone().index(1).key("status"), &json!(500)).unwrap();
        assert_eq!(doc.get(&http.clone().index(1)), Some(json!({"url": "a\\.example/gate", "body": "OK", "status": 500})));
        for _ in 0..3 {
            doc.remove_item(&http, 0).unwrap();
        }
        assert_eq!(doc.get(&http), None, "an emptied list goes:\n{}", doc.text());
        assert_eq!(comments(doc.text()), comments(INIT));
    }

    #[test]
    fn a_flow_list_takes_items() {
        let mut doc = ConfigDoc::new(INIT);
        let args = ConfigPath::parse("entryPoints[0].args");
        doc.set(&args, &json!(["--install", "C:\\Users\\a b", "it's"])).unwrap();
        assert_eq!(doc.get(&args), Some(json!(["--install", "C:\\Users\\a b", "it's"])));
    }

    #[test]
    fn syntax_errors_name_the_line() {
        let doc = ConfigDoc::new("limits:\n  maxSleeps: [1, 2\nnetwork:\n");
        let problems = doc.syntax_problems();
        assert!(!problems.is_empty());
        assert!(problems[0].line >= 2, "{problems:?}");
        let mut doc = doc;
        assert!(matches!(doc.set(&ConfigPath::parse("limits.maxSleeps"), &json!(3)), Err(EditError::Syntax(_))));
    }

    #[test]
    fn an_empty_document_gets_block_yaml() {
        let mut doc = ConfigDoc::new("");
        doc.set(&ConfigPath::parse("limits.maxSleeps"), &json!(5)).unwrap();
        doc.push(&ConfigPath::parse("entryPoints"), &json!({"type": "clinit-sweep"})).unwrap();
        // At the top level the YAML library writes list items without indenting them; both are valid.
        assert_eq!(doc.text(), "limits:\n  maxSleeps: 5\nentryPoints:\n- type: clinit-sweep\n");
    }
}

//! Randomised edits of configs with comments. After every edit:
//!
//! - an independent YAML reader (libyaml, through `serde_yaml`) reads exactly the values a
//!   simple model of the edits expects, so nothing was written that reads differently;
//! - the config's own reader agrees;
//! - every comment line of the original is still there, in order.
//!
//! Values include strings that need quoting: Windows paths, `#`, `: `, quotes, `yes`, numbers
//! as text, non-ASCII, line breaks and control characters.

use javabyte_gui_backend::config::{ConfigDoc, ConfigPath, Step};
use serde_json::{Map, Value, json};

/// Real `javabyte init` output, and a hand-written config in other styles.
const BASES: &[&str] = &[
    "# JavaByte config for http-rat.jar\n# Run with: javabyte run -c <this file>. Every option: javabyte defaults\nsample: /tmp/http-rat.jar\n\nentryPoints:\n  - type: main\n    class: dev.javabyte.guest.sim.HttpRat\n    args: []\n  # Uncomment to also run every static initialiser (harvests decrypted strings):\n  # - type: clinit-sweep\n\nlimits:\n  timeoutSeconds: 120\n  maxSleeps: 40        # ends beacon loops\n\nenvironment:\n  os: windows          # or linux\n  userName: admin\n  # files:             # files the sample should find\n  #   - path: 'C:\\Users\\admin\\AppData\\Roaming\\config.dat'\n  #     text: 'example'\n\nnetwork:\n  mode: respond        # or offline\n  # http:              # canned answers; first matching rule wins\n  #   - url: 'example\\.com/gate'\n  #     body: 'OK'\n\n# hooks:               # force a result, e.g. to pass an environment check\n#   - method: 'com/example/Checks.isSandbox()Z'\n#     action: return\n#     value: false\n\noutput:\n  directory: javabyte-out\n",
    "# Settings for the second stage\nsample: \"C:\\\\samples\\\\stage two.jar\"   # quoted on purpose\nlimits: {timeoutSeconds: 30, maxSleeps: 5}\nenvironment:\n  # who the sample thinks it is\n  userName: 'o''brien'\n  properties:\n    java.version: \"17\"\n# the end\n",
];

const STRINGS: &[&str] = &[
    "inline", "C:\\Users\\admin\\AppData\\Roaming\\x.dat", "a: b", "#hash", "it's", "\"quoted\"", "yes", "off",
    "010", "1e3", "null", "", " leading", "trailing ", "é ü 中 🙂", "line one\nline two", "bell\u{7}", "-dash",
    "[x]", "{y}", "a,b", "com/example/Checks.isSandbox()Z", "https://example.com/gate?id=1&x=2", "~",
];

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
    fn string(&mut self) -> String {
        STRINGS[self.below(STRINGS.len())].to_string()
    }
    fn value(&mut self) -> Value {
        match self.below(4) {
            0 => json!(self.below(100_000)),
            1 => json!(self.below(2) == 0),
            _ => json!(self.string()),
        }
    }
}

/// Places a scalar may be set, as (path, whether its parent is a map whose keys are free).
const SCALAR_PATHS: &[&str] = &[
    "limits.maxSleeps", "limits.maxTasks", "limits.timeoutSeconds", "threads.mode", "threads.periodicRuns",
    "environment.userName", "environment.hostName", "environment.samplePath", "network.mode", "trace.hostCalls",
    "output.directory", "policy.onBlocked",
];
const LISTS: &[&str] = &["network.http", "processes", "hooks", "classpath"];

// --- the model: the same edits on plain JSON ---

fn model_get<'a>(root: &'a Value, path: &ConfigPath) -> Option<&'a Value> {
    let mut node = root;
    for step in &path.0 {
        node = match (step, node) {
            (Step::Key(k), Value::Object(m)) => m.get(k)?,
            (Step::Index(i), Value::Array(a)) => a.get(*i)?,
            _ => return None,
        };
    }
    Some(node)
}

fn model_set(root: &mut Value, steps: &[Step], value: Value) {
    match steps.split_first() {
        None => *root = value,
        Some((Step::Key(k), rest)) => {
            if !root.is_object() {
                *root = Value::Object(Map::new());
            }
            let child = root.as_object_mut().unwrap().entry(k.clone()).or_insert(Value::Null);
            model_set(child, rest, value);
        }
        Some((Step::Index(i), rest)) => model_set(&mut root.as_array_mut().unwrap()[*i], rest, value),
    }
}

/// Removes, then removes mappings left empty above (never list items, never the root).
fn model_remove(root: &mut Value, steps: &[Step]) {
    fn inner(node: &mut Value, steps: &[Step]) {
        match steps {
            [] => {}
            [Step::Key(k)] => {
                if let Some(m) = node.as_object_mut() {
                    m.shift_remove(k);
                }
            }
            [Step::Index(i)] => {
                if let Some(a) = node.as_array_mut() {
                    if *i < a.len() {
                        a.remove(*i);
                    }
                }
            }
            [Step::Key(k), rest @ ..] => {
                if let Some(child) = node.as_object_mut().and_then(|m| m.get_mut(k)) {
                    inner(child, rest);
                    if child.as_object().is_some_and(Map::is_empty) {
                        node.as_object_mut().unwrap().shift_remove(k);
                    }
                }
            }
            [Step::Index(i), rest @ ..] => {
                if let Some(child) = node.as_array_mut().and_then(|a| a.get_mut(*i)) {
                    inner(child, rest);
                }
            }
        }
    }
    inner(root, steps);
}

fn comment_lines(text: &str) -> Vec<String> {
    text.lines().filter(|l| l.trim_start().starts_with('#')).map(|l| l.trim().to_string()).collect()
}

/// What libyaml reads, as JSON.
fn read_independently(text: &str) -> Result<Value, String> {
    let value: serde_yaml::Value = serde_yaml::from_str(text).map_err(|e| format!("libyaml cannot read:\n{text}\n{e}"))?;
    let json = serde_json::to_value(value).unwrap();
    Ok(if json.is_null() { Value::Object(Map::new()) } else { json })
}

/// Values compared regardless of key order (the YAML keeps the order keys were added in, and
/// the model may differ when a key was removed and added again).
fn same(a: &Value, b: &Value) -> bool {
    match (a, b) {
        (Value::Object(x), Value::Object(y)) => x.len() == y.len() && x.iter().all(|(k, v)| y.get(k).is_some_and(|w| same(v, w))),
        (Value::Array(x), Value::Array(y)) => x.len() == y.len() && x.iter().zip(y).all(|(v, w)| same(v, w)),
        _ => a == b,
    }
}

#[test]
fn random_edits_keep_comments_and_read_back_exactly() {
    // A fixed seed keeps CI repeatable; set CONFIG_EDITS_SEED to try others.
    let seed = std::env::var("CONFIG_EDITS_SEED").ok().and_then(|s| s.parse().ok()).unwrap_or(0x5eed_1234_abcd_0001u64);
    let mut rng = Rng(seed | 1);
    let mut edits = 0;
    for round in 0..240 {
        let base = BASES[round % BASES.len()];
        let mut doc = ConfigDoc::new(base);
        let mut model = read_independently(base).unwrap();
        let mut history: Vec<String> = Vec::new();
        let original_comments = comment_lines(base);
        for _ in 0..30 {
            let description;
            match rng.below(9) {
                0..=2 => {
                    let path = ConfigPath::parse(SCALAR_PATHS[rng.below(SCALAR_PATHS.len())]);
                    let value = rng.value();
                    description = format!("set {path} = {value}");
                    doc.set(&path, &value).unwrap();
                    model_set(&mut model, &path.0, value);
                }
                3 => {
                    let path = ConfigPath::parse(SCALAR_PATHS[rng.below(SCALAR_PATHS.len())]);
                    description = format!("remove {path}");
                    doc.remove(&path).unwrap();
                    model_remove(&mut model, &path.0);
                }
                4 => {
                    // A free key in a map, sometimes with dots.
                    let key = ["java.version", "user.home", "os.name", "custom"][rng.below(4)].to_string();
                    let path = ConfigPath::parse("environment.properties").key(key);
                    let value = json!(rng.string());
                    description = format!("set {path:?} = {value}");
                    doc.set(&path, &value).unwrap();
                    model_set(&mut model, &path.0, value);
                }
                5 => {
                    let list = ConfigPath::parse(LISTS[rng.below(LISTS.len())]);
                    let item = if list.to_string() == "classpath" {
                        json!(rng.string())
                    } else {
                        json!({"url": rng.string(), "status": rng.below(600), "body": rng.string()})
                    };
                    description = format!("push {list} {item}");
                    doc.push(&list, &item).unwrap();
                    let mut items = model_get(&model, &list).and_then(Value::as_array).cloned().unwrap_or_default();
                    items.push(item);
                    model_set(&mut model, &list.0, Value::Array(items));
                }
                6 => {
                    let list = ConfigPath::parse(LISTS[rng.below(LISTS.len())]);
                    let len = model_get(&model, &list).and_then(Value::as_array).map_or(0, Vec::len);
                    if len == 0 {
                        continue;
                    }
                    let (from, to) = (rng.below(len), rng.below(len));
                    description = format!("move {list} {from} -> {to}");
                    doc.move_item(&list, from, to).unwrap();
                    let mut items = model_get(&model, &list).unwrap().as_array().unwrap().clone();
                    let item = items.remove(from);
                    items.insert(to, item);
                    model_set(&mut model, &list.0, Value::Array(items));
                }
                7 => {
                    let list = ConfigPath::parse(LISTS[rng.below(LISTS.len())]);
                    let len = model_get(&model, &list).and_then(Value::as_array).map_or(0, Vec::len);
                    if len == 0 {
                        continue;
                    }
                    let index = rng.below(len);
                    description = format!("remove item {list}[{index}]");
                    doc.remove_item(&list, index).unwrap();
                    if len == 1 {
                        model_remove(&mut model, &list.0);
                    } else {
                        model_remove(&mut model, &list.clone().index(index).0);
                    }
                }
                _ => {
                    // Change one field of an existing HTTP rule.
                    let list = ConfigPath::parse("network.http");
                    let len = model_get(&model, &list).and_then(Value::as_array).map_or(0, Vec::len);
                    if len == 0 {
                        continue;
                    }
                    let path = list.index(rng.below(len)).key(["url", "body", "status"][rng.below(3)]);
                    let value = rng.value();
                    description = format!("set {path} = {value}");
                    doc.set(&path, &value).unwrap();
                    model_set(&mut model, &path.0, value);
                }
            }
            edits += 1;
            history.push(description.clone());
            let read = read_independently(doc.text()).unwrap_or_else(|e| panic!("round {round}, edits:\n{}\n{e}", history.join("\n")));
            assert!(same(&read, &model), "after {description}, libyaml reads\n{read:#}\nbut the edits made\n{model:#}\nfrom:\n{}", doc.text());
            let own = doc.to_json().unwrap();
            assert!(same(&own, &read), "after {description}, the config's own reader differs from libyaml:\n{own:#}\n{read:#}");
            assert_eq!(comment_lines(doc.text()), original_comments, "after {description}, comments changed:\n{}", doc.text());
        }
    }
    assert!(edits > 5_000, "only {edits} edits were tried");
}

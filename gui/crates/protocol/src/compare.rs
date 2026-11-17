//! Two runs side by side: how each ended, events per category, and per indicator what was
//! added or removed. Runs are repeatable (seeded randomness, fixed clock when set), so a
//! difference comes from what changed between them: the sample or its settings.

use crate::report::{Report, describe_outcome};
use crate::views;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet};

/// One of the two runs. Untrusted text: names and values come from the samples.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Side {
    pub sample_name: String,
    pub sha256: String,
    pub folder: String,
    /// How each part ended, in words.
    pub endings: Vec<(String, String)>,
    pub events: u64,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct CategoryCount {
    pub category: String,
    pub a: u64,
    pub b: u64,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct IndicatorDiff {
    pub id: String,
    pub title: String,
    /// In the second run, not the first.
    pub added: Vec<String>,
    /// In the first run, not the second.
    pub removed: Vec<String>,
    pub same: usize,
}

/// A setting whose effective value differs. Values as one-line JSON; `None` when not set.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SettingDiff {
    pub path: String,
    pub a: Option<String>,
    pub b: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Comparison {
    pub a: Side,
    pub b: Side,
    pub same_sample: bool,
    pub categories: Vec<CategoryCount>,
    pub indicators: Vec<IndicatorDiff>,
    pub settings: Vec<SettingDiff>,
}

impl Comparison {
    /// Whether nothing the comparison looks at differs.
    pub fn identical(&self) -> bool {
        self.settings.is_empty()
            && self.categories.iter().all(|c| c.a == c.b)
            && self.indicators.iter().all(|i| i.added.is_empty() && i.removed.is_empty())
            && self.a.endings == self.b.endings
    }
}

fn side(report: &Report, name: &str, folder: &str) -> Side {
    Side {
        sample_name: name.to_string(),
        sha256: report.sample.sha256.clone(),
        folder: folder.to_string(),
        endings: report.runs.iter().map(|r| (r.entry.clone(), describe_outcome(&r.outcome))).collect(),
        events: report.summary.event_counts.values().sum(),
    }
}

/// Settings that never mean a different analysis: where results went, and what the command
/// printed.
const IGNORED_SETTINGS: &[&str] = &["output.directory", "output.console"];

pub fn compare(a: (&Report, &str, &str), b: (&Report, &str, &str)) -> Comparison {
    let (ra, rb) = (a.0, b.0);
    let mut categories: BTreeSet<&String> = ra.summary.event_counts.keys().collect();
    categories.extend(rb.summary.event_counts.keys());
    let categories = categories
        .into_iter()
        .map(|c| CategoryCount {
            category: c.clone(),
            a: ra.summary.event_counts.get(c).copied().unwrap_or(0),
            b: rb.summary.event_counts.get(c).copied().unwrap_or(0),
        })
        .collect();

    // The indicator lists as the report screen shows them, compared as sets.
    let va = views::report_view(ra, a.1, a.2);
    let vb = views::report_view(rb, b.1, b.2);
    let mut ids: Vec<(String, String)> = va.sections.iter().map(|s| (s.id.clone(), s.title.clone())).collect();
    for s in &vb.sections {
        if !ids.iter().any(|(id, _)| *id == s.id) {
            ids.push((s.id.clone(), s.title.clone()));
        }
    }
    let items = |v: &views::ReportView, id: &str| -> BTreeSet<String> {
        v.sections.iter().find(|s| s.id == id).map(|s| s.items.iter().cloned().collect()).unwrap_or_default()
    };
    let indicators = ids
        .into_iter()
        .map(|(id, title)| {
            let (sa, sb) = (items(&va, &id), items(&vb, &id));
            IndicatorDiff {
                added: sb.difference(&sa).cloned().collect(),
                removed: sa.difference(&sb).cloned().collect(),
                same: sa.intersection(&sb).count(),
                id,
                title,
            }
        })
        .collect();

    let (fa, fb) = (flatten(&ra.config), flatten(&rb.config));
    let mut paths: BTreeSet<&String> = fa.keys().collect();
    paths.extend(fb.keys());
    let settings = paths
        .into_iter()
        .filter(|p| !IGNORED_SETTINGS.contains(&p.as_str()))
        .filter(|p| fa.get(*p) != fb.get(*p))
        .map(|p| SettingDiff { path: p.clone(), a: fa.get(p).cloned(), b: fb.get(p).cloned() })
        .collect();

    Comparison {
        same_sample: ra.sample.sha256 == rb.sample.sha256,
        a: side(ra, a.1, a.2),
        b: side(rb, b.1, b.2),
        categories,
        indicators,
        settings,
    }
}

/// Settings by path, down to single values; lists are compared whole.
fn flatten(config: &Value) -> BTreeMap<String, String> {
    fn walk(prefix: &str, value: &Value, out: &mut BTreeMap<String, String>) {
        match value {
            Value::Object(map) if !map.is_empty() => {
                for (k, v) in map {
                    walk(&if prefix.is_empty() { k.clone() } else { format!("{prefix}.{k}") }, v, out);
                }
            }
            Value::Null => {}
            Value::Array(a) if a.is_empty() => {}
            Value::Object(_) => {}
            other => {
                out.insert(prefix.to_string(), other.to_string());
            }
        }
    }
    let mut out = BTreeMap::new();
    walk("", config, &mut out);
    out
}

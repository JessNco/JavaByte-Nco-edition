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
    /// The sample's file name.
    pub sample_name: String,
    /// SHA-256 of the sample: equal for both sides when the same sample ran twice.
    pub sha256: String,
    /// The run's folder.
    pub folder: String,
    /// How each part ended, in words.
    pub endings: Vec<(String, String)>,
    /// Events recorded, in all categories.
    pub events: u64,
}

/// One category's event count in each run.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct CategoryCount {
    /// The category, such as `net`.
    pub category: String,
    /// Events in the first run.
    pub a: u64,
    /// Events in the second run.
    pub b: u64,
}

/// One indicator list compared: what is new, what is gone, how much is the same.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct IndicatorDiff {
    /// The indicator's id, as in `IndicatorSection::id`.
    pub id: String,
    /// The indicator's heading.
    pub title: String,
    /// In the second run, not the first.
    pub added: Vec<String>,
    /// In the first run, not the second.
    pub removed: Vec<String>,
    /// Entries in both runs.
    pub same: usize,
}

/// A setting whose effective value differs. Values as one-line JSON; `None` when not set.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SettingDiff {
    /// The setting's path, such as `limits.maxSleeps`.
    pub path: String,
    /// Its value in the first run.
    pub a: Option<String>,
    /// Its value in the second run.
    pub b: Option<String>,
}

/// The whole comparison.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Comparison {
    /// The first run, usually the earlier.
    pub a: Side,
    /// The second run.
    pub b: Side,
    /// Whether both ran the same sample (same SHA-256).
    pub same_sample: bool,
    /// Every category either run recorded.
    pub categories: Vec<CategoryCount>,
    /// Every indicator either run listed.
    pub indicators: Vec<IndicatorDiff>,
    /// Settings whose effective values differ.
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

/// One run's summary for the comparison.
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

/// Compares two runs, each given as (report, sample name, folder).
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

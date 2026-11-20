//! The report's Limits and gaps tab: why each part of the run ended, where the analysis could
//! not imitate the real thing, which Java features it refused, and which events were not kept.
//! Each comes with what it means and, where a setting would help, which one (the follow-up
//! table in AGENTS.md).

use crate::report::{Report, describe_outcome};
use crate::t;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// What to try next. `setting` is a schema path the Settings screen can show, such as
/// `limits.timeoutSeconds`.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct NextStep {
    /// What to try, in a sentence.
    pub text: String,
    /// The setting that helps, if one does.
    pub setting: Option<String>,
}

/// How one part of the run (an entry point, queued tasks, shutdown code) ended.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Ending {
    /// The part, such as `a.B.main` or `(shutdown hooks)`. Untrusted.
    pub entry: String,
    /// The tool's outcome word.
    pub outcome: String,
    /// The outcome in plain words.
    pub meaning: String,
    /// The tool's own detail, such as an exception and where it was thrown. Untrusted.
    pub detail: Option<String>,
    /// What to try next, when there is something.
    pub next: Option<NextStep>,
}

/// Gaps of one kind.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct GapGroup {
    /// The tool's word for it, such as `missing-class`.
    pub kind: String,
    /// Its title in plain words.
    pub title: String,
    /// What it means for the results.
    pub meaning: String,
    /// What to try next, when there is something.
    pub next: Option<NextStep>,
    /// How many there were.
    pub count: u64,
    /// The first few, by event number, so people can find them in the event list. Untrusted.
    pub examples: Vec<(u64, String)>,
}

/// One Java feature the analysis refused, and how often.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Refusal {
    /// The member, such as `java/awt/Robot.<init>()V`. Untrusted.
    pub member: String,
    /// `blocked` (refused), `phantom-call` (answered with a harmless value), or
    /// `blocked-field-write`.
    pub action: String,
    /// How many times.
    pub count: u64,
    /// The number of the first such event.
    pub first_seq: u64,
}

/// A category whose events were not all kept.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Dropped {
    /// The category, such as `string`.
    pub category: String,
    /// Events counted but not kept.
    pub count: u64,
}

/// Everything the Limits and gaps tab shows.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct LimitsView {
    /// How each part of the run ended.
    pub endings: Vec<Ending>,
    /// Gaps grouped by kind, most frequent first.
    pub gaps: Vec<GapGroup>,
    /// Refused Java features, in the order they first happened.
    pub refusals: Vec<Refusal>,
    /// Categories with events not kept.
    pub dropped: Vec<Dropped>,
    /// What to do about dropped events, if any were dropped.
    pub dropped_next: Option<NextStep>,
}

/// Gap kinds with their own words in the resource files. Others use the general words.
pub const KNOWN_GAPS: &[&str] = &[
    "missing-class", "invalid-bytecode", "unparseable-class", "unsupported", "stub-exception",
    "phantom-superclass", "phantom-superclass-call", "shell-failed", "thread-not-started",
    "native-bridge-unavailable", "native-bridge-load-failed", "native-bridge-call-failed",
    "native-bridge-not-an-array", "nested-native-call", "unsupported-jni-callback", "finish-failed", "plugin-close-failed",
];

/// Examples kept per gap kind.
const EXAMPLES: usize = 5;

/// A next step from its message, with the setting that helps.
fn step(id: &str, setting: Option<&str>) -> Option<NextStep> {
    Some(NextStep { text: crate::i18n::tr(id, None), setting: setting.map(str::to_string) })
}

/// What to try after a run ends a given way.
fn next_for_outcome(outcome: &str) -> Option<NextStep> {
    match outcome {
        "timeout" => step("next-timeout", Some("limits.timeoutSeconds")),
        "instruction-limit" => step("next-instructions", Some("limits.maxInstructions")),
        "sleep-budget" => step("next-sleeps", Some("network.http")),
        "silent-loop" => step("next-silent-loop", Some("limits.maxSilentInstructions")),
        "blocked" => step("next-blocked-wait", Some("network.http")),
        "no-entry-point" => step("next-entry", Some("entryPoints")),
        "policy" => step("next-policy-abort", Some("policy.onBlocked")),
        "internal-error" => step("next-report-fault", None),
        _ => None,
    }
}

/// What to try about a kind of gap.
fn next_for_gap(kind: &str) -> Option<NextStep> {
    match kind {
        "missing-class" | "phantom-superclass" | "phantom-superclass-call" => step("next-classpath", Some("classpath")),
        "native-bridge-unavailable" | "native-bridge-load-failed" => step("next-native-bridge-python", Some("plugins.native-bridge.python")),
        "thread-not-started" => step("next-tasks", Some("limits.maxTasks")),
        "unsupported" | "stub-exception" | "shell-failed" | "finish-failed" | "plugin-close-failed" | "native-bridge-call-failed"
        | "native-bridge-not-an-array"
        | "nested-native-call" | "unsupported-jni-callback" => step("next-report-fault", None),
        _ => None,
    }
}

/// Builds the tab's data from a report.
pub fn limits_view(report: &Report) -> LimitsView {
    let endings = report
        .runs
        .iter()
        .map(|run| Ending {
            entry: run.entry.clone(),
            outcome: run.outcome.clone(),
            meaning: describe_outcome(&run.outcome),
            detail: run.detail.clone(),
            next: next_for_outcome(&run.outcome),
        })
        .collect();

    let mut gaps: BTreeMap<String, GapGroup> = BTreeMap::new();
    let mut refusals: BTreeMap<(String, String), Refusal> = BTreeMap::new();
    for event in &report.events {
        match event.category.as_str() {
            "gap" => {
                let kind = event.action.clone();
                let group = gaps.entry(kind.clone()).or_insert_with(|| {
                    let known = KNOWN_GAPS.contains(&kind.as_str());
                    GapGroup {
                        title: if known { crate::i18n::tr(&format!("gap-{kind}"), None) } else { t!("gap-other", kind = kind.clone()) },
                        meaning: if known { crate::i18n::tr(&format!("gap-{kind}-meaning"), None) } else { t!("gap-other-meaning") },
                        next: next_for_gap(&kind),
                        kind,
                        count: 0,
                        examples: Vec::new(),
                    }
                });
                group.count += 1;
                if group.examples.len() < EXAMPLES {
                    group.examples.push((event.seq, event.details()));
                }
            }
            "policy" if matches!(event.action.as_str(), "blocked" | "phantom-call" | "blocked-field-write") => {
                let member = ["member", "method", "field", "name"]
                    .iter()
                    .find_map(|k| event.data.get(*k).and_then(|v| v.as_str()))
                    .map_or_else(|| event.details(), str::to_string);
                let entry = refusals.entry((member.clone(), event.action.clone())).or_insert(Refusal {
                    member,
                    action: event.action.clone(),
                    count: 0,
                    first_seq: event.seq,
                });
                entry.count += 1;
            }
            _ => {}
        }
    }
    // The summary counts every gap, including any not kept as events.
    let mut gaps: Vec<GapGroup> = gaps.into_values().collect();
    gaps.sort_by(|a, b| b.count.cmp(&a.count).then_with(|| a.kind.cmp(&b.kind)));
    let mut refusals: Vec<Refusal> = refusals.into_values().collect();
    refusals.sort_by_key(|r| r.first_seq);

    let dropped: Vec<Dropped> =
        report.summary.events_dropped.iter().map(|(category, count)| Dropped { category: category.clone(), count: *count }).collect();
    let dropped_next = (!dropped.is_empty()).then(|| step("next-max-events", Some("limits.maxEventsPerCategory"))).flatten();
    LimitsView { endings, gaps, refusals, dropped, dropped_next }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_known_gap_and_next_step_has_words() {
        for kind in KNOWN_GAPS {
            assert!(crate::i18n::has(&format!("gap-{kind}")), "no title for gap {kind}");
            assert!(crate::i18n::has(&format!("gap-{kind}-meaning")), "no meaning for gap {kind}");
            next_for_gap(kind);
        }
        for outcome in ["timeout", "instruction-limit", "sleep-budget", "silent-loop", "blocked", "no-entry-point", "policy", "internal-error"] {
            assert!(next_for_outcome(outcome).is_some(), "{outcome} has no next step");
        }
    }
}

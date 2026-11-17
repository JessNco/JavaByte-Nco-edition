//! A pretend analysis for the trial's "running" screen.
//!
//! It advances by elapsed time rather than on its own thread, so the same code runs natively
//! and in a browser. Phase 1 replaces it with the real emulator's event stream; the screen
//! code does not change, because it only ever sees [`RunStatus`].

use crate::events::EventSource;
use crate::synthetic::SyntheticEvents;
use javabyte_gui_protocol::{EventRow, RunState, RunStatus};

/// Events produced per second of the pretend run: fast enough to stress the screen.
const EVENTS_PER_SECOND: u64 = 4_000;
const INSTRUCTIONS_PER_EVENT: u64 = 25_000;
const CATEGORIES: &[&str] = &["process", "net", "file", "class", "crypto", "string", "reflect", "thread", "console", "policy", "gap"];
const NOTABLE: &[&str] = &["process", "net", "class"];

pub struct Simulation {
    events: SyntheticEvents,
    duration_ms: u64,
    started_at_ms: Option<f64>,
    stopped: Option<(u64, &'static str)>,
}

impl Simulation {
    /// A pretend run that ends by itself after `duration_ms`.
    pub fn new(duration_ms: u64) -> Self {
        Self { events: SyntheticEvents::new(u64::MAX), duration_ms, started_at_ms: None, stopped: None }
    }

    pub fn start(&mut self, now_ms: f64) {
        self.started_at_ms = Some(now_ms);
        self.stopped = None;
    }

    /// Ends the run as the Stop button would, keeping what was gathered.
    pub fn stop(&mut self, now_ms: f64) {
        if self.stopped.is_none() {
            if let Some(start) = self.started_at_ms {
                let elapsed = ((now_ms - start).max(0.0) as u64).min(self.duration_ms);
                self.stopped = Some((elapsed, "stopped"));
            }
        }
    }

    pub fn status(&mut self, now_ms: f64) -> RunStatus {
        let Some(start) = self.started_at_ms else {
            return RunStatus::default();
        };
        let elapsed = (now_ms - start).max(0.0) as u64;
        if self.stopped.is_none() && elapsed >= self.duration_ms {
            self.stopped = Some((self.duration_ms, "completed"));
        }
        let (elapsed, outcome) = match self.stopped {
            Some((at, outcome)) => (at, Some(outcome.to_string())),
            None => (elapsed, None),
        };
        let produced = elapsed * EVENTS_PER_SECOND / 1000;
        // Counting every event would be slow for long runs; a sample of the stream gives the
        // proportions, which is all a progress display needs.
        let sample = produced.min(4_000);
        let mut counts = vec![0u64; CATEGORIES.len()];
        for i in 0..sample {
            let category = self.events.category(i * produced.max(1) / sample.max(1));
            if let Some(slot) = CATEGORIES.iter().position(|c| *c == category) {
                counts[slot] += 1;
            }
        }
        let by_category = CATEGORIES
            .iter()
            .zip(counts)
            .map(|(name, count)| (name.to_string(), if sample == 0 { 0 } else { count * produced / sample }))
            .collect();
        RunStatus {
            state: if outcome.is_some() { RunState::Finished } else { RunState::Running },
            elapsed_ms: elapsed,
            instructions: produced * INSTRUCTIONS_PER_EVENT,
            events: produced,
            by_category,
            notable: self.notable(produced),
            ended: outcome.as_deref().map(|o| javabyte_gui_protocol::report::describe_outcome(o).to_string()),
            report_ready: outcome.is_some(),
            outcome,
            sample_name: "example.jar".into(),
            entry: "com.example.Main.main".into(),
        }
    }

    fn notable(&self, produced: u64) -> Vec<EventRow> {
        let mut found = Vec::new();
        let mut index = produced;
        while index > 0 && found.len() < 5 && produced - index < 2_000 {
            index -= 1;
            if NOTABLE.contains(&self.events.category(index)) {
                found.push(self.events.row(index));
            }
        }
        found
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn runs_then_completes_or_stops() {
        let mut run = Simulation::new(10_000);
        assert_eq!(run.status(0.0).state, RunState::Idle);
        run.start(1_000.0);
        let midway = run.status(6_000.0);
        assert_eq!(midway.state, RunState::Running);
        assert_eq!(midway.events, 20_000);
        assert!(midway.by_category.iter().map(|(_, n)| n).sum::<u64>() > 15_000);
        assert!(!midway.notable.is_empty());
        let done = run.status(20_000.0);
        assert_eq!(done.outcome.as_deref(), Some("completed"));
        assert_eq!(done.elapsed_ms, 10_000);

        run.start(0.0);
        run.stop(2_500.0);
        let stopped = run.status(9_000.0);
        assert_eq!(stopped.outcome.as_deref(), Some("stopped"));
        assert_eq!(stopped.elapsed_ms, 2_500);
    }
}

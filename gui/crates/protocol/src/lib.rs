//! Types exchanged between the GUI's screens (WebAssembly) and its backend (native Rust).
//!
//! This is the only place the GUI defines the shape of data it passes around, so both sides
//! always agree. It has no dependencies beyond `serde`, so it compiles for both targets.

mod display;

pub use display::{DisplayText, display_text};
use serde::{Deserialize, Serialize};

/// One event, reduced to what a table row shows.
///
/// The emulator's events carry arbitrary key-value data. A row keeps the fixed fields and a
/// one-line rendering of the data; the full event stays in the backend.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct EventRow {
    /// Position in the report, starting at 0. Stable across filters.
    pub seq: u64,
    pub category: String,
    pub action: String,
    /// `key=value` pairs from the event's data, as the emulator's text log writes them.
    /// Untrusted: it comes from the sample. Always pass it through [`display_text`].
    pub details: String,
    /// Where in the sample the event happened (`class.method:line`), if known. Untrusted.
    pub caller: Option<String>,
}

/// A request for a slice of the events, optionally filtered.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct PageRequest {
    /// Index of the first row wanted, counted within the filtered result.
    pub offset: u64,
    /// Maximum number of rows. The backend caps it, so a screen cannot ask for everything.
    pub limit: u32,
    pub filter: EventFilter,
}

/// Which events to show. An empty filter shows all.
#[derive(Clone, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct EventFilter {
    /// Only this category, e.g. `net`.
    pub category: Option<String>,
    /// Case-insensitive text that must appear in the action, details or caller.
    pub text: Option<String>,
}

/// A slice of the (filtered) events.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Page {
    /// Number of rows that match the filter in total.
    pub total: u64,
    /// Number of events in the report, ignoring the filter.
    pub unfiltered_total: u64,
    pub offset: u64,
    pub rows: Vec<EventRow>,
}

/// Largest page the backend will return. Enough for any screen, small enough that a request
/// can never move a large report into the web view at once.
pub const MAX_PAGE_ROWS: u32 = 500;

/// State of an analysis, as the running screen shows it.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct RunStatus {
    pub state: RunState,
    pub elapsed_ms: u64,
    pub instructions: u64,
    pub events: u64,
    /// Event counts per category, in a fixed order so the screen does not reshuffle.
    pub by_category: Vec<(String, u64)>,
    /// The most recent events worth a person's attention (processes, network, files, classes),
    /// newest first.
    pub notable: Vec<EventRow>,
    /// How the run ended, in the emulator's words (`completed`, `stopped`, ...), once it has.
    pub outcome: Option<String>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum RunState {
    #[default]
    Idle,
    Running,
    Finished,
}

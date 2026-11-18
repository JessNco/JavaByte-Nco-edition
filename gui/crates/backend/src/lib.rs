#![warn(missing_docs)]
//! The GUI's backend logic, kept free of any window or web-view code so it can be tested on
//! its own and reused by any front end.
//!
//! - [`runner`] starts the command-line tool and reads what it streams back;
//! - [`session`] holds one window's state: the chosen sample, its settings, the run, the open
//!   report and the recent list;
//! - [`config`] edits settings as YAML in place, keeping comments;
//! - [`events`], [`report`] page through a report's events;
//! - `artifacts` reads and saves the files a run produced (desktop only);
//! - `setup` finds the bundled runtime, jar, bridge and Windows sandbox helper, and the
//!   preferences that choose between them (desktop only);
//! - `isolation` runs the tool with no network (Linux only);
//! - [`simulation`], [`synthetic`] stand in for a real run and real events in browser tests.
//!
//! Everything except `artifacts`, `setup` and `isolation` also compiles to WebAssembly, so the
//! screens can run in an ordinary browser for the automated accessibility tests, without the
//! desktop shell.

/// Files a run saved: listing, pages, and safe copies.
#[cfg(not(target_arch = "wasm32"))]
pub mod artifacts;
/// Settings as YAML, edited in place.
pub mod config;
/// Runs with no network, on Linux.
#[cfg(target_os = "linux")]
pub mod isolation;
/// The bundled runtime, jar, bridge and Windows sandbox helper, and the preferences that
/// choose between them.
#[cfg(not(target_arch = "wasm32"))]
pub mod setup;
/// Paging and filtering events.
pub mod events;
/// Events from a `report.json`.
pub mod report;
/// Starting the command-line tool and reading its stream.
pub mod runner;
/// One window's state between screens.
pub mod session;
/// A pretend run, for browser tests.
pub mod simulation;
/// Made-up events, for browser tests.
pub mod synthetic;

pub use events::EventStore;

//! The GUI's backend logic, kept free of any window or web-view code so it can be tested on
//! its own and reused by any front end.
//!
//! In phase 0 it serves events from a synthetic source or from a real `report.json`. The same
//! code also compiles to WebAssembly, so the screens can run in an ordinary browser for
//! automated accessibility tests without the desktop shell.

pub mod events;
pub mod report;
pub mod runner;
pub mod simulation;
pub mod synthetic;

pub use events::EventStore;

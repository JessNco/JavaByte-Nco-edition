//! `javabyte validate --json`: every problem with a config.

use serde::{Deserialize, Serialize};

/// The answer of `javabyte validate --json`.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Validation {
    /// True when the settings have no problems.
    pub valid: bool,
    /// Every problem, not just the first.
    pub problems: Vec<Problem>,
}

/// One problem in the settings.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Problem {
    /// The option concerned, such as `network.http[2].url`; empty for the whole file.
    pub key: String,
    /// What is wrong and, usually, what is allowed. From the tool, in English.
    pub message: String,
}

//! `javabyte validate --json`: every problem with a config.

use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Validation {
    pub valid: bool,
    pub problems: Vec<Problem>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Problem {
    /// The option concerned, such as `network.http[2].url`; empty for the whole file.
    pub key: String,
    pub message: String,
}

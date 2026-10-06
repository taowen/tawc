//! Connection events for the UI's live list. Never logged: they carry
//! client addresses, and the sparse-logging rule applies.

use serde::Serialize;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Serialize)]
pub struct Event {
    /// Unix milliseconds.
    pub time: u64,
    /// `login`, `logout`, `denied`, `run`, `exit`, `forward`, `info`,
    /// `warn`, or `status` (the agent's [`crate::agent::Status`] changed;
    /// `msg` is empty, poll the status).
    pub kind: &'static str,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub from: String,
    pub msg: String,
}

pub type Sink = Arc<dyn Fn(Event) + Send + Sync>;

pub fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

#[derive(Clone)]
pub struct Events {
    sink: Sink,
}

impl Events {
    pub fn new(sink: Sink) -> Self {
        Events { sink }
    }

    pub fn emit(&self, kind: &'static str, from: &str, msg: impl Into<String>) {
        (self.sink)(Event { time: now_ms(), kind, from: from.to_string(), msg: msg.into() });
    }
}

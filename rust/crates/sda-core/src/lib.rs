use serde::{Deserialize, Serialize};
use sda_protocol::TaskState;
use thiserror::Error;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum RouteDecision {
    PassThrough,
    SdaManaged,
    Defer,
    Reject,
    Redirect,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TaskDescriptor {
    pub task_id: u64,
    pub source_uid: u32,
    pub source_package: String,
    pub url: String,
    pub destination: String,
    pub mime_type: Option<String>,
    pub expected_length: Option<u64>,
    pub notification_tag: Option<String>,
    pub notification_id: i32,
    pub notification_channel: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Eligibility {
    pub supports_http_range: bool,
    pub stable_total_length: bool,
    pub ordinary_file: bool,
    pub writable_destination: bool,
    pub has_transform_semantics: bool,
}

impl Eligibility {
    pub fn route(&self) -> RouteDecision {
        if self.supports_http_range
            && self.stable_total_length
            && self.ordinary_file
            && self.writable_destination
            && !self.has_transform_semantics
        {
            RouteDecision::SdaManaged
        } else {
            RouteDecision::PassThrough
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AggregateProgress {
    pub downloaded_bytes: u64,
    pub durable_bytes: u64,
    pub total_bytes: u64,
    pub active_workers: u32,
    pub total_workers: u32,
    pub speed_bps: u64,
    pub eta_seconds: Option<u64>,
}

impl AggregateProgress {
    pub fn percent_x100(&self) -> u32 {
        if self.total_bytes == 0 {
            return 0;
        }
        let scaled = self
            .downloaded_bytes
            .saturating_mul(10_000)
            .saturating_div(self.total_bytes);
        scaled.min(10_000) as u32
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TaskMachine {
    state: TaskState,
}

impl Default for TaskMachine {
    fn default() -> Self {
        Self {
            state: TaskState::Queued,
        }
    }
}

impl TaskMachine {
    pub fn state(&self) -> TaskState {
        self.state
    }

    pub fn transition(&mut self, next: TaskState) -> Result<(), TransitionError> {
        if is_allowed(self.state, next) {
            self.state = next;
            Ok(())
        } else {
            Err(TransitionError {
                from: self.state,
                to: next,
            })
        }
    }
}

fn is_allowed(from: TaskState, to: TaskState) -> bool {
    use TaskState::*;
    matches!(
        (from, to),
        (Queued, Probing)
            | (Probing, Segmenting)
            | (Probing, FallbackSystem)
            | (Segmenting, RunningMulti)
            | (Segmenting, FallbackSystem)
            | (RunningMulti, Verifying)
            | (RunningMulti, Paused)
            | (RunningMulti, WaitingNetwork)
            | (RunningMulti, FailedRetryable)
            | (RunningMulti, FailedFinal)
            | (RunningMulti, Cancelled)
            | (Paused, RunningMulti)
            | (Paused, Cancelled)
            | (WaitingNetwork, RunningMulti)
            | (WaitingNetwork, Cancelled)
            | (FailedRetryable, RunningMulti)
            | (FailedRetryable, FallbackSystem)
            | (Verifying, Finalizing)
            | (Verifying, FailedFinal)
            | (Finalizing, Success)
            | (Finalizing, FailedFinal)
    )
}

#[derive(Debug, Error, PartialEq, Eq)]
#[error("invalid task transition: {from:?} -> {to:?}")]
pub struct TransitionError {
    pub from: TaskState,
    pub to: TaskState,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn route_is_fail_open() {
        let eligibility = Eligibility {
            supports_http_range: false,
            stable_total_length: true,
            ordinary_file: true,
            writable_destination: true,
            has_transform_semantics: false,
        };
        assert_eq!(eligibility.route(), RouteDecision::PassThrough);
    }

    #[test]
    fn task_machine_rejects_invalid_jump() {
        let mut machine = TaskMachine::default();
        let error = machine.transition(TaskState::Success).unwrap_err();
        assert_eq!(error.from, TaskState::Queued);
        assert_eq!(error.to, TaskState::Success);
    }
}

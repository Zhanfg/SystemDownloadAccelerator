use bytes::{BufMut, Bytes, BytesMut};
use prost::Message;
use thiserror::Error;

pub const PROTOCOL_VERSION: u32 = 1;
pub const MAX_FRAME_LEN: usize = 1024 * 1024;

#[derive(Clone, PartialEq, Message)]
pub struct Envelope {
    #[prost(uint32, tag = "1")]
    pub protocol_version: u32,
    #[prost(uint64, tag = "2")]
    pub request_id: u64,
    #[prost(oneof = "envelope::Payload", tags = "10, 11, 12, 13")]
    pub payload: Option<envelope::Payload>,
}

pub mod envelope {
    use super::{Hello, RuntimeStatus, TaskQuery, TaskSummary};
    use prost::Oneof;

    #[derive(Clone, PartialEq, Oneof)]
    pub enum Payload {
        #[prost(message, tag = "10")]
        Hello(Hello),
        #[prost(message, tag = "11")]
        RuntimeStatus(RuntimeStatus),
        #[prost(message, tag = "12")]
        TaskQuery(TaskQuery),
        #[prost(message, tag = "13")]
        TaskSummary(TaskSummary),
    }
}

#[derive(Clone, PartialEq, Message)]
pub struct Hello {
    #[prost(string, tag = "1")]
    pub peer_name: String,
    #[prost(uint32, tag = "2")]
    pub min_version: u32,
    #[prost(uint32, tag = "3")]
    pub max_version: u32,
    #[prost(string, repeated, tag = "4")]
    pub requested_capabilities: Vec<String>,
}

#[derive(Clone, PartialEq, Message)]
pub struct RuntimeStatus {
    #[prost(bool, tag = "1")]
    pub injected: bool,
    #[prost(bool, tag = "2")]
    pub safe_mode: bool,
    #[prost(string, tag = "3")]
    pub process_name: String,
    #[prost(uint32, tag = "4")]
    pub active_tasks: u32,
    #[prost(string, tag = "5")]
    pub last_error: String,
}

#[derive(Clone, PartialEq, Message)]
pub struct TaskQuery {
    #[prost(uint64, optional, tag = "1")]
    pub task_id: Option<u64>,
}

#[derive(Clone, PartialEq, Message)]
pub struct TaskSummary {
    #[prost(uint64, tag = "1")]
    pub task_id: u64,
    #[prost(string, tag = "2")]
    pub source_package: String,
    #[prost(string, tag = "3")]
    pub file_name: String,
    #[prost(enumeration = "TaskState", tag = "4")]
    pub state: i32,
    #[prost(uint64, tag = "5")]
    pub downloaded_bytes: u64,
    #[prost(uint64, tag = "6")]
    pub total_bytes: u64,
    #[prost(uint32, tag = "7")]
    pub active_workers: u32,
    #[prost(uint64, tag = "8")]
    pub speed_bps: u64,
    #[prost(uint64, tag = "9")]
    pub eta_seconds: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum TaskState {
    Unknown = 0,
    Queued = 1,
    Probing = 2,
    Segmenting = 3,
    RunningMulti = 4,
    Verifying = 5,
    Finalizing = 6,
    Success = 7,
    Paused = 8,
    WaitingNetwork = 9,
    FallbackSystem = 10,
    FailedRetryable = 11,
    FailedFinal = 12,
    Cancelled = 13,
}

#[derive(Debug, Error)]
pub enum FrameError {
    #[error("frame exceeds maximum size: {0}")]
    TooLarge(usize),
    #[error("incomplete frame")]
    Incomplete,
    #[error("protobuf decode failed: {0}")]
    Decode(#[from] prost::DecodeError),
}

pub fn encode_frame(message: &Envelope) -> Result<Bytes, FrameError> {
    let payload_len = message.encoded_len();
    if payload_len > MAX_FRAME_LEN {
        return Err(FrameError::TooLarge(payload_len));
    }

    let mut out = BytesMut::with_capacity(4 + payload_len);
    out.put_u32(payload_len as u32);
    message
        .encode(&mut out)
        .expect("BytesMut capacity is reserved for prost encoding");
    Ok(out.freeze())
}

pub fn decode_frame(input: &[u8]) -> Result<Envelope, FrameError> {
    if input.len() < 4 {
        return Err(FrameError::Incomplete);
    }
    let len = u32::from_be_bytes(input[0..4].try_into().expect("fixed length")) as usize;
    if len > MAX_FRAME_LEN {
        return Err(FrameError::TooLarge(len));
    }
    if input.len() < 4 + len {
        return Err(FrameError::Incomplete);
    }
    Ok(Envelope::decode(&input[4..4 + len])?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_envelope() {
        let envelope = Envelope {
            protocol_version: PROTOCOL_VERSION,
            request_id: 42,
            payload: Some(envelope::Payload::Hello(Hello {
                peer_name: "sdactl".into(),
                min_version: 1,
                max_version: 1,
                requested_capabilities: vec!["runtime.status".into()],
            })),
        };

        let bytes = encode_frame(&envelope).unwrap();
        let decoded = decode_frame(&bytes).unwrap();
        assert_eq!(decoded, envelope);
    }
}

use std::ops::RangeInclusive;

use thiserror::Error;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProbeResult {
    pub total_length: u64,
    pub etag: Option<String>,
    pub last_modified: Option<String>,
    pub accepts_ranges: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RangeRequest {
    pub url: String,
    pub range: RangeInclusive<u64>,
    pub etag: Option<String>,
    pub last_modified: Option<String>,
    pub headers: Vec<(String, String)>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RangeResponseMeta {
    pub status_code: u16,
    pub content_range: Option<String>,
    pub content_length: Option<u64>,
    pub etag: Option<String>,
    pub last_modified: Option<String>,
}

pub trait RangeBackend: Send + Sync + 'static {
    type Reader: std::io::Read + Send;

    fn probe(&self, url: &str, headers: &[(String, String)]) -> Result<ProbeResult, EngineError>;

    fn open_range(&self, request: &RangeRequest)
        -> Result<(RangeResponseMeta, Self::Reader), EngineError>;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Segment {
    pub index: u32,
    pub start: u64,
    pub end_inclusive: u64,
}

impl Segment {
    pub fn len(&self) -> u64 {
        self.end_inclusive - self.start + 1
    }

    pub fn is_empty(&self) -> bool {
        self.start > self.end_inclusive
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PlannerConfig {
    pub requested_workers: u32,
    pub min_segment_size: u64,
    pub max_workers: u32,
}

impl Default for PlannerConfig {
    fn default() -> Self {
        Self {
            requested_workers: 8,
            min_segment_size: 4 * 1024 * 1024,
            max_workers: 16,
        }
    }
}

pub fn plan_segments(total_length: u64, config: PlannerConfig) -> Result<Vec<Segment>, EngineError> {
    if total_length == 0 {
        return Err(EngineError::UnknownLength);
    }

    let by_size = total_length.div_ceil(config.min_segment_size.max(1));
    let worker_count = u64::from(config.requested_workers.max(1))
        .min(u64::from(config.max_workers.max(1)))
        .min(by_size.max(1)) as u32;

    let base = total_length / u64::from(worker_count);
    let remainder = total_length % u64::from(worker_count);
    let mut segments = Vec::with_capacity(worker_count as usize);
    let mut start = 0u64;

    for index in 0..worker_count {
        let extra = u64::from(index) < remainder;
        let len = base + u64::from(extra);
        let end_inclusive = start + len - 1;
        segments.push(Segment {
            index,
            start,
            end_inclusive,
        });
        start = end_inclusive + 1;
    }

    debug_assert_eq!(start, total_length);
    Ok(segments)
}

#[derive(Debug, Error)]
pub enum EngineError {
    #[error("server did not provide a stable total length")]
    UnknownLength,
    #[error("server does not support valid byte ranges")]
    RangeUnsupported,
    #[error("remote validator changed during download")]
    ValidatorChanged,
    #[error("network backend failed: {0}")]
    Backend(String),
    #[error("file I/O failed: {0}")]
    Io(#[from] std::io::Error),
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn planner_covers_file_without_overlap() {
        let segments = plan_segments(
            100,
            PlannerConfig {
                requested_workers: 4,
                min_segment_size: 1,
                max_workers: 8,
            },
        )
        .unwrap();

        assert_eq!(segments.first().unwrap().start, 0);
        assert_eq!(segments.last().unwrap().end_inclusive, 99);
        for pair in segments.windows(2) {
            assert_eq!(pair[0].end_inclusive + 1, pair[1].start);
        }
        assert_eq!(segments.iter().map(Segment::len).sum::<u64>(), 100);
    }
}

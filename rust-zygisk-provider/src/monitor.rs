use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::os::fd::RawFd;
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::host::{json_escape, HostReport};

const PROC_ROOT: &str = "/proc";

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
enum Role {
    Zygote32,
    Zygote64,
    Usap32,
    Usap64,
}

impl Role {
    fn as_str(self) -> &'static str {
        match self {
            Self::Zygote32 => "zygote32",
            Self::Zygote64 => "zygote64",
            Self::Usap32 => "usap32",
            Self::Usap64 => "usap64",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProcessRecord {
    pub role: &'static str,
    pub pid: u32,
    pub start_time_ticks: u64,
    pub abi: &'static str,
    pub command: String,
    pub pidfd_supported: bool,
    pub pidfd_alive: bool,
    pub provider_evidence: Vec<String>,
}

impl ProcessRecord {
    fn identity(&self) -> (u32, u64) {
        (self.pid, self.start_time_ticks)
    }

    fn to_json(&self) -> String {
        let evidence = self
            .provider_evidence
            .iter()
            .map(|item| format!("\"{}\"", json_escape(item)))
            .collect::<Vec<_>>()
            .join(",");
        format!(
            "{{\"role\":\"{}\",\"pid\":{},\"start_time_ticks\":{},\"abi\":\"{}\",\"command\":\"{}\",\"pidfd_supported\":{},\"pidfd_alive\":{},\"provider_evidence\":[{}]}}",
            self.role,
            self.pid,
            self.start_time_ticks,
            self.abi,
            json_escape(&self.command),
            self.pidfd_supported,
            self.pidfd_alive,
            evidence
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProviderGate {
    pub eligible: bool,
    pub reasons: Vec<String>,
}

impl ProviderGate {
    pub fn to_json(&self) -> String {
        let reasons = self
            .reasons
            .iter()
            .map(|item| format!("\"{}\"", json_escape(item)))
            .collect::<Vec<_>>()
            .join(",");
        format!(
            "{{\"eligible\":{},\"reasons\":[{}]}}",
            self.eligible, reasons
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MonitorSnapshot {
    pub sequence: u64,
    pub updated_ms: u128,
    pub pidfd_supported: bool,
    pub zygote32: Option<ProcessRecord>,
    pub zygote64: Option<ProcessRecord>,
    pub usap32_pids: Vec<u32>,
    pub usap64_pids: Vec<u32>,
    pub zygote32_restarts: u64,
    pub zygote64_restarts: u64,
    pub changes: Vec<String>,
    pub provider_gate: ProviderGate,
}

impl MonitorSnapshot {
    pub fn empty() -> Self {
        Self {
            sequence: 0,
            updated_ms: 0,
            pidfd_supported: false,
            zygote32: None,
            zygote64: None,
            usap32_pids: Vec::new(),
            usap64_pids: Vec::new(),
            zygote32_restarts: 0,
            zygote64_restarts: 0,
            changes: Vec::new(),
            provider_gate: ProviderGate {
                eligible: false,
                reasons: vec!["monitor-not-started".to_owned()],
            },
        }
    }

    pub fn to_json(&self) -> String {
        let zygote32 = self
            .zygote32
            .as_ref()
            .map_or_else(|| "null".to_owned(), ProcessRecord::to_json);
        let zygote64 = self
            .zygote64
            .as_ref()
            .map_or_else(|| "null".to_owned(), ProcessRecord::to_json);
        let usap32 = join_u32(&self.usap32_pids);
        let usap64 = join_u32(&self.usap64_pids);
        let changes = self
            .changes
            .iter()
            .map(|item| format!("\"{}\"", json_escape(item)))
            .collect::<Vec<_>>()
            .join(",");
        format!(
            "{{\"sequence\":{},\"updated_ms\":{},\"pidfd_supported\":{},\"zygote32\":{},\"zygote64\":{},\"usap32_pids\":[{}],\"usap64_pids\":[{}],\"zygote32_restarts\":{},\"zygote64_restarts\":{},\"changes\":[{}],\"provider_gate\":{}}}",
            self.sequence,
            self.updated_ms,
            self.pidfd_supported,
            zygote32,
            zygote64,
            usap32,
            usap64,
            self.zygote32_restarts,
            self.zygote64_restarts,
            changes,
            self.provider_gate.to_json()
        )
    }
}

#[derive(Debug, Default)]
pub struct MonitorEngine {
    sequence: u64,
    previous: BTreeMap<Role, (u32, u64)>,
    seen: BTreeSet<Role>,
    restarts: BTreeMap<Role, u64>,
}

impl MonitorEngine {
    pub fn scan(
        &mut self,
        host: &HostReport,
        guest_active: bool,
        safe_mode: bool,
    ) -> MonitorSnapshot {
        self.sequence = self.sequence.saturating_add(1);
        let records = scan_processes();
        let mut changes = Vec::new();

        let zygote32 = first_record(&records, Role::Zygote32);
        let zygote64 = first_record(&records, Role::Zygote64);
        self.track(Role::Zygote32, zygote32.as_ref(), &mut changes);
        self.track(Role::Zygote64, zygote64.as_ref(), &mut changes);

        let mut usap32_pids = pids_for(&records, Role::Usap32);
        let mut usap64_pids = pids_for(&records, Role::Usap64);
        usap32_pids.sort_unstable();
        usap64_pids.sort_unstable();

        let pidfd_supported = records.iter().any(|(_, record)| record.pidfd_supported);
        let provider_gate = provider_gate(
            host,
            guest_active,
            safe_mode,
            zygote32.is_some() || zygote64.is_some(),
        );

        MonitorSnapshot {
            sequence: self.sequence,
            updated_ms: now_ms(),
            pidfd_supported,
            zygote32,
            zygote64,
            usap32_pids,
            usap64_pids,
            zygote32_restarts: *self.restarts.get(&Role::Zygote32).unwrap_or(&0),
            zygote64_restarts: *self.restarts.get(&Role::Zygote64).unwrap_or(&0),
            changes,
            provider_gate,
        }
    }

    fn track(
        &mut self,
        role: Role,
        current: Option<&ProcessRecord>,
        changes: &mut Vec<String>,
    ) {
        let current_identity = current.map(ProcessRecord::identity);
        let previous_identity = self.previous.get(&role).copied();

        match (previous_identity, current_identity) {
            (None, Some(identity)) => {
                if self.seen.contains(&role) {
                    let count = self.restarts.entry(role).or_insert(0);
                    *count = count.saturating_add(1);
                    changes.push(format!("{}-restarted:{}", role.as_str(), identity.0));
                } else {
                    changes.push(format!("{}-started:{}", role.as_str(), identity.0));
                }
                self.seen.insert(role);
                self.previous.insert(role, identity);
            }
            (Some(previous), Some(current)) if previous != current => {
                let count = self.restarts.entry(role).or_insert(0);
                *count = count.saturating_add(1);
                changes.push(format!(
                    "{}-replaced:{}->{}",
                    role.as_str(),
                    previous.0,
                    current.0
                ));
                self.previous.insert(role, current);
            }
            (Some(previous), None) => {
                changes.push(format!("{}-exited:{}", role.as_str(), previous.0));
                self.previous.remove(&role);
            }
            _ => {}
        }
    }
}

fn provider_gate(
    host: &HostReport,
    guest_active: bool,
    safe_mode: bool,
    zygote_seen: bool,
) -> ProviderGate {
    let mut reasons = Vec::new();
    if safe_mode {
        reasons.push("safe-mode-enabled".to_owned());
    }
    if host.conflict {
        reasons.push("provider-conflict".to_owned());
    }
    if guest_active {
        reasons.push("active-zygisk-host-confirmed-by-guest".to_owned());
    } else if host.provider != "unknown" {
        reasons.push(format!("provider-detected:{}", host.provider));
    }
    if !zygote_seen {
        reasons.push("zygote-not-visible".to_owned());
    }
    ProviderGate {
        eligible: reasons.is_empty(),
        reasons,
    }
}

fn scan_processes() -> Vec<(Role, ProcessRecord)> {
    let mut records = Vec::new();
    let Ok(entries) = fs::read_dir(PROC_ROOT) else {
        return records;
    };

    for entry in entries.flatten() {
        let Some(pid_text) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        let Ok(pid) = pid_text.parse::<u32>() else {
            continue;
        };
        let process_dir = entry.path();
        let command = process_command(&process_dir);
        let Some(role) = classify_command(&command) else {
            continue;
        };
        let Some(start_time_ticks) = process_start_time(&process_dir) else {
            continue;
        };
        let abi = process_abi(role, &process_dir);
        let (pidfd_supported, pidfd_alive) = pidfd_probe(pid);
        let provider_evidence = if matches!(role, Role::Zygote32 | Role::Zygote64) {
            provider_mapping_evidence(&process_dir)
        } else {
            Vec::new()
        };
        records.push((
            role,
            ProcessRecord {
                role: role.as_str(),
                pid,
                start_time_ticks,
                abi,
                command,
                pidfd_supported,
                pidfd_alive,
                provider_evidence,
            },
        ));
    }
    records.sort_by_key(|(_, record)| record.pid);
    records
}

fn process_command(process_dir: &Path) -> String {
    if let Ok(bytes) = fs::read(process_dir.join("cmdline")) {
        let first = bytes.split(|byte| *byte == 0).next().unwrap_or_default();
        let command = String::from_utf8_lossy(first).trim().to_owned();
        if !command.is_empty() {
            return command;
        }
    }
    fs::read_to_string(process_dir.join("comm"))
        .unwrap_or_default()
        .trim()
        .to_owned()
}

fn classify_command(command: &str) -> Option<Role> {
    let leaf = command.rsplit('/').next().unwrap_or(command);
    if leaf == "zygote64" {
        Some(Role::Zygote64)
    } else if leaf == "zygote" || leaf == "zygote32" {
        Some(Role::Zygote32)
    } else if leaf.starts_with("usap64") {
        Some(Role::Usap64)
    } else if leaf.starts_with("usap32") || leaf == "usap" {
        Some(Role::Usap32)
    } else {
        None
    }
}

fn process_start_time(process_dir: &Path) -> Option<u64> {
    let stat = fs::read_to_string(process_dir.join("stat")).ok()?;
    parse_start_time(&stat)
}

fn parse_start_time(stat: &str) -> Option<u64> {
    let close = stat.rfind(')')?;
    let tail = stat.get(close + 1..)?.split_whitespace().collect::<Vec<_>>();
    tail.get(19)?.parse().ok()
}

fn process_abi(role: Role, process_dir: &Path) -> &'static str {
    if matches!(role, Role::Zygote64 | Role::Usap64) {
        return "64";
    }
    if let Ok(executable) = fs::read_link(process_dir.join("exe")) {
        if executable.to_string_lossy().contains("64") {
            return "64";
        }
    }
    "32"
}

fn provider_mapping_evidence(process_dir: &Path) -> Vec<String> {
    let Ok(maps) = fs::read_to_string(process_dir.join("maps")) else {
        return Vec::new();
    };
    let normalized = maps.to_ascii_lowercase();
    let mut evidence = Vec::new();
    for (needle, label) in [
        ("rezygisk", "mapping:rezygisk"),
        ("zygisknext", "mapping:zygisk-next"),
        ("zygisk_next", "mapping:zygisk-next"),
        ("zygisksu", "mapping:zygisksu"),
        ("libzygisk", "mapping:libzygisk"),
    ] {
        if normalized.contains(needle) && !evidence.iter().any(|item| item == label) {
            evidence.push(label.to_owned());
        }
    }
    evidence
}

fn pidfd_probe(pid: u32) -> (bool, bool) {
    // SAFETY: pidfd_open only reads the supplied PID and flags; no pointers are passed.
    let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, pid as libc::pid_t, 0) as RawFd };
    if fd < 0 {
        let error = std::io::Error::last_os_error();
        return (error.raw_os_error() != Some(libc::ENOSYS), false);
    }
    let mut poll_fd = libc::pollfd {
        fd,
        events: libc::POLLIN,
        revents: 0,
    };
    // SAFETY: poll_fd points to one initialized pollfd structure for this call.
    let result = unsafe { libc::poll(&mut poll_fd, 1, 0) };
    let alive = result == 0 || poll_fd.revents == 0;
    // SAFETY: fd is owned by this function and has not been closed yet.
    unsafe { libc::close(fd) };
    (true, alive)
}

fn first_record(records: &[(Role, ProcessRecord)], role: Role) -> Option<ProcessRecord> {
    records
        .iter()
        .find(|(candidate, _)| *candidate == role)
        .map(|(_, record)| record.clone())
}

fn pids_for(records: &[(Role, ProcessRecord)], role: Role) -> Vec<u32> {
    records
        .iter()
        .filter_map(|(candidate, record)| (*candidate == role).then_some(record.pid))
        .collect()
}

fn join_u32(values: &[u32]) -> String {
    values
        .iter()
        .map(u32::to_string)
        .collect::<Vec<_>>()
        .join(",")
}

fn now_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| duration.as_millis())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies_android_process_names() {
        assert_eq!(classify_command("zygote64"), Some(Role::Zygote64));
        assert_eq!(classify_command("/system/bin/usap64"), Some(Role::Usap64));
        assert_eq!(classify_command("com.example.app"), None);
    }

    #[test]
    fn parses_proc_start_time() {
        let mut fields = vec!["S"; 20];
        fields[19] = "123456";
        let stat = format!("42 (zygote64) {}", fields.join(" "));
        assert_eq!(parse_start_time(&stat), Some(123456));
    }

    #[test]
    fn gate_blocks_active_guest_host() {
        let host = HostReport {
            root_manager: "magisk".to_owned(),
            provider: "unknown".to_owned(),
            confidence: 0,
            conflict: false,
            evidence: Vec::new(),
        };
        let gate = provider_gate(&host, true, false, true);
        assert!(!gate.eligible);
        assert!(gate.reasons.iter().any(|item| item.contains("active-zygisk-host")));
    }
}

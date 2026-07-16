use std::fs::{self, OpenOptions};
use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{SystemTime, UNIX_EPOCH};

use rz_runtime::host::{json_escape, HostReport};
use rz_runtime::protocol::{Message, MessageKind};
use rz_runtime::security::require_root_peer;

const DATA_DIR: &str = "/data/adb/rzruntime";
const RUN_DIR: &str = "/data/adb/rzruntime/run";
const STATE_DIR: &str = "/data/adb/rzruntime/state";
const LOG_DIR: &str = "/data/adb/rzruntime/log";
const SOCKET_PATH: &str = "/data/adb/rzruntime/run/control.sock";
const PID_FILE: &str = "/data/adb/rzruntime/run/rzguestd.pid";
const STATUS_FILE: &str = "/data/adb/rzruntime/state/status.json";
const SAFE_MODE_FILE: &str = "/data/adb/rzruntime/state/safe_mode";
const FALLBACK_FILE: &str = "/data/adb/rzruntime/state/last_guest.txt";
const SUPERVISOR_STATE_FILE: &str = "/data/adb/rzruntime/state/supervisor.json";

#[derive(Debug, Clone)]
struct RuntimeSnapshot {
    healthy: bool,
    mode: &'static str,
    started_ms: u128,
    last_seen_ms: u128,
    pid: u32,
    uid: u32,
    process: String,
    registrations: u64,
    host: HostReport,
}

impl RuntimeSnapshot {
    fn new() -> Result<Self, String> {
        Ok(Self {
            healthy: true,
            mode: "guest",
            started_ms: now_ms()?,
            last_seen_ms: 0,
            pid: 0,
            uid: 0,
            process: String::new(),
            registrations: 0,
            host: HostReport::detect(),
        })
    }
}

fn main() {
    if let Err(error) = run() {
        eprintln!("rzguestd: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    prepare_directories()?;
    remove_stale_socket()?;
    write_pid_file()?;

    let listener = UnixListener::bind(SOCKET_PATH).map_err(|error| error.to_string())?;
    fs::set_permissions(SOCKET_PATH, fs::Permissions::from_mode(0o660))
        .map_err(|error| error.to_string())?;

    let snapshot = Arc::new(Mutex::new(RuntimeSnapshot::new()?));
    {
        let current = snapshot.lock().map_err(|_| "state poisoned")?;
        persist_snapshot(&current)?;
    }

    println!("rzguestd: listening on {SOCKET_PATH}");
    for connection in listener.incoming() {
        match connection {
            Ok(stream) => {
                let snapshot = Arc::clone(&snapshot);
                thread::spawn(move || {
                    if let Err(error) = handle_client(stream, &snapshot) {
                        eprintln!("rzguestd: client rejected: {error}");
                    }
                });
            }
            Err(error) => eprintln!("rzguestd: accept error: {error}"),
        }
    }

    Ok(())
}

fn handle_client(
    mut stream: UnixStream,
    snapshot: &Arc<Mutex<RuntimeSnapshot>>,
) -> Result<(), String> {
    require_root_peer(&stream)?;
    let request = Message::read_from(&mut stream).map_err(|error| error.to_string())?;
    match request.kind {
        MessageKind::RegisterProcess => register_process(request, &mut stream, snapshot),
        MessageKind::QueryStatus => {
            let current = snapshot.lock().map_err(|_| "state poisoned")?;
            send(&mut stream, MessageKind::Status, snapshot_json(&current))
        }
        MessageKind::QueryHost => {
            let mut current = snapshot.lock().map_err(|_| "state poisoned")?;
            current.host = HostReport::detect();
            persist_snapshot(&current)?;
            send(&mut stream, MessageKind::HostStatus, current.host.to_json())
        }
        MessageKind::QueryDoctor => {
            let mut current = snapshot.lock().map_err(|_| "state poisoned")?;
            current.host = HostReport::detect();
            persist_snapshot(&current)?;
            send(
                &mut stream,
                MessageKind::DoctorStatus,
                doctor_json(&current),
            )
        }
        other => send(
            &mut stream,
            MessageKind::Error,
            format!("unsupported request: {other:?}"),
        ),
    }
}

fn register_process(
    request: Message,
    stream: &mut UnixStream,
    snapshot: &Arc<Mutex<RuntimeSnapshot>>,
) -> Result<(), String> {
    let mut current = snapshot.lock().map_err(|_| "state poisoned")?;
    current.last_seen_ms = now_ms()?;
    current.pid = request.pid;
    current.uid = request.uid;
    current.process = request.body;
    current.registrations = current.registrations.saturating_add(1);
    persist_snapshot(&current)?;
    send(stream, MessageKind::Ack, "process-registered")
}

fn send(stream: &mut UnixStream, kind: MessageKind, body: impl Into<String>) -> Result<(), String> {
    Message::new(kind, 0, 0, body)
        .write_to(stream)
        .map_err(|error| error.to_string())
}

fn prepare_directories() -> Result<(), String> {
    for path in [DATA_DIR, RUN_DIR, STATE_DIR, LOG_DIR] {
        fs::create_dir_all(path).map_err(|error| error.to_string())?;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))
            .map_err(|error| error.to_string())?;
    }
    Ok(())
}

fn remove_stale_socket() -> Result<(), String> {
    let path = Path::new(SOCKET_PATH);
    if path.exists() {
        fs::remove_file(path).map_err(|error| error.to_string())?;
    }
    Ok(())
}

fn write_pid_file() -> Result<(), String> {
    fs::write(PID_FILE, format!("{}\n", std::process::id())).map_err(|error| error.to_string())
}

fn persist_snapshot(snapshot: &RuntimeSnapshot) -> Result<(), String> {
    let temp_path = format!("{STATUS_FILE}.tmp");
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(&temp_path)
        .map_err(|error| error.to_string())?;
    file.write_all(snapshot_json(snapshot).as_bytes())
        .map_err(|error| error.to_string())?;
    file.write_all(b"\n").map_err(|error| error.to_string())?;
    file.sync_all().map_err(|error| error.to_string())?;
    fs::rename(temp_path, STATUS_FILE).map_err(|error| error.to_string())
}

fn snapshot_json(snapshot: &RuntimeSnapshot) -> String {
    format!(
        "{{\"healthy\":{},\"mode\":\"{}\",\"started_ms\":{},\"last_seen_ms\":{},\"pid\":{},\"uid\":{},\"process\":\"{}\",\"registrations\":{},\"host\":{}}}",
        snapshot.healthy,
        snapshot.mode,
        snapshot.started_ms,
        snapshot.last_seen_ms,
        snapshot.pid,
        snapshot.uid,
        json_escape(&snapshot.process),
        snapshot.registrations,
        snapshot.host.to_json()
    )
}

fn doctor_json(snapshot: &RuntimeSnapshot) -> String {
    let now = now_ms().unwrap_or_default();
    let target_seen = snapshot.registrations > 0 && snapshot.process == "com.android.providers.downloads";
    let last_seen_age_ms = if snapshot.last_seen_ms == 0 {
        0
    } else {
        now.saturating_sub(snapshot.last_seen_ms)
    };
    let safe_mode = Path::new(SAFE_MODE_FILE).exists();
    let fallback_present = Path::new(FALLBACK_FILE).exists();
    let supervisor = fs::read_to_string(SUPERVISOR_STATE_FILE)
        .unwrap_or_else(|_| "null".to_owned())
        .trim()
        .to_owned();

    let mut issues = Vec::new();
    if safe_mode {
        issues.push("safe-mode-enabled");
    }
    if snapshot.host.conflict {
        issues.push("multiple-provider-candidates");
    }
    if snapshot.host.provider == "unknown" {
        issues.push("provider-unidentified");
    }
    if !target_seen {
        issues.push("downloadprovider-not-observed");
    }
    if fallback_present {
        issues.push("companion-fallback-state-present");
    }

    let issues_json = issues
        .iter()
        .map(|item| format!("\"{}\"", json_escape(item)))
        .collect::<Vec<_>>()
        .join(",");
    let ok = issues.is_empty();

    format!(
        "{{\"ok\":{},\"peer_auth\":\"SO_PEERCRED uid=0\",\"safe_mode\":{},\"target_seen\":{},\"last_seen_age_ms\":{},\"fallback_present\":{},\"issues\":[{}],\"host\":{},\"supervisor\":{}}}",
        ok,
        safe_mode,
        target_seen,
        last_seen_age_ms,
        fallback_present,
        issues_json,
        snapshot.host.to_json(),
        if supervisor.starts_with('{') { supervisor } else { "null".to_owned() }
    )
}

fn now_ms() -> Result<u128, String> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .map_err(|error| error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn status_is_valid_shape() {
        let mut snapshot = RuntimeSnapshot::new().unwrap();
        snapshot.process = "com.android.providers.downloads".to_owned();
        let json = snapshot_json(&snapshot);
        assert!(json.contains("\"mode\":\"guest\""));
        assert!(json.contains("com.android.providers.downloads"));
        assert!(json.contains("\"host\":"));
    }

    #[test]
    fn doctor_reports_unseen_target() {
        let snapshot = RuntimeSnapshot::new().unwrap();
        let json = doctor_json(&snapshot);
        assert!(json.contains("downloadprovider-not-observed"));
    }
}

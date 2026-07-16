use std::fs::{self, OpenOptions};
use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{SystemTime, UNIX_EPOCH};

use rz_runtime::protocol::{Message, MessageKind};

const DATA_DIR: &str = "/data/adb/rzruntime";
const RUN_DIR: &str = "/data/adb/rzruntime/run";
const STATE_DIR: &str = "/data/adb/rzruntime/state";
const LOG_DIR: &str = "/data/adb/rzruntime/log";
const SOCKET_PATH: &str = "/data/adb/rzruntime/run/control.sock";
const PID_FILE: &str = "/data/adb/rzruntime/run/rzguestd.pid";
const STATUS_FILE: &str = "/data/adb/rzruntime/state/status.json";

#[derive(Debug, Clone)]
struct RuntimeSnapshot {
    healthy: bool,
    mode: &'static str,
    host: &'static str,
    last_seen_ms: u128,
    pid: u32,
    uid: u32,
    process: String,
    registrations: u64,
}

impl Default for RuntimeSnapshot {
    fn default() -> Self {
        Self {
            healthy: true,
            mode: "guest",
            host: "zygisk-compatible",
            last_seen_ms: 0,
            pid: 0,
            uid: 0,
            process: String::new(),
            registrations: 0,
        }
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

    let snapshot = Arc::new(Mutex::new(RuntimeSnapshot::default()));
    persist_snapshot(&snapshot.lock().map_err(|_| "state poisoned")?)?;

    println!("rzguestd: listening on {SOCKET_PATH}");
    for connection in listener.incoming() {
        match connection {
            Ok(stream) => {
                let snapshot = Arc::clone(&snapshot);
                thread::spawn(move || {
                    if let Err(error) = handle_client(stream, &snapshot) {
                        eprintln!("rzguestd: client error: {error}");
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
    let request = Message::read_from(&mut stream).map_err(|error| error.to_string())?;
    match request.kind {
        MessageKind::RegisterProcess => {
            let mut current = snapshot.lock().map_err(|_| "state poisoned")?;
            current.last_seen_ms = now_ms()?;
            current.pid = request.pid;
            current.uid = request.uid;
            current.process = request.body;
            current.registrations = current.registrations.saturating_add(1);
            persist_snapshot(&current)?;
            Message::new(
                MessageKind::Ack,
                current.pid,
                current.uid,
                "process-registered",
            )
            .write_to(&mut stream)
            .map_err(|error| error.to_string())?;
        }
        MessageKind::QueryStatus => {
            let current = snapshot.lock().map_err(|_| "state poisoned")?;
            Message::new(MessageKind::Status, 0, 0, snapshot_json(&current))
                .write_to(&mut stream)
                .map_err(|error| error.to_string())?;
        }
        other => {
            Message::new(
                MessageKind::Error,
                0,
                0,
                format!("unsupported request: {other:?}"),
            )
            .write_to(&mut stream)
            .map_err(|error| error.to_string())?;
        }
    }
    Ok(())
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
        "{{\"healthy\":{},\"mode\":\"{}\",\"host\":\"{}\",\"last_seen_ms\":{},\"pid\":{},\"uid\":{},\"process\":\"{}\",\"registrations\":{}}}",
        snapshot.healthy,
        snapshot.mode,
        snapshot.host,
        snapshot.last_seen_ms,
        snapshot.pid,
        snapshot.uid,
        json_escape(&snapshot.process),
        snapshot.registrations
    )
}

fn json_escape(value: &str) -> String {
    let mut escaped = String::with_capacity(value.len());
    for character in value.chars() {
        match character {
            '"' => escaped.push_str("\\\""),
            '\\' => escaped.push_str("\\\\"),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '\t' => escaped.push_str("\\t"),
            control if control.is_control() => {
                use std::fmt::Write as _;
                let _ = write!(escaped, "\\u{:04x}", control as u32);
            }
            other => escaped.push(other),
        }
    }
    escaped
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
    fn escapes_json_control_characters() {
        assert_eq!(json_escape("a\"b\\c\n"), "a\\\"b\\\\c\\n");
    }

    #[test]
    fn status_is_valid_shape() {
        let snapshot = RuntimeSnapshot {
            process: "com.android.providers.downloads".to_owned(),
            ..RuntimeSnapshot::default()
        };
        let json = snapshot_json(&snapshot);
        assert!(json.contains("\"mode\":\"guest\""));
        assert!(json.contains("com.android.providers.downloads"));
    }
}

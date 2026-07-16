use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::UnixStream;
use std::path::Path;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use rz_runtime::protocol::{Message, MessageKind};

const SOCKET_PATH: &str = "/data/adb/rzruntime/run/control.sock";
const SAFE_MODE_FILE: &str = "/data/adb/rzruntime/state/safe_mode";
const DAEMON_PID_FILE: &str = "/data/adb/rzruntime/run/rzguestd.pid";

fn main() {
    if let Err(error) = run() {
        eprintln!("rzctl: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let arguments = std::env::args().skip(1).collect::<Vec<_>>();
    let command = arguments.first().map(String::as_str).unwrap_or("status");

    match command {
        "status" | "ping" => query(MessageKind::QueryStatus, MessageKind::Status),
        "host" => query(MessageKind::QueryHost, MessageKind::HostStatus),
        "doctor" => query(MessageKind::QueryDoctor, MessageKind::DoctorStatus),
        "safe-mode" => safe_mode(arguments.get(1).map(String::as_str).unwrap_or("status")),
        "restart-daemon" => restart_daemon(),
        "help" | "--help" | "-h" => {
            print_help();
            Ok(())
        }
        other => Err(format!("unknown command: {other}")),
    }
}

fn query(request_kind: MessageKind, expected_kind: MessageKind) -> Result<(), String> {
    let mut stream = UnixStream::connect(SOCKET_PATH).map_err(|error| {
        format!("cannot connect to runtime daemon at {SOCKET_PATH}: {error}")
    })?;
    stream
        .set_read_timeout(Some(Duration::from_secs(2)))
        .map_err(|error| error.to_string())?;
    stream
        .set_write_timeout(Some(Duration::from_secs(2)))
        .map_err(|error| error.to_string())?;

    Message::new(request_kind, std::process::id(), effective_uid(), "")
        .write_to(&mut stream)
        .map_err(|error| error.to_string())?;
    let response = Message::read_from(&mut stream).map_err(|error| error.to_string())?;

    if response.kind == expected_kind {
        println!("{}", response.body);
        Ok(())
    } else if response.kind == MessageKind::Error {
        Err(response.body)
    } else {
        Err(format!("unexpected response: {:?}", response.kind))
    }
}

fn safe_mode(action: &str) -> Result<(), String> {
    match action {
        "status" => {
            println!(
                "{{\"safe_mode\":{}}}",
                Path::new(SAFE_MODE_FILE).exists()
            );
            Ok(())
        }
        "enable" => {
            require_root()?;
            fs::create_dir_all("/data/adb/rzruntime/state")
                .map_err(|error| error.to_string())?;
            let contents = format!(
                "reason=user-requested\ntimestamp_ms={}\n",
                now_ms()?
            );
            fs::write(SAFE_MODE_FILE, contents).map_err(|error| error.to_string())?;
            fs::set_permissions(SAFE_MODE_FILE, fs::Permissions::from_mode(0o600))
                .map_err(|error| error.to_string())?;
            let _ = signal_daemon(libc::SIGTERM);
            println!("{{\"safe_mode\":true,\"action\":\"enabled\"}}");
            Ok(())
        }
        "disable" => {
            require_root()?;
            if Path::new(SAFE_MODE_FILE).exists() {
                fs::remove_file(SAFE_MODE_FILE).map_err(|error| error.to_string())?;
            }
            println!("{{\"safe_mode\":false,\"action\":\"disabled\"}}");
            Ok(())
        }
        other => Err(format!(
            "unknown safe-mode action: {other}; use status, enable or disable"
        )),
    }
}

fn restart_daemon() -> Result<(), String> {
    require_root()?;
    signal_daemon(libc::SIGTERM)?;
    println!("{{\"restart_requested\":true}}");
    Ok(())
}

fn signal_daemon(signal: i32) -> Result<(), String> {
    let pid_text = fs::read_to_string(DAEMON_PID_FILE).map_err(|error| error.to_string())?;
    let pid = pid_text
        .trim()
        .parse::<i32>()
        .map_err(|error| error.to_string())?;
    let command = fs::read(format!("/proc/{pid}/cmdline")).map_err(|error| error.to_string())?;
    if !String::from_utf8_lossy(&command).contains("rzguestd") {
        return Err(format!("pid {pid} is not rzguestd"));
    }

    // SAFETY: pid was read from the daemon-owned pid file and verified against /proc cmdline.
    let result = unsafe { libc::kill(pid, signal) };
    if result != 0 {
        return Err(std::io::Error::last_os_error().to_string());
    }
    Ok(())
}

fn require_root() -> Result<(), String> {
    if effective_uid() != 0 {
        return Err("this command requires root".to_owned());
    }
    Ok(())
}

fn effective_uid() -> u32 {
    // SAFETY: geteuid has no preconditions and does not access caller-provided memory.
    unsafe { libc::geteuid() }
}

fn now_ms() -> Result<u128, String> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .map_err(|error| error.to_string())
}

fn print_help() {
    println!(
        "Usage: rzctl <command>\n\nCommands:\n  status\n  host\n  doctor\n  safe-mode [status|enable|disable]\n  restart-daemon\n  help"
    );
}

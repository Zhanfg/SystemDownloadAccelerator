use std::collections::VecDeque;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::{Command, ExitStatus};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const DATA_DIR: &str = "/data/adb/rzruntime";
const RUN_DIR: &str = "/data/adb/rzruntime/run";
const STATE_DIR: &str = "/data/adb/rzruntime/state";
const PID_FILE: &str = "/data/adb/rzruntime/run/rzsupervisord.pid";
const STATE_FILE: &str = "/data/adb/rzruntime/state/supervisor.json";
const SAFE_MODE_FILE: &str = "/data/adb/rzruntime/state/safe_mode";
const CRASH_WINDOW: Duration = Duration::from_secs(60);
const STABLE_RUNTIME: Duration = Duration::from_secs(30);
const CRASH_LIMIT: usize = 5;

fn main() {
    if let Err(error) = run() {
        eprintln!("rzsupervisord: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    prepare_directories()?;
    fs::write(PID_FILE, format!("{}\n", std::process::id()))
        .map_err(|error| error.to_string())?;

    let daemon = daemon_path()?;
    let mut recent_exits = VecDeque::new();
    let mut restarts = 0_u64;
    let mut backoff = Duration::from_secs(1);

    loop {
        if Path::new(SAFE_MODE_FILE).exists() {
            persist_state("safe-mode", 0, restarts, backoff, None, true)?;
            thread::sleep(Duration::from_secs(2));
            recent_exits.clear();
            backoff = Duration::from_secs(1);
            continue;
        }

        let started = Instant::now();
        let mut child = match Command::new(&daemon).spawn() {
            Ok(child) => child,
            Err(error) => {
                restarts = restarts.saturating_add(1);
                persist_state(
                    "spawn-failed",
                    0,
                    restarts,
                    backoff,
                    Some(error.raw_os_error().unwrap_or(-1)),
                    false,
                )?;
                thread::sleep(backoff);
                backoff = (backoff * 2).min(Duration::from_secs(30));
                continue;
            }
        };

        let child_pid = child.id();
        persist_state("running", child_pid, restarts, backoff, None, false)?;
        let status = child.wait().map_err(|error| error.to_string())?;
        restarts = restarts.saturating_add(1);

        if started.elapsed() >= STABLE_RUNTIME {
            recent_exits.clear();
            backoff = Duration::from_secs(1);
        } else {
            let now = Instant::now();
            recent_exits.push_back(now);
            while recent_exits
                .front()
                .is_some_and(|timestamp| now.duration_since(*timestamp) > CRASH_WINDOW)
            {
                recent_exits.pop_front();
            }
        }

        if recent_exits.len() >= CRASH_LIMIT {
            enable_crash_safe_mode(status, restarts)?;
            persist_state(
                "crash-fused",
                0,
                restarts,
                backoff,
                exit_code(status),
                true,
            )?;
            continue;
        }

        persist_state(
            "backoff",
            0,
            restarts,
            backoff,
            exit_code(status),
            false,
        )?;
        thread::sleep(backoff);
        backoff = (backoff * 2).min(Duration::from_secs(30));
    }
}

fn prepare_directories() -> Result<(), String> {
    for path in [DATA_DIR, RUN_DIR, STATE_DIR] {
        fs::create_dir_all(path).map_err(|error| error.to_string())?;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))
            .map_err(|error| error.to_string())?;
    }
    Ok(())
}

fn daemon_path() -> Result<PathBuf, String> {
    let executable = std::env::current_exe().map_err(|error| error.to_string())?;
    let directory = executable
        .parent()
        .ok_or_else(|| "supervisor executable has no parent directory".to_owned())?;
    let daemon = directory.join("rzguestd");
    if !daemon.is_file() {
        return Err(format!("daemon not found: {}", daemon.display()));
    }
    Ok(daemon)
}

fn enable_crash_safe_mode(status: ExitStatus, restarts: u64) -> Result<(), String> {
    let contents = format!(
        "reason=daemon-crash-loop\ntimestamp_ms={}\nrestarts={}\nlast_exit={}\n",
        now_ms()?,
        restarts,
        exit_code(status).unwrap_or(-1)
    );
    fs::write(SAFE_MODE_FILE, contents).map_err(|error| error.to_string())?;
    fs::set_permissions(SAFE_MODE_FILE, fs::Permissions::from_mode(0o600))
        .map_err(|error| error.to_string())
}

fn persist_state(
    status: &str,
    child_pid: u32,
    restarts: u64,
    backoff: Duration,
    last_exit: Option<i32>,
    safe_mode: bool,
) -> Result<(), String> {
    let temp_path = format!("{STATE_FILE}.tmp");
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(&temp_path)
        .map_err(|error| error.to_string())?;
    let json = format!(
        "{{\"status\":\"{}\",\"supervisor_pid\":{},\"child_pid\":{},\"restarts\":{},\"backoff_ms\":{},\"last_exit\":{},\"safe_mode\":{},\"updated_ms\":{}}}\n",
        status,
        std::process::id(),
        child_pid,
        restarts,
        backoff.as_millis(),
        last_exit.map_or_else(|| "null".to_owned(), |value| value.to_string()),
        safe_mode,
        now_ms()?
    );
    file.write_all(json.as_bytes())
        .map_err(|error| error.to_string())?;
    file.sync_all().map_err(|error| error.to_string())?;
    fs::rename(temp_path, STATE_FILE).map_err(|error| error.to_string())
}

fn exit_code(status: ExitStatus) -> Option<i32> {
    status.code()
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
    fn crash_policy_constants_are_sane() {
        assert!(CRASH_LIMIT >= 3);
        assert!(CRASH_WINDOW > STABLE_RUNTIME);
    }
}

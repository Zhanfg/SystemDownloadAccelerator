pub mod protocol;

use std::ffi::{c_char, CStr};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::os::fd::{FromRawFd, RawFd};
use std::os::unix::net::UnixStream;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use protocol::{Message, MessageKind};

const CONTROL_SOCKET: &str = "/data/adb/rzruntime/run/control.sock";
const FALLBACK_STATE_DIR: &str = "/data/adb/rzruntime/state";
const FALLBACK_STATE_FILE: &str = "/data/adb/rzruntime/state/last_guest.txt";
const TARGET_PROCESS: &str = "com.android.providers.downloads";

pub const DECISION_UNLOAD: i32 = 0;
pub const DECISION_KEEP: i32 = 1;
pub const DECISION_KEEP_WITH_COMPANION: i32 = 2;

#[unsafe(no_mangle)]
pub extern "C" fn rz_runtime_on_load() -> i32 {
    ffi_guard(|| 0).unwrap_or(-255)
}

#[unsafe(no_mangle)]
pub extern "C" fn rz_runtime_pre_app(process_name: *const c_char, _uid: i32) -> i32 {
    ffi_guard(|| match read_process_name(process_name) {
        Ok(name) if name == TARGET_PROCESS => DECISION_KEEP_WITH_COMPANION,
        Ok(_) => DECISION_UNLOAD,
        Err(_) => DECISION_UNLOAD,
    })
    .unwrap_or(DECISION_UNLOAD)
}

#[unsafe(no_mangle)]
pub extern "C" fn rz_runtime_post_app(
    process_name: *const c_char,
    uid: i32,
    companion_fd: RawFd,
) -> i32 {
    ffi_guard(|| post_app_inner(process_name, uid, companion_fd)).unwrap_or(-255)
}

#[unsafe(no_mangle)]
pub extern "C" fn rz_companion_entry(client_fd: RawFd) {
    let _ = ffi_guard(|| companion_inner(client_fd));
}

fn post_app_inner(process_name: *const c_char, uid: i32, companion_fd: RawFd) -> i32 {
    if companion_fd < 0 {
        return -2;
    }

    let process_name = match read_process_name(process_name) {
        Ok(name) => name,
        Err(_) => return -3,
    };

    // SAFETY: ownership of the exempted descriptor is transferred by the C++ shim.
    let mut stream = unsafe { UnixStream::from_raw_fd(companion_fd) };
    let _ = stream.set_read_timeout(Some(Duration::from_secs(3)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(3)));

    let pid = unsafe { libc::getpid() } as u32;
    let message = Message::new(MessageKind::RegisterProcess, pid, uid as u32, process_name);
    if message.write_to(&mut stream).is_err() {
        return -4;
    }

    match Message::read_from(&mut stream) {
        Ok(reply) if reply.kind == MessageKind::Ack => 0,
        Ok(reply) if reply.kind == MessageKind::Error => -5,
        Ok(_) => -6,
        Err(_) => -7,
    }
}

fn companion_inner(client_fd: RawFd) {
    if client_fd < 0 {
        return;
    }

    // SAFETY: Zygisk transfers ownership of the connected companion descriptor.
    let mut client = unsafe { UnixStream::from_raw_fd(client_fd) };
    let _ = client.set_read_timeout(Some(Duration::from_secs(3)));
    let _ = client.set_write_timeout(Some(Duration::from_secs(3)));

    let request = match Message::read_from(&mut client) {
        Ok(message) => message,
        Err(error) => {
            let _ = Message::new(MessageKind::Error, 0, 0, error.to_string())
                .write_to(&mut client);
            return;
        }
    };

    if request.kind != MessageKind::RegisterProcess {
        let _ = Message::new(MessageKind::Error, 0, 0, "unexpected companion request")
            .write_to(&mut client);
        return;
    }

    let daemon_result = forward_to_daemon(&request);
    if daemon_result.is_err() {
        let _ = write_fallback_state(&request);
    }

    let body = match daemon_result {
        Ok(()) => "registered-via-daemon",
        Err(_) => "registered-via-fallback",
    };
    let _ = Message::new(MessageKind::Ack, request.pid, request.uid, body).write_to(&mut client);
}

fn forward_to_daemon(message: &Message) -> Result<(), String> {
    let mut daemon = UnixStream::connect(CONTROL_SOCKET).map_err(|error| error.to_string())?;
    daemon
        .set_read_timeout(Some(Duration::from_secs(2)))
        .map_err(|error| error.to_string())?;
    daemon
        .set_write_timeout(Some(Duration::from_secs(2)))
        .map_err(|error| error.to_string())?;
    message
        .write_to(&mut daemon)
        .map_err(|error| error.to_string())?;
    let reply = Message::read_from(&mut daemon).map_err(|error| error.to_string())?;
    if reply.kind == MessageKind::Ack {
        Ok(())
    } else {
        Err(format!("unexpected daemon response: {:?}", reply.kind))
    }
}

fn write_fallback_state(message: &Message) -> Result<(), String> {
    fs::create_dir_all(FALLBACK_STATE_DIR).map_err(|error| error.to_string())?;
    let now_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| error.to_string())?
        .as_millis();
    let temp_path = format!("{FALLBACK_STATE_FILE}.tmp");
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(&temp_path)
        .map_err(|error| error.to_string())?;
    writeln!(
        file,
        "timestamp_ms={now_ms}\npid={}\nuid={}\nprocess={}",
        message.pid, message.uid, message.body
    )
    .map_err(|error| error.to_string())?;
    file.sync_all().map_err(|error| error.to_string())?;
    fs::rename(temp_path, Path::new(FALLBACK_STATE_FILE)).map_err(|error| error.to_string())?;
    Ok(())
}

fn read_process_name(pointer: *const c_char) -> Result<String, ()> {
    if pointer.is_null() {
        return Err(());
    }

    // SAFETY: the C++ shim provides a NUL-terminated string for the duration of this call.
    let value = unsafe { CStr::from_ptr(pointer) };
    value.to_str().map(str::to_owned).map_err(|_| ())
}

fn ffi_guard<T>(operation: impl FnOnce() -> T) -> Result<T, ()> {
    catch_unwind(AssertUnwindSafe(operation)).map_err(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CString;

    #[test]
    fn target_process_requests_companion() {
        let name = CString::new(TARGET_PROCESS).unwrap();
        assert_eq!(
            rz_runtime_pre_app(name.as_ptr(), 10068),
            DECISION_KEEP_WITH_COMPANION
        );
    }

    #[test]
    fn unrelated_process_is_unloaded() {
        let name = CString::new("com.example.app").unwrap();
        assert_eq!(rz_runtime_pre_app(name.as_ptr(), 10123), DECISION_UNLOAD);
    }

    #[test]
    fn null_process_is_unloaded() {
        assert_eq!(rz_runtime_pre_app(std::ptr::null(), 0), DECISION_UNLOAD);
    }
}

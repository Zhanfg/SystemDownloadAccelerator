use std::ffi::{c_char, CStr};
use std::os::fd::RawFd;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::OnceLock;

use thiserror::Error;

static RUNTIME: OnceLock<RuntimeContext> = OnceLock::new();

#[derive(Debug)]
struct RuntimeContext {
    process_name: String,
    bootstrap_fd: RawFd,
}

#[repr(C)]
pub struct SdaRuntimeInitArgs {
    pub process_name: *const c_char,
    pub daemon_fd: RawFd,
}

#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SdaRuntimeStatus {
    Ok = 0,
    InvalidArgument = 1,
    WrongProcess = 2,
    AlreadyInitialized = 3,
    InternalError = 255,
}

#[unsafe(no_mangle)]
pub extern "C" fn sda_runtime_init(args: *const SdaRuntimeInitArgs) -> SdaRuntimeStatus {
    ffi_guard(|| initialize(args)).unwrap_or(SdaRuntimeStatus::InternalError)
}

#[unsafe(no_mangle)]
pub extern "C" fn sda_runtime_is_initialized() -> bool {
    RUNTIME.get().is_some()
}

#[unsafe(no_mangle)]
pub extern "C" fn sda_runtime_bootstrap_fd() -> RawFd {
    RUNTIME.get().map_or(-1, |runtime| runtime.bootstrap_fd)
}

#[unsafe(no_mangle)]
pub extern "C" fn sda_runtime_process_name_len() -> usize {
    RUNTIME.get().map_or(0, |runtime| runtime.process_name.len())
}

#[unsafe(no_mangle)]
pub extern "C" fn sda_companion_handle(client_fd: RawFd) {
    let _ = ffi_guard(|| companion_handle(client_fd));
}

fn initialize(args: *const SdaRuntimeInitArgs) -> SdaRuntimeStatus {
    if args.is_null() {
        return SdaRuntimeStatus::InvalidArgument;
    }

    // SAFETY: the C++ shim passes a valid pointer for the duration of this call.
    let args = unsafe { &*args };
    if args.process_name.is_null() || args.daemon_fd < 0 {
        return SdaRuntimeStatus::InvalidArgument;
    }

    // SAFETY: process_name is a NUL-terminated string owned by the caller for this call.
    let process_name = unsafe { CStr::from_ptr(args.process_name) };
    let Ok(process_name) = process_name.to_str() else {
        return SdaRuntimeStatus::InvalidArgument;
    };

    if process_name != "com.android.providers.downloads" {
        return SdaRuntimeStatus::WrongProcess;
    }

    let context = RuntimeContext {
        process_name: process_name.to_owned(),
        bootstrap_fd: args.daemon_fd,
    };

    if RUNTIME.set(context).is_err() {
        return SdaRuntimeStatus::AlreadyInitialized;
    }

    SdaRuntimeStatus::Ok
}

fn companion_handle(client_fd: RawFd) {
    // Phase 2 will connect to /data/adb/sda/run/control.sock and transfer the
    // authenticated daemon descriptor with SCM_RIGHTS. The FFI boundary is
    // established now so the C++ shim never owns daemon or protocol logic.
    let _ = client_fd;
}

fn ffi_guard<T>(operation: impl FnOnce() -> T) -> Result<T, RuntimeError> {
    catch_unwind(AssertUnwindSafe(operation)).map_err(|_| RuntimeError::Panic)
}

#[derive(Debug, Error)]
enum RuntimeError {
    #[error("panic contained at FFI boundary")]
    Panic,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn null_init_is_rejected() {
        assert_eq!(
            sda_runtime_init(std::ptr::null()),
            SdaRuntimeStatus::InvalidArgument
        );
    }
}

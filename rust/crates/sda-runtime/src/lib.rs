use std::ffi::{c_char, CStr};
use std::os::fd::RawFd;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::OnceLock;

use thiserror::Error;

static RUNTIME: OnceLock<RuntimeContext> = OnceLock::new();

#[derive(Debug)]
struct RuntimeContext {
    process_name: String,
    daemon_fd: RawFd,
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
        daemon_fd: args.daemon_fd,
    };

    if RUNTIME.set(context).is_err() {
        return SdaRuntimeStatus::AlreadyInitialized;
    }

    SdaRuntimeStatus::Ok
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

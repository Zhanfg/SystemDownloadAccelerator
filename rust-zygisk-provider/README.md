# Rust Zygisk Runtime

This directory contains the first executable milestone of a Rust-first Zygisk framework.

## Current milestone: Guest mode

The module is loaded by an existing compatible Zygisk provider. It does not inject Zygote by itself yet.

```text
Existing Zygisk provider
└─ zygisk/arm64-v8a.so
   ├─ minimal C++ ABI adapter
   └─ Rust runtime
      ├─ process policy
      ├─ companion protocol
      ├─ root companion client
      ├─ persistent daemon
      └─ CLI diagnostics
```

Only `com.android.providers.downloads` is retained in the process. Every unrelated app process immediately requests `DLCLOSE_MODULE_LIBRARY`. This milestone does not install JNI, PLT or ART hooks and does not modify specialization arguments.

## Rust/C++ boundary

The C++ adapter is intentionally limited to the public Zygisk ABI:

- receive `onLoad`, `preAppSpecialize` and `postAppSpecialize`;
- convert `jstring nice_name` to a bounded UTF-8 buffer;
- call `connectCompanion()` and `exemptFd()` before specialization;
- request `DLCLOSE_MODULE_LIBRARY` for unrelated processes;
- forward lifecycle data to C ABI functions implemented by Rust.

Rust owns:

- target-process policy;
- framed and versioned IPC;
- companion request handling;
- daemon registration state;
- status persistence;
- CLI output;
- panic containment at every exported FFI boundary.

## Runtime data

```text
/data/adb/rzruntime/
├─ config/runtime.conf
├─ run/control.sock
├─ run/rzguestd.pid
├─ state/status.json
├─ state/last_guest.txt
└─ log/rzguestd.log
```

## Build

Requirements:

- Rust 1.86 or newer;
- Android NDK with an arm64 API 26 clang toolchain;
- `aarch64-linux-android` Rust target;
- `zip` and `sha256sum`.

```sh
export ANDROID_NDK_HOME=/path/to/android-ndk
rustup target add aarch64-linux-android
./build.sh
```

Output:

```text
dist/Rust-Zygisk-Runtime-Guest-v0.1.0.zip
dist/SHA256SUMS.txt
```

## Device validation

After flashing and rebooting:

```sh
su -c /data/adb/modules/rust_zygisk_runtime/bin/rzctl status
```

Before DownloadProvider is observed, the daemon reports an empty process. After any system DownloadManager task causes `com.android.providers.downloads` to start, the status should contain that process name, PID, UID and an incremented registration counter.

## Fail-open behavior

- A missing companion connection does not change DownloadProvider behavior.
- A daemon failure falls back to a root-companion state file.
- Non-target processes unload the module library immediately.
- No hook is installed in this milestone.

## Next provider milestones

1. Host-provider identification and conflict arbitration.
2. Root adapter abstraction for Magisk, KernelSU and APatch.
3. Rust monitor state machine for 32-bit and 64-bit Zygote.
4. Clean-room provider-mode injector.
5. Standard Zygisk module registry and API v1-v5 adapters.
6. Module crash fuse and transactional loading.

ReZygisk is used as an architectural and behavioral reference under its published open-source terms. No current closed-source Zygisk Next implementation is copied.

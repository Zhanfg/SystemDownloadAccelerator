# Rust Zygisk Runtime

This directory contains the executable Guest-mode foundation of a Rust-first Zygisk framework.

## Current milestone: supervised Guest mode

The module is loaded by an existing compatible Zygisk provider. It does not inject Zygote by itself yet.

```text
Existing Zygisk provider
└─ zygisk/arm64-v8a.so
   ├─ minimal C++ ABI adapter
   └─ Rust runtime
      ├─ process policy
      ├─ authenticated companion protocol
      ├─ root companion client
      ├─ persistent daemon
      ├─ crash-loop supervisor
      ├─ host/provider diagnostics
      └─ CLI and safe mode
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

- target-process policy and safe-mode gate;
- framed and versioned IPC;
- `SO_PEERCRED` root-client authentication;
- companion request handling;
- daemon registration state;
- root-manager and provider detection;
- crash supervision and automatic fuse;
- status persistence and doctor reports;
- CLI output;
- panic containment at every exported FFI boundary.

## Runtime data

```text
/data/adb/rzruntime/
├─ config/runtime.conf
├─ run/control.sock
├─ run/rzguestd.pid
├─ run/rzsupervisord.pid
├─ state/status.json
├─ state/supervisor.json
├─ state/last_guest.txt
├─ state/safe_mode
└─ log/rzruntime.log
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
dist/Rust-Zygisk-Runtime-Guest-v0.2.0.zip
dist/SHA256SUMS.txt
```

## Device validation

After flashing and rebooting:

```sh
su -c /data/adb/modules/rust_zygisk_runtime/bin/rzctl status
su -c /data/adb/modules/rust_zygisk_runtime/bin/rzctl host
su -c /data/adb/modules/rust_zygisk_runtime/bin/rzctl doctor
```

Before DownloadProvider is observed, the daemon reports an empty process and `downloadprovider-not-observed`. After any system DownloadManager task causes `com.android.providers.downloads` to start, the status should contain that process name, PID, UID and an incremented registration counter.

Emergency controls:

```sh
su -c /data/adb/modules/rust_zygisk_runtime/bin/rzctl safe-mode enable
su -c /data/adb/modules/rust_zygisk_runtime/bin/rzctl safe-mode disable
su -c /data/adb/modules/rust_zygisk_runtime/bin/rzctl restart-daemon
```

Safe mode causes every app process to unload the Guest runtime before any companion connection is created. The supervisor also enables safe mode automatically after five daemon exits within sixty seconds.

## Host detection

Host identity is reported with a confidence score and evidence list. It scans active module metadata and root-owned process command lines. Unknown results remain `unknown`; the runtime does not infer a provider merely from a compatible API response. Multiple active provider candidates are reported as a conflict and never trigger Provider mode automatically.

## Fail-open behavior

- A missing companion connection does not change DownloadProvider behavior.
- A daemon failure falls back to a root-companion state file.
- Non-target processes unload the module library immediately.
- Safe mode unloads the module from every app process.
- No hook is installed in this milestone.

## Next provider milestones

1. Provider arbitration policy and explicit user-selected mode.
2. Root adapter abstraction for Magisk, KernelSU and APatch.
3. Rust monitor state machine for 32-bit and 64-bit Zygote.
4. Clean-room Provider-mode injector.
5. Standard Zygisk module registry and API v1-v5 adapters.
6. Module crash fuse and transactional loading.

ReZygisk is used as an architectural and behavioral reference under its published open-source terms. No current closed-source Zygisk Next implementation is copied.

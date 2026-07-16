# Rust Zygisk Runtime

This directory contains the executable Guest-mode foundation of a Rust-first Zygisk framework.

## Current milestone: monitored Guest mode

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
      ├─ read-only Zygote/USAP monitor
      ├─ provider arbitration preflight
      ├─ host/provider diagnostics
      └─ CLI and safe mode
```

Only `com.android.providers.downloads` is retained in the app process. Every unrelated app process immediately requests `DLCLOSE_MODULE_LIBRARY`. This milestone does not install JNI, PLT or ART hooks and does not modify specialization arguments.

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
- `/proc` process discovery and PID-reuse detection;
- `pidfd_open` liveness probes when supported by the kernel;
- Zygote32, Zygote64, USAP32 and USAP64 state tracking;
- Provider-mode eligibility and conflict reasons;
- status persistence and doctor reports;
- CLI output;
- panic containment at every exported FFI boundary.

## Read-only monitor

The monitor scans `/proc` every two seconds. It does not attach with `ptrace`, stop a process, write process memory, install a hook, or send a signal.

A process identity is the pair:

```text
(pid, /proc/<pid>/stat start_time)
```

This prevents a recycled PID from being treated as the same Zygote. When supported, `pidfd_open` is also used as a read-only liveness check. Zygote mappings are inspected only for provider evidence strings.

The monitor reports:

- current Zygote32 and Zygote64 identities;
- ABI and command line;
- USAP process lists;
- Zygote replacement/restart counters;
- pidfd support and liveness;
- provider-related mapping evidence;
- Provider-mode eligibility and every blocking reason.

Provider mode remains blocked whenever Guest registration proves another Zygisk host is active.

## Runtime data

```text
/data/adb/rzruntime/
├─ config/runtime.conf
├─ run/control.sock
├─ run/rzguestd.pid
├─ run/rzsupervisord.pid
├─ state/status.json
├─ state/monitor.json
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
dist/Rust-Zygisk-Runtime-Guest-v0.3.0.zip
dist/SHA256SUMS.txt
```

## Device validation

After flashing and rebooting:

```sh
su -c /data/adb/modules/rust_zygisk_runtime/bin/rzctl status
su -c /data/adb/modules/rust_zygisk_runtime/bin/rzctl host
su -c /data/adb/modules/rust_zygisk_runtime/bin/rzctl monitor
su -c /data/adb/modules/rust_zygisk_runtime/bin/rzctl provider-gate
su -c /data/adb/modules/rust_zygisk_runtime/bin/rzctl doctor
```

Before DownloadProvider is observed, the daemon reports an empty process and `downloadprovider-not-observed`. After any system DownloadManager task causes `com.android.providers.downloads` to start, the status should contain that process name, PID, UID and an incremented registration counter.

In Guest mode, `provider-gate` should normally be blocked by:

```text
active-zygisk-host-confirmed-by-guest
```

That result is correct and prevents a second Provider from injecting into the same Zygote.

Emergency controls:

```sh
su -c /data/adb/modules/rust_zygisk_runtime/bin/rzctl safe-mode enable
su -c /data/adb/modules/rust_zygisk_runtime/bin/rzctl safe-mode disable
su -c /data/adb/modules/rust_zygisk_runtime/bin/rzctl restart-daemon
```

Safe mode causes every app process to unload the Guest runtime before any companion connection is created. The supervisor also enables safe mode automatically after five daemon exits within sixty seconds.

## Fail-open behavior

- A missing companion connection does not change DownloadProvider behavior.
- A daemon failure falls back to a root-companion state file.
- Non-target processes unload the module library immediately.
- Safe mode unloads the module from every app process.
- Monitor failure does not modify or terminate Zygote.
- Provider eligibility is diagnostic only in this milestone.
- No hook is installed in this milestone.

## Next provider milestones

1. Persisted user-selected operating mode with mandatory conflict gate.
2. Root adapter abstraction for Magisk, KernelSU and APatch.
3. Read-only monitor soak testing across Zygote and USAP restarts.
4. Clean-room Provider-mode injector behind an explicit experimental flag.
5. Standard Zygisk module registry and API v1-v5 adapters.
6. Module crash fuse and transactional loading.

ReZygisk is used as an architectural and behavioral reference under its published open-source terms. No current closed-source Zygisk Next implementation is copied.

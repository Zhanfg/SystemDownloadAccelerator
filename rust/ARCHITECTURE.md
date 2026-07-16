# Rust-first architecture

Status: initial architecture for the standard Zygisk rewrite.

The LSPosed Domo probe remains a disposable compatibility test. It is not part of the production runtime.

## Design boundary

The module does **not** replace `DownloadProvider.apk`, the package, provider authorities, UID, database contract, jobs, or notification ownership.

It replaces only the network execution path for eligible HTTP/HTTPS tasks and decorates the existing system notification in place.

The formal implementation is Rust-first. Two deliberately small compatibility shims remain outside Rust:

1. a C++ Zygisk ABI shim, because the public Zygisk module API is a C++ virtual interface;
2. a Java/ART hook bridge, because `DownloadProvider` execution and notification builders are Java methods and the public Zygisk API does not provide arbitrary Java-method hooks.

Neither shim contains scheduling, download, persistence, policy, protocol, or UI business logic.

## Process topology

```text
KernelSU / module scripts
└─ sdad (Rust, root daemon)
   ├─ control.sock
   ├─ plugin.sock
   ├─ persistent task checkpoints
   ├─ configuration and safe-mode state
   └─ diagnostics

Zygisk Next
└─ zygisk/<abi>.so
   └─ tiny C++ ABI shim
      └─ libsda_runtime.a / Rust FFI
         └─ com.android.providers.downloads
            ├─ Java/ART bridge
            ├─ task router
            ├─ segmented-download coordinator
            ├─ Android HTTP range workers
            ├─ progress aggregator
            ├─ StateSynchronizer
            └─ notification decorator

KernelSU WebUI
└─ sdactl --json ...
   └─ control.sock
      └─ sdad
```

## Why a persistent daemon and a Zygisk companion are both used

`sdad` is a normal persistent Rust daemon started by `service.sh`. It owns persistent state, plugin registration and diagnostics.

The Zygisk companion is only an SELinux-safe bootstrap broker. During `preAppSpecialize`, it connects to `sdad`, then passes an already-open Unix socket file descriptor to the injected runtime. The injected process retains that file descriptor after specialization.

The companion is not the long-running backend and does not perform downloads.

## Workspace crates

### `sda-protocol`

Stable, versioned IPC types shared by `sdad`, `sdactl`, the injected runtime and future plugins.

Responsibilities:

- length-prefixed frames;
- protocol version negotiation;
- request IDs;
- capability negotiation;
- task and runtime events;
- bounded message sizes;
- explicit error codes.

The first wire format is protobuf through `prost`. No Rust-specific serialized representation is exposed to plugins.

### `sda-core`

Platform-independent state and policy.

Responsibilities:

- task state machine;
- routing decisions;
- task identity and notification identity;
- progress aggregation;
- retry and circuit-breaker policy;
- concurrency limits;
- configuration schema.

No JNI, filesystem path assumptions, sockets or Android classes are allowed in this crate.

### `sda-engine`

Segmented-download coordinator.

Responsibilities:

- HTTP Range capability probe;
- ETag / Last-Modified consistency checks;
- segment planning;
- work stealing;
- retry and backoff;
- non-overlapping `pwrite` output;
- checkpoint generation;
- final verification and atomic completion.

The network layer is a trait. The first production backend is an Android/JNI backend using the platform HTTP stack, not an independent Rust TLS stack. This preserves Android proxy behavior, trust configuration and the DownloadProvider process UID/network policy.

### `sda-runtime`

Injected runtime compiled as a Rust `staticlib` and linked into the Zygisk module shared object.

Responsibilities:

- target-process initialization;
- receiving the pre-opened daemon socket;
- installing the Java/ART bridge;
- routing eligible tasks;
- running the Rust coordinator;
- exposing progress to `StateSynchronizer`;
- fail-open fallback to the original DownloadProvider implementation;
- decorating the original notification without changing its package, tag, ID, channel or PendingIntents.

All exported FFI functions catch Rust panics before crossing the C ABI boundary.

### `sdad`

Root daemon.

Responsibilities:

- configuration persistence;
- runtime health and crash counters;
- safe mode;
- task checkpoints under `/data/adb/sda/state`;
- plugin authentication and capability grants;
- protocol translation;
- diagnostics and status snapshots.

`sdad` does not normally open network connections or write DownloadProvider database rows.

### `sdactl`

Small Rust CLI used by boot scripts and KernelSU WebUI.

Examples:

```text
sdactl --json status
sdactl --json tasks
sdactl --json config get
sdactl --json config set concurrency.global=16
sdactl --json diagnostics export
sdactl safe-mode enable
```

## Java/ART bridge

The bridge has a narrow API surface:

```text
installHooks(runtimeHandle)
extractTask(downloadThreadObject) -> TaskDescriptor
openRangeConnection(request) -> RangeConnectionHandle
syncProgress(taskId, bytes, total, speed, eta)
decorateNotification(notification, aggregateState)
callOriginal(downloadThreadObject)
```

The bridge must not implement the scheduler or hold persistent task state.

Initial hook targets are discovered from the uploaded Android 16 ROM APKs and stored as a versioned compatibility profile. Hard-coded AOSP class names are not treated as universal.

## Task routing

```text
PASS_THROUGH
SDA_MANAGED
DEFER
REJECT
REDIRECT
```

The first release uses only `PASS_THROUGH` and `SDA_MANAGED` by default. All unsupported or ambiguous tasks pass through to the original implementation.

Eligibility requirements for `SDA_MANAGED`:

- HTTP or HTTPS;
- known final URL after redirects;
- stable total length;
- valid byte-range response;
- stable ETag or Last-Modified when supplied;
- ordinary file download;
- destination writable by the DownloadProvider process;
- no DRM, OTA, streaming playlist or content-transform semantics.

## Runtime task state

```text
QUEUED
  -> PROBING
  -> SEGMENTING
  -> RUNNING_MULTI
  -> VERIFYING
  -> FINALIZING
  -> SUCCESS
```

Exceptional paths:

```text
PAUSED
WAITING_NETWORK
FALLBACK_SYSTEM
FAILED_RETRYABLE
FAILED_FINAL
CANCELLED
```

Only `StateSynchronizer` may mutate DownloadProvider-visible state. Segment workers never update the provider database directly.

## File output model

One system task maps to one database row, one final file and one notification identity.

Internally:

- preallocate one `.sda.part` file;
- every segment owns a non-overlapping byte range;
- workers write with positional I/O;
- aggregate durable bytes separately from transient in-flight bytes;
- periodically checkpoint segment state;
- `fsync` and atomically rename only after final validation.

The design deliberately avoids one temporary file per segment.

## Notification and Domo boundary

The notification remains a system DownloadProvider notification.

The runtime:

- preserves original package, tag, integer ID and channel;
- preserves original content and delete PendingIntents;
- updates the same notification submission chain;
- adds only useful speed, ETA and active-worker information;
- never adds module branding to user-visible notification text;
- never posts a second notification.

Domo/ColorOS adaptation is a renderer over the same notification state. A SystemUI adapter is loaded only when the DownloadProvider-side notification is insufficient for the current ROM.

## IPC security

Sockets:

```text
/data/adb/sda/run/control.sock
/data/adb/sda/run/plugin.sock
```

Controls:

- `SO_PEERCRED` verification;
- optional SELinux peer context verification;
- protocol-version handshake;
- challenge-response session establishment;
- explicit capability grants;
- bounded queues and message sizes;
- default deny for plugin mutations.

Initial plugin capabilities:

```text
task.read
task.observe
task.pause
task.resume
task.cancel
task.modify_policy
task.claim_engine
config.read
config.write
diagnostics.read
runtime.reload
```

## Failure model

The production rule is fail-open.

- hook discovery failure: do not replace execution;
- daemon unavailable: continue with local conservative policy or pass through;
- protocol mismatch: pass through;
- range probe failure: call original implementation;
- segment consistency failure before finalization: cancel Rust workers and fall back when safe;
- repeated runtime-start failures: daemon enables safe mode;
- notification renderer failure: keep the untouched original notification.

No component may call `cancelAll()` on DownloadProvider notifications.

## Build outputs

```text
module.prop
post-fs-data.sh
service.sh
uninstall.sh
sepolicy.rule
zygisk/arm64-v8a.so
bin/arm64/sdad
bin/arm64/sdactl
dex/sda_bridge.dex
webroot/index.html
webroot/assets/*
config/default.json
compat/*.json
```

The first device target is Android 16, arm64-v8a, kernel 6.6, with the uploaded ColorOS DownloadProvider and SystemUI builds.

## Implementation phases

1. workspace, protocol and daemon skeleton;
2. standard Zygisk target matching and companion-FD bootstrap;
3. ROM-specific Java hook discovery with fail-open logging;
4. original-notification in-place observation and decoration;
5. fake segmented state, no network replacement;
6. Range probe and single managed segment;
7. multi-segment scheduler and positional writes;
8. durable checkpoints and resume;
9. WebUI and plugin API;
10. optional SystemUI Domo adapter.

# Architecture

## Scope

The module is split into three Android surfaces:

- `com.android.providers.downloads` — transfer engine and source of truth
- `com.android.providers.downloads.ui` — download list/detail/control surface
- `com.android.systemui` — optional notification/status presentation

Android 16 (API 36) and Android 17 (API 37.0) are the first targets.

`android` / `system_server` is deliberately **not** in the LSPosed scope. Network,
power, metered-state and thermal information should first be consumed through public
framework APIs from the Provider process. A system_server hook is only justified if
device testing proves a framework policy cannot otherwise be observed or controlled.

## Ownership rule

```text
DownloadProvider = source of truth
Downloads UI     = view + user controls
SystemUI         = presentation only
```

UI processes must not maintain an independent authoritative copy of download state.

## Current hook policy

### DownloadProvider

The Provider layer currently hooks/probes `DownloadThread`:

- `run`
- `executeDownload`
- `transferData`
- `addRequestHeaders`

It remains observation-only. All hooks use protective exception mode and call through to
the original implementation.

### Downloads UI

The UI layer currently performs class-capability discovery only. No private UI method is
intercepted yet. This gives us a safe OxygenOS/AOSP compatibility signal before we add
download detail controls.

### SystemUI

SystemUI is also capability-probe-only. We verify that the module is injected and inspect
whether known notification-pipeline classes exist. No notification pipeline method is
intercepted yet.

This is intentional: a broken Provider hook can break downloading; a broken SystemUI hook
can destabilize the whole visible system shell.

## Planned transfer pipeline

```text
DownloadManager.enqueue()
        |
DownloadProvider / DownloadThread
        |
 capability probe
        |
        +-- unsafe / unsupported --> original DownloadThread
        |
        +-- safe --> adaptive scheduler
                       |
                       +-- 1/2/4/6/8 segment workers
                       +-- positional writes
                       +-- aggregated progress
                       +-- native cancellation/resume
                       +-- native completion semantics
                                |
                                +--> Downloads UI
                                +--> SystemUI notification surface
```

## Adaptive worker ceiling

The first scheduler policy is conservative:

- < 8 MiB: 1 worker
- 8–32 MiB: up to 2 workers
- 32–256 MiB: up to 4 workers
- large unmetered Wi-Fi/Ethernet: up to 8 workers
- large unmetered cellular: up to 6 workers
- VPN: up to 4 workers
- metered / power-save / high thermal state: up to 2 workers

This is a ceiling, not a target. Runtime throughput sampling should reduce concurrency when
one connection already saturates the path.

## Range eligibility

Parallel transfer is only eligible when all required checks pass:

- known content length
- valid byte-range behavior
- stable validator such as ETag
- no incompatible content encoding
- seekable destination
- file large enough for segmentation to be useful

Anything uncertain falls back to Android's original implementation.

## Cross-process state

A versioned bridge contract is reserved now, but no IPC implementation is enabled yet.
When added, Provider will publish immutable telemetry snapshots; UI layers will consume
those snapshots and send explicit control commands back. Provider remains authoritative.

## Root add-on

Kernel/TCP tuning remains optional and separate from the LSPosed APK. It must never become
a requirement for correct DownloadManager behavior.

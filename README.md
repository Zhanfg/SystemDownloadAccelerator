# SystemDownloadAccelerator

LSPosed module that accelerates Android's system DownloadManager while keeping
Android's own download records, destination handling, progress, pause/cancel,
resume, retry and completion semantics.

## Current architecture — 0.4

The project no longer depends on hooking private `DownloadThread.transferData()`
implementations.

The acceleration path is now:

```text
DownloadManager
      |
DownloadProvider / DownloadThread
      |
Android Network / URL connection creation
      |
request-header recorder
      |
HttpURLConnection.getInputStream()
      |
      +-- unsafe / unsupported --> original Android response stream
      |
      +-- eligible -------------> parallel Range core
                                      |
                                      +-- Range capability validation
                                      +-- adaptive worker count
                                      +-- micro-part scheduler
                                      +-- bounded reorder window
                                      +-- sequential accelerated InputStream
                                                   |
                                                   v
                                      native DownloadThread writer
```

Android remains the source of truth. The accelerator does not directly update
DownloadProvider's database or write the final destination out of order.

## AB Download Manager reference

The independent core is inspired by the architecture of
`amir1376/ab-download-manager`: download jobs own part state, Range workers are
independent, parts are load-balanced, and server resume support is validated.

This project uses a clean-room implementation adapted to Android DownloadProvider;
AB Download Manager source code is not vendored or copied.

See `docs/AB_REFERENCE.md`.

## LSPosed scopes

- `com.android.providers.downloads` — transfer adapter
- `com.android.providers.downloads.ui` — optional/on-demand diagnostics surface
- `com.android.systemui` — notification/system UI probe

libxposed API 102 hot reload is enabled.

## Safety policy

Parallel mode currently requires:

- Android 16+;
- at least 8 MiB remaining;
- an originating Android `Network`;
- identity/no content encoding;
- strong ETag or Last-Modified validator;
- valid byte-range behavior.

Anything uncertain falls back to Android's original response stream.

## In-app self-test

The app starts a localhost HTTP server and submits a 16 MiB request through the
real Android DownloadManager. A successful parallel run should show events such as:

```text
CORE_ARMED
CONNECTION
STREAM_INTERCEPT
RANGE_PROBE
RANGE_OK
PARTS
PARALLEL
STREAM_REPLACED
COMPLETE
```

The self-test server also reports the number of Range requests so acceleration is
verified by protocol behavior, not just by a green module status.

## Build

CI currently uses:

- AGP 9.4.0
- Gradle 9.6.0
- JDK 17
- Android 17.0 SDK (`compileSdk = 37`, `compileSdkMinor = 0`)

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

GitHub Actions artifact: `SystemDownloadAccelerator-debug`.

## One-tap diagnostics

The app includes a `生成诊断日志` button. It writes a shareable text report to:

`Download/SystemDownloadAccelerator/`

The report includes module/framework versions, scope and running targets, self-test
events, filtered Android logcat and (when root access is available) LSPosed module
logs and relevant process snapshots.

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


## Adaptive concurrency

Large downloads use throughput-driven concurrency rather than a fixed thread
count.

- start conservatively at 2-4 workers (8 only for very large, very fast links);
- compute a hard ceiling from file size, network kind, metered state, thermal
  state, power-save mode and Android's downstream-bandwidth hint;
- ramp by powers of two only when measured aggregate throughput still improves;
- stop adding workers when marginal gain falls below 12 percent;
- current hard maximum is 64 workers for very large files on suitable links;
- VPN/cellular, metered, thermal and low-bandwidth conditions reduce that ceiling.

Workers consume immutable ~8 MiB micro-parts from a shared queue, so the number
of work units is independent from the current worker count.


## Hot reload generations

API 102 hot reload performs an explicit generation handoff:

1. recover the target ClassLoader from the old generation;
2. unhook every old HookHandle;
3. abort reload if any stale hook could not be removed;
4. clear process-local generation state;
5. install the new generation with versioned hook IDs.

This prevents a target process from executing old and new interceptor logic at
the same time after an in-place module update.


## Real-network benchmark

The app includes an optional large-file benchmark that uses the real Android
DownloadManager path.

- paste an HTTP/HTTPS large-file direct URL;
- current and average throughput update about once per second;
- ADAPT_INIT/RAMP/HOLD/MAX events show the scheduler's worker decisions;
- active-network metering is checked before start and metered networks are
  rejected by default to avoid accidental cellular data usage;
- the test can be cancelled at any time;
- DownloadManager records and benchmark files are cleaned up automatically.

The localhost 16 MiB self-test remains the correctness test. It is intentionally
too small to exercise 32/64-worker scaling.

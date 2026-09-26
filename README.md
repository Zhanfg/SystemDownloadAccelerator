# SystemDownloadAccelerator

LSPosed module for accelerating Android's system DownloadManager without replacing the external DownloadManager API.

## Current phase

The first implementation is intentionally **probe-only**:

- Modern libxposed API 102
- static scope: `com.android.providers.downloads`
- Android 16 / 17 first
- discovers AOSP `DownloadThread` hook points at runtime
- records only sanitized transfer metadata
- preserves the original network and file path completely
- includes the range-policy and segment-planning core for the next phase

This gives us a safe device-validation baseline before enabling parallel byte-range transfer.

## Build

CI uses AGP 9.4.0, Gradle 9.6.0, JDK 17 and Android API 37.

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

The GitHub Actions artifact is named `SystemDownloadAccelerator-debug`.

## Device validation

After enabling the module for **Download Manager / Downloads** in LSPosed and restarting the target process/device, check logcat for:

```text
SysDlAccel
```

Expected baseline:

```text
module loaded
DownloadThread probe ready
download start {uri=https://example.com/…, ...}
```

Full URLs, query parameters, fragments and credentials are deliberately not logged.

## Next

1. Validate AOSP/OxygenOS Android 16 hook signatures.
2. Add HTTP capability probing.
3. Add guarded 2/4/6/8-way Range scheduling.
4. Preserve DownloadManager progress, resume, cancellation and completion semantics.
5. Add optional root-side network tuning separately.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

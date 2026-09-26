# SystemDownloadAccelerator

LSPosed module for accelerating Android's system DownloadManager while preserving the
system download lifecycle, UI compatibility and fallback behavior.

## Current phase

The foundation now covers three process surfaces:

- `com.android.providers.downloads`
- `com.android.providers.downloads.ui`
- `com.android.systemui`

Only **DownloadProvider** currently installs method hooks. Downloads UI and SystemUI use
capability probes only, so OEM UI differences cannot alter or crash the download path.

Current features:

- Modern libxposed API 102
- Android 16 / Android 17.0 first
- fail-closed DownloadThread probing
- sanitized transfer logging
- Range eligibility policy
- gap-free segment planner
- adaptive 1/2/4/6/8 worker ceiling
- reserved versioned Provider-to-UI bridge contract
- no system_server scope
- no root requirement

## Build

CI uses AGP 9.4.0, Gradle 9.6.0, JDK 17 and Android 17.0 SDK
(`compileSdk = 37`, `compileSdkMinor = 0`).

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

The GitHub Actions artifact is named `SystemDownloadAccelerator-debug`.

## Device validation

Enable the module for **Download Manager / Downloads / System UI** in LSPosed, then reboot
or restart the affected processes. Filter logcat by:

```text
SysDlAccel
```

Expected baseline includes:

```text
provider ready
downloads-ui ready
systemui ready
download start {uri=https://example.com/…, ...}
```

Full URLs, query parameters, fragments and credentials are deliberately not logged.

## Next

1. Validate all three scopes on OxygenOS / Android 16.
2. Add real HTTP `Range: bytes=0-0` capability probing.
3. Bind framework network/power/thermal state into the adaptive policy.
4. Add guarded segmented transfer and positional writes.
5. Publish Provider telemetry to Downloads UI / SystemUI through the bridge.
6. Preserve native progress, pause/resume, cancellation, retry and completion semantics.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

# Architecture

## Scope

Phase 1 targets the Android system DownloadProvider package:

- `com.android.providers.downloads`
- Android 16 (API 36) and Android 17 (API 37) first
- Modern libxposed API 102
- No system APK replacement
- No root-side TCP tuning in the base module

## Safety model

The module starts fail-closed:

1. Hook `DownloadThread` using reflection.
2. Observe method availability and sanitized download metadata.
3. Never log query strings, fragments, credentials, cookies or full paths.
4. If an OEM changes private fields or methods, skip that probe rather than changing host behavior.
5. Range acceleration only activates after capability checks prove it safe.

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
        +-- safe --> range scheduler
                       |
                       +-- segment workers
                       |
                       +-- positional writes
                       |
                       +-- original DownloadManager state / notification / broadcast
```

## Range eligibility

The future engine requires:

- known content length
- byte-range support
- stable validator (ETag or equivalent)
- no incompatible content encoding
- a file large enough for segmentation to help

Anything uncertain falls back to Android's original implementation.

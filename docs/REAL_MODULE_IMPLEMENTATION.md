# Real module implementation

This branch is being converted from the UI-only alpha into a real libxposed API 102 module.

## First hook target

- Package: `com.android.providers.downloads`
- Transfer classes: `com.android.providers.downloads.d` and `com.android.providers.downloads.e`
- Hook methods:
  - `u(HttpURLConnection)` to bind the active connection to the current transfer thread
  - `t(InputStream, OutputStream, FileDescriptor)` to replace the sequential copy loop when ranged downloading is safe
  - `s()` on the job-backed thread to propagate cancellation

## Safety rules

- DRM streams always use the original implementation.
- Unknown length, invalid `Content-Range`, HTTP 200 for a ranged request, ETag changes, or worker failure fall back to the original system path.
- Concurrent writes use positional writes and never share a mutable seek position.
- System progress is updated through the vendor thread state and its existing progress-flush method.

## Required verification

1. LSPosed reports API 102 entry loaded in `com.android.providers.downloads`.
2. Hook installation succeeds for both transfer classes.
3. A range-capable server receives multiple non-overlapping requests.
4. Final file hash matches a single-thread reference download.
5. Cancellation, pause, network switching, and non-range fallback do not crash the download service.

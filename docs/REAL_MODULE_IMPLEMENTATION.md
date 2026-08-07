# Real module implementation

## Active target

- Package: `com.android.providers.downloads`
- Transfer classes: `com.android.providers.downloads.d` and `com.android.providers.downloads.e`
- Hook methods:
  - `g(HttpURLConnection)` — capture the original request Range state after ColorOS configures it;
  - `u(HttpURLConnection)` — bind the active base connection to the current transfer thread;
  - `t(InputStream, OutputStream, FileDescriptor)` — replace only the input side with a bounded parallel Range stream;
  - `s()` — propagate DownloadProvider cancellation to worker connections.

## Alpha 15 safety model

The accelerator does not write the destination file directly. Parallel workers spool validated byte ranges into the DownloadProvider private cache. The vendor copy method remains the only destination writer.

Acceleration is allowed only when all of these conditions hold:

1. the original Range request state was captured reliably;
2. a fresh HTTP 200 response has no original Range request;
3. an HTTP 206 response has exactly one original Range request and the response start equals the requested start;
4. a resume request that is downgraded to HTTP 200 is rejected;
5. the response is identity encoded and has a known total size;
6. the resource has a strong ETag or stable Last-Modified validator;
7. a one-byte probe returns exact HTTP 206 / Content-Range / validator data;
8. temporary spool capacity satisfies the bounded prefetch budget plus 64 MiB reserve.

If any preflight rule fails, the original system stream is used before accelerated bytes are exposed.

## Worker model

- 2–16 workers;
- 4–64 MiB chunk size;
- bounded prefetch window;
- contiguous chunk geometry comes from the unit-tested `RangeProtocol` helper;
- each worker verifies HTTP 206, exact `Content-Range`, stable validator, identity encoding and body length;
- ColorOS connection configuration calls are serialized on a module-owned lock, never the vendor transfer object monitor;
- spool directories live under `Context.getCacheDir()/sda-range-spool` and stale sessions are cleaned opportunistically.

## Automated invariants

`RangeProtocolTest` covers the byte-window rules independently of Android/Xposed runtime code, including HTTP 200 resume downgrade, HTTP 206 start mismatch, ambiguous/malformed ranges, thread caps and chunk boundaries.

## Required device verification

1. LSPosed API 102 entry loads in `com.android.providers.downloads`.
2. Hook installation succeeds for the current ColorOS `d/e` transfer classes.
3. Fresh downloads and resumed downloads both preserve exact file hashes.
4. A server that ignores a resume Range and returns HTTP 200 causes system fallback, not accelerated output.
5. A range-capable server receives multiple non-overlapping requests.
6. Cancellation, pause, network switching, DownloadProvider restart and reboot preserve valid system retry/resume behavior.
7. Private spool usage remains bounded and is cleaned after completion/failure/cancellation.

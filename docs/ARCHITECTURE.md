# Architecture

## Design goal

Accelerate Android DownloadManager without replacing the system download lifecycle.

The previous prototype tried to intercept private `DownloadThread.transferData`
methods. That approach is intentionally retired: OEM forks, ART inlining and
HttpEngine changes make those private call sites too fragile.

## Layer 1 — System adapter

The LSPosed module keeps a narrow system-facing surface:

1. `DownloadThread.run()` establishes the current Provider execution context.
2. Android `Network.openConnection()`, `URL.openConnection()`, and detected
   HttpEngine connection factories are observed.
3. For a created `HttpURLConnection`, request headers are recorded.
4. The concrete connection's `getInputStream()` is the body interception point.

No private transfer helper is required for acceleration.

Worker-created Range requests are marked internal with a ThreadLocal bypass, so
they never recursively enter the accelerator.

## Layer 2 — Independent Range core

The core follows a job/part model inspired by AB Download Manager.

### RangePart

Each part has immutable inclusive `from..to` bounds.

Unlike the first prototype, the live HTTP boundary of an active part is never
mutated. This avoids races between an in-flight HTTP response and a scheduler
changing its end offset.

### MicroPartPlanner

There are more parts than workers (normally four parts per worker, capped at 64).
When a worker finishes, it claims another micro-part.

This provides dynamic load balancing similar to runtime part splitting while
keeping each request immutable and easy to validate.

### Range validation

For a new 200 response, the core makes a small `bytes=0-255` request. A valid
206 response must have a parseable Content-Range with the same total entity size.

For a resumed native 206 response, its Content-Range already proves that the
server accepted a Range request.

Strong ETag is preferred; Last-Modified is accepted as a fallback validator.
All worker requests use `If-Range`.

### Bounded reorder window

Workers prefetch parts into per-part temporary files under the DownloadProvider
cache directory.

The scheduler limits how many parts may be ahead of the part currently consumed.
This prevents the accelerator from duplicating an entire large download in cache.

The accelerated InputStream reads those temporary parts in byte order and deletes
each part immediately after consumption.

## Layer 3 — Native state bridge

This is the most important compatibility property.

The range core does **not** write the user's final destination directly.

Android's original DownloadThread consumes the accelerated InputStream and remains
responsible for:

- final destination writes;
- fsync;
- `currentBytes` and resumable contiguous progress;
- DownloadProvider database updates;
- pause/cancel/shutdown checks;
- retry policy;
- notification progress;
- final success/error status;
- permission/finalization behavior.

If the range core fails while being consumed, its InputStream throws an IOException
and Android handles it through the normal retry/error path.

## Network inheritance

Worker connections reuse:

- the originating Android `Network`;
- caller request headers recorded before connection;
- final redirected URL;
- native connect/read timeouts;
- HTTPS socket factory from the original connection.

The accelerator overrides only headers it owns: Range, If-Range and
Accept-Encoding.

## Concurrency

Worker count remains adaptive. Metered networks, power-save mode and thermal state
cap concurrency.

Micro-parts are separate from workers: two workers may process eight parts over
the lifetime of one transfer.

## Process death

No custom part database is required for correctness.

The system writer only advances DownloadProvider progress for bytes it has consumed
sequentially. If DownloadProvider dies, Android's own persisted contiguous offset is
still valid. On resume, a new range session is created starting from that native
offset.

## Future optimizations

Once the stream adapter is proven on-device:

- per-host concurrency budgets;
- 429/503 Retry-After backoff;
- adaptive part sizing from measured throughput;
- cache-window sizing from storage pressure;
- HTTP/2/HTTP/3-aware connection strategy;
- optional direct-offset destination writer only where Android exposes a stable
  transaction boundary.

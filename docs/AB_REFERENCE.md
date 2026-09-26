# AB Download Manager reference

Upstream: https://github.com/amir1376/ab-download-manager

License: Apache License 2.0.

This project uses AB Download Manager as an architectural reference only. No AB
source files are vendored into SystemDownloadAccelerator.

## Upstream concepts reviewed

The following upstream files informed the redesign:

- `downloader/core/.../downloaditem/http/HttpDownloadJob.kt`
  - owns the task state;
  - restores saved ranged parts;
  - determines concurrent support;
  - creates/schedules part downloaders;
  - persists part state.

- `downloader/core/.../part/RangedPart.kt`
  - tracks `from`, `to`, and current position per part.

- `downloader/core/.../part/HttpPartDownloader.kt`
  - creates an independent Range connection per part;
  - validates response position/length;
  - retries part failures.

- `downloader/core/.../part/PartSplitSupport.kt`
  - dynamically splits remaining work so idle workers can help slower parts.

- `downloader/core/.../connection/HttpDownloaderClient.kt`
  - forces identity encoding;
  - probes byte-range behavior before declaring resume support.

- `downloader/core/.../destination/DestWriter.kt`
  - gives each part an independent seek position.

## What we adopt

- task / part / transport separation;
- range capability validation;
- more work units than active workers;
- independent retries;
- strict server response checks;
- identity encoding;
- adaptive concurrency.

## What we deliberately change

AB owns the final output file and part database. SystemDownloadAccelerator must
coexist with Android DownloadProvider, so the final destination remains owned by
Android.

Instead of directly writing AB-style parts into the destination, our workers feed
a bounded reorder stream. DownloadThread then performs the authoritative sequential
write itself.

This costs some temporary I/O, but dramatically reduces coupling to private
DownloadProvider internals and preserves system resume semantics.

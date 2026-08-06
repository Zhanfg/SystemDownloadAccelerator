# SystemDownloadAccelerator

SystemDownloadAccelerator is an early LSPosed API 102 prototype for studying how Android's system DownloadProvider can be enhanced without replacing the system download service.

## Current status

- Early development;
- Android 16 and ColorOS adaptation is in progress;
- DownloadProvider entry points, task lifecycle, storage rules, and compatibility boundaries are still being verified;
- The project is not yet presented as a stable replacement or broadly compatible module.

## Target capabilities

- Configurable confirmation for suitable foreground download requests;
- Destination rules that remain compatible with Android storage and the original caller;
- Segmented-download strategies only when the server and entity metadata support reliable range requests;
- Explicit fallback to the system path when enhancement conditions are not met;
- Download history and diagnostics that explain network, server, permission, storage, and integrity failures.

These are engineering targets, not a declaration that every path is already implemented or validated.

## Design principle

The module should intervene only at controlled points and preserve the original DownloadManager / DownloadProvider lifecycle wherever possible. Enhancement failure must degrade to the system default behavior rather than leave callers with an inconsistent task state.

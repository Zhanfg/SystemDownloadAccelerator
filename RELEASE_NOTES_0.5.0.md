# SDA 0.5.0

First stable SDA release.

## Identity
- App name: SDA
- Android applicationId: `dev.axym.sda`
- Version: `0.5.0`
- versionCode: `27`
- LSPosed API: 102

## Highlights
- Android DownloadManager acceleration without replacing Android's download database/destination semantics.
- Parallel HTTP Range engine with bounded reorder buffering.
- Throughput-driven adaptive concurrency with dynamic ramp/rollback and a 64-worker ceiling for suitable large transfers.
- Conservative VPN/metered-network handling with physical-underlay assessment.
- Provider generation/build receipts and API 102 hot-reload self-heal.
- Local correctness self-test, real-network benchmark, pipeline telemetry and one-tap diagnostics.
- ColorOS/OnePlus path support through URL/HttpURLConnection interception instead of assuming AOSP DownloadThread exists.

## Migration
The stable package id is different from the alpha package id.

Before enabling SDA in LSPosed:
1. Disable the legacy `dev.axymorrsen.systemdownloadaccelerator` module.
2. Install SDA and enable its three scopes.
3. Verify the localhost self-test.
4. Uninstall the legacy alpha app after verification.

Do not leave both module packages enabled for the same scopes, because both could hook DownloadProvider/SystemUI concurrently.

## Release validation
The 0.5.0 release branch is built from the twice-green 0.5.0-alpha10 baseline and must pass:
- full JVM unit suite
- Android debug APK assembly
- artifact upload

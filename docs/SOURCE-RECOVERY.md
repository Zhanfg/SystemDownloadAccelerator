# Source recovery record

## Problem

The default branch retained workflows that referenced missing Android sources and missing scripts. The only complete Alpha 12 lineage was stored behind a split Base64 bootstrap and a chain of scripts that regenerated each historical Alpha before compiling it. Documentation-only pull requests therefore attempted an Android build that could never start from the default-branch tree.

## Recovery

The retained Alpha 12 migration chain was executed once in an isolated recovery branch. The resulting ordinary Gradle project was validated for its application ID, version, manifest, LSPosed API metadata and scope, then committed as normal source files.

The repair branch removes:

- `.bootstrap/` and all split encoded payloads;
- `.ci-*` trigger files;
- Alpha 7–12 source-generation scripts;
- bootstrap, historical Alpha and one-shot recovery workflows.

The repository now builds directly from `app/`, `build.gradle.kts` and `settings.gradle.kts`. `ci/validate-source.sh` prevents the encoded bootstrap and historical generators from returning.

## Preserved components

- Android application and LSPosed API 102 entry point;
- first-run Root/notification/battery setup;
- confirmation, history and Live Update bridges;
- Rust read-only diagnostic;
- Root wrapper module.

## Validation boundary

CI proves source consistency, Android compilation, lint execution, APK signature validity, Rust cross-compilation, module ZIP structure and SHA-256 generation. It does not prove compatibility with a specific ColorOS build or physical device.

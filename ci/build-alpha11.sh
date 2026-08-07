#!/usr/bin/env bash
set -euo pipefail

SAVE=/tmp/sda-alpha11-sources
rm -rf "$SAVE"
mkdir -p "$SAVE/xposed"
cp app/src/main/java/io/github/zhanfg/sda/SystemDownloadConfirmActivity.java "$SAVE/"
cp app/src/main/java/io/github/zhanfg/sda/xposed/SystemDownloadConfirmationModule.java "$SAVE/xposed/"
cp app/src/main/java/io/github/zhanfg/sda/xposed/HistoryMirrorModule.java "$SAVE/xposed/"

# Build the complete Alpha 10 Live Update lifecycle baseline first.
bash ci/build-alpha10.sh

SRC=app/src/main/java/io/github/zhanfg/sda
cp "$SAVE/SystemDownloadConfirmActivity.java" "$SRC/"
cp "$SAVE/xposed/SystemDownloadConfirmationModule.java" "$SRC/xposed/"
cp "$SAVE/xposed/HistoryMirrorModule.java" "$SRC/xposed/"

python3 - <<'PY'
from pathlib import Path
build = Path('app/build.gradle.kts')
text = build.read_text(encoding='utf-8')
text = text.replace('versionCode = 11', 'versionCode = 12')
text = text.replace('versionName = "0.2.8-alpha10"', 'versionName = "0.2.9-alpha11"')
build.write_text(text, encoding='utf-8')
PY

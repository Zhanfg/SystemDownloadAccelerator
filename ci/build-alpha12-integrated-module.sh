#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET=aarch64-linux-android
ANDROID_API="${ANDROID_API:-26}"
NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"

if [[ -z "$NDK_ROOT" || ! -d "$NDK_ROOT" ]]; then
  echo "ANDROID_NDK_HOME or ANDROID_NDK_ROOT must point to an Android NDK" >&2
  exit 1
fi

case "$(uname -s)" in
  Linux) HOST_TAG=linux-x86_64 ;;
  Darwin) HOST_TAG=darwin-x86_64 ;;
  *) echo "Unsupported build host: $(uname -s)" >&2; exit 1 ;;
esac

TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG"
CC="$TOOLCHAIN/bin/${TARGET}${ANDROID_API}-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
STRIP="$TOOLCHAIN/bin/llvm-strip"

for tool in "$CC" "$AR" "$STRIP" cargo zip unzip sha256sum; do
  if ! command -v "$tool" >/dev/null 2>&1 && [[ ! -x "$tool" ]]; then
    echo "Required build tool not found: $tool" >&2
    exit 1
  fi
done

cd "$ROOT"

# Build the existing Alpha exactly through its verified chain first.
bash ci/build-alpha12-fixed.sh

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="$AR"
cargo build \
  --manifest-path alpha-diagnostics/Cargo.toml \
  --release \
  --target "$TARGET"

DETECTOR="$ROOT/alpha-diagnostics/target/$TARGET/release/sda-alpha-detect"
test -s "$DETECTOR"
"$STRIP" --strip-unneeded "$DETECTOR"

APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
APK_SOURCE=""
for candidate in \
  "$ROOT/dist/SystemDownloadAccelerator-0.3.0-alpha12.apk" \
  "$ROOT/dist/SystemDownloadAccelerator-0.3.0-alpha12-debug.apk"; do
  if [[ -s "$candidate" ]] && "$APKSIGNER" verify "$candidate" >/dev/null 2>&1; then
    APK_SOURCE="$candidate"
    break
  fi
done
if [[ -z "$APK_SOURCE" ]]; then
  echo "Neither Alpha APK is signed and installable" >&2
  exit 1
fi

echo "Embedded APK: $APK_SOURCE"

STAGE="$ROOT/out/alpha-module"
rm -rf "$STAGE"
mkdir -p "$STAGE/bin" "$STAGE/apk" "$STAGE/META-INF/com/google/android"
cp -a "$ROOT/alpha-module/." "$STAGE/"
cp "$DETECTOR" "$STAGE/bin/sda-alpha-detect"
cp "$APK_SOURCE" "$STAGE/apk/SystemDownloadAccelerator.apk"

cat > "$STAGE/META-INF/com/google/android/update-binary" <<'INSTALLER'
#!/sbin/sh
umask 022
OUTFD=$2
ZIPFILE=$3
ui_print() { echo "$1"; }
abort() { ui_print "$1"; exit 1; }
mount /data 2>/dev/null
if [ -f /data/adb/magisk/util_functions.sh ]; then
  . /data/adb/magisk/util_functions.sh
  install_module
  exit $?
fi
abort "Install this module from Magisk, KernelSU or APatch manager."
INSTALLER
printf '#MAGISK\n' > "$STAGE/META-INF/com/google/android/updater-script"

chmod 0755 \
  "$STAGE/customize.sh" \
  "$STAGE/post-fs-data.sh" \
  "$STAGE/service.sh" \
  "$STAGE/action.sh" \
  "$STAGE/uninstall.sh" \
  "$STAGE/bin/sda-alpha-detect" \
  "$STAGE/META-INF/com/google/android/update-binary"
chmod 0644 \
  "$STAGE/module.prop" \
  "$STAGE/apk/SystemDownloadAccelerator.apk" \
  "$STAGE/META-INF/com/google/android/updater-script"

MODULE_ZIP="$ROOT/dist/SystemDownloadAccelerator-0.3.0-alpha12-module.zip"
rm -f "$MODULE_ZIP"
(
  cd "$STAGE"
  zip -qr "$MODULE_ZIP" .
)

unzip -Z1 "$MODULE_ZIP" | grep -qx 'module.prop'
unzip -Z1 "$MODULE_ZIP" | grep -qx 'action.sh'
unzip -Z1 "$MODULE_ZIP" | grep -qx 'bin/sda-alpha-detect'
unzip -Z1 "$MODULE_ZIP" | grep -qx 'apk/SystemDownloadAccelerator.apk'

(
  cd "$ROOT/dist"
  sha256sum \
    SystemDownloadAccelerator-0.3.0-alpha12-debug.apk \
    SystemDownloadAccelerator-0.3.0-alpha12.apk \
    SystemDownloadAccelerator-0.3.0-alpha12-module.zip \
    > checksums.txt
)

printf 'Built %s\n' "$MODULE_ZIP"

#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${VERSION:-0.3.0-alpha14}"
TARGET=aarch64-linux-android
ANDROID_API="${ANDROID_API:-26}"
NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
GRADLE_BIN="${GRADLE_BIN:-gradle}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-35.0.0}"

cd "$ROOT"
bash ci/validate-source.sh

if [ "${SKIP_ANDROID_BUILD:-0}" != "1" ]; then
  "$GRADLE_BIN" --no-daemon --stacktrace \
    :app:assembleDebug :app:assembleRelease :app:lintDebug
fi

DEBUG_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
[ -s "$DEBUG_APK" ] || { echo "Debug APK missing: $DEBUG_APK" >&2; exit 1; }
[ -s "$RELEASE_APK" ] || { echo "Release APK missing: $RELEASE_APK" >&2; exit 1; }

APKSIGNER="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/apksigner"
[ -x "$APKSIGNER" ] || { echo "apksigner missing: $APKSIGNER" >&2; exit 1; }
"$APKSIGNER" verify --verbose "$DEBUG_APK"
"$APKSIGNER" verify --verbose "$RELEASE_APK"

verify_xposed_metadata() {
  local apk="$1"
  local actual expected

  expected="$(printf '%s\n' \
    'io.github.zhanfg.sda.ModuleMain' \
    'io.github.zhanfg.sda.xposed.SystemDownloadConfirmationModule' \
    'io.github.zhanfg.sda.xposed.HistoryMirrorModule')"
  actual="$(unzip -p "$apk" META-INF/xposed/java_init.list | tr -d '\r')"
  [ "$actual" = "$expected" ] || {
    echo "Unsafe libxposed entry list in $apk" >&2
    printf 'Expected:\n%s\nActual:\n%s\n' "$expected" "$actual" >&2
    exit 1
  }

  actual="$(unzip -p "$apk" META-INF/xposed/scope.list | tr -d '\r' | sed '/^[[:space:]]*$/d')"
  [ "$actual" = 'com.android.providers.downloads' ] || {
    echo "Unsafe LSPosed scope in $apk: $actual" >&2
    exit 1
  }

  unzip -p "$apk" META-INF/xposed/module.prop | tr -d '\r' \
    | grep -qx 'minApiVersion=102'
  unzip -p "$apk" META-INF/xposed/module.prop | tr -d '\r' \
    | grep -qx 'targetApiVersion=102'
}

verify_xposed_metadata "$DEBUG_APK"
verify_xposed_metadata "$RELEASE_APK"

if [ -z "$NDK_ROOT" ] || [ ! -d "$NDK_ROOT" ]; then
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
  if ! command -v "$tool" >/dev/null 2>&1 && [ ! -x "$tool" ]; then
    echo "Required build tool not found: $tool" >&2
    exit 1
  fi
done

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="$AR"
cargo build \
  --manifest-path alpha-diagnostics/Cargo.toml \
  --release \
  --target "$TARGET"

DETECTOR="$ROOT/alpha-diagnostics/target/$TARGET/release/sda-alpha-detect"
[ -s "$DETECTOR" ] || { echo "Rust detector missing" >&2; exit 1; }
"$STRIP" --strip-unneeded "$DETECTOR"

DIST="$ROOT/dist"
STAGE="$ROOT/out/alpha-module"
rm -rf "$DIST" "$STAGE"
mkdir -p "$DIST" "$STAGE/bin" "$STAGE/apk" "$STAGE/META-INF/com/google/android"

DEBUG_OUT="$DIST/SystemDownloadAccelerator-$VERSION-debug.apk"
RELEASE_OUT="$DIST/SystemDownloadAccelerator-$VERSION.apk"
cp "$DEBUG_APK" "$DEBUG_OUT"
cp "$RELEASE_APK" "$RELEASE_OUT"
cp -a "$ROOT/alpha-module/." "$STAGE/"
cp "$DETECTOR" "$STAGE/bin/sda-alpha-detect"
cp "$RELEASE_APK" "$STAGE/apk/SystemDownloadAccelerator.apk"

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

MODULE_ZIP="$DIST/SystemDownloadAccelerator-$VERSION-module.zip"
(
  cd "$STAGE"
  zip -qr "$MODULE_ZIP" .
)

unzip -t "$MODULE_ZIP" >/dev/null
unzip -Z1 "$MODULE_ZIP" | grep -qx 'module.prop'
unzip -Z1 "$MODULE_ZIP" | grep -qx 'action.sh'
unzip -Z1 "$MODULE_ZIP" | grep -qx 'bin/sda-alpha-detect'
unzip -Z1 "$MODULE_ZIP" | grep -qx 'apk/SystemDownloadAccelerator.apk'

TMP_EMBEDDED="$ROOT/out/embedded-release.apk"
unzip -p "$MODULE_ZIP" apk/SystemDownloadAccelerator.apk > "$TMP_EMBEDDED"
cmp -s "$RELEASE_APK" "$TMP_EMBEDDED" || {
  echo "Embedded module APK differs from the verified release APK" >&2
  exit 1
}
verify_xposed_metadata "$TMP_EMBEDDED"

(
  cd "$DIST"
  sha256sum \
    "$(basename "$DEBUG_OUT")" \
    "$(basename "$RELEASE_OUT")" \
    "$(basename "$MODULE_ZIP")" \
    > SHA256SUMS.txt
)

printf 'Built direct-source artifacts in %s\n' "$DIST"

#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="aarch64-linux-android"
ANDROID_API="${ANDROID_API:-26}"
NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"

if [[ -z "$NDK_ROOT" || ! -d "$NDK_ROOT" ]]; then
  echo "ANDROID_NDK_HOME or ANDROID_NDK_ROOT must point to an Android NDK" >&2
  exit 1
fi

case "$(uname -s)" in
  Linux) HOST_TAG="linux-x86_64" ;;
  Darwin) HOST_TAG="darwin-x86_64" ;;
  *) echo "Unsupported build host: $(uname -s)" >&2; exit 1 ;;
esac

TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG"
CC="$TOOLCHAIN/bin/${TARGET}${ANDROID_API}-clang"
CXX="$TOOLCHAIN/bin/${TARGET}${ANDROID_API}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"

for tool in "$CC" "$CXX" "$AR" cargo rustc zip; do
  if ! command -v "$tool" >/dev/null 2>&1 && [[ ! -x "$tool" ]]; then
    echo "Required build tool not found: $tool" >&2
    exit 1
  fi
done

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="$AR"
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"

cd "$ROOT"
cargo build --release --target "$TARGET" --lib --bin rzguestd --bin rzctl

RUST_OUT="$ROOT/target/$TARGET/release"
STATIC_LIB="$RUST_OUT/librz_runtime.a"
DAEMON="$RUST_OUT/rzguestd"
CTL="$RUST_OUT/rzctl"

for artifact in "$STATIC_LIB" "$DAEMON" "$CTL"; do
  if [[ ! -f "$artifact" ]]; then
    echo "Missing Rust artifact: $artifact" >&2
    exit 1
  fi
done

OUT="$ROOT/out"
STAGE="$OUT/module"
DIST="$ROOT/dist"
rm -rf "$OUT" "$DIST"
mkdir -p "$STAGE/zygisk" "$STAGE/bin" "$DIST"
cp -a "$ROOT/module/." "$STAGE/"

"$CXX" \
  --sysroot="$TOOLCHAIN/sysroot" \
  -std=c++20 \
  -O2 \
  -fPIC \
  -fvisibility=hidden \
  -fno-exceptions \
  -fno-rtti \
  -ffunction-sections \
  -fdata-sections \
  -shared \
  "$ROOT/native/zygisk_shim.cpp" \
  "$STATIC_LIB" \
  -Wl,--gc-sections \
  -Wl,--exclude-libs,ALL \
  -Wl,--no-undefined \
  -Wl,--build-id=sha1 \
  -Wl,-z,max-page-size=16384 \
  -pthread -ldl -llog -lm \
  -o "$STAGE/zygisk/arm64-v8a.so"

cp "$DAEMON" "$STAGE/bin/rzguestd"
cp "$CTL" "$STAGE/bin/rzctl"
chmod 0755 \
  "$STAGE/post-fs-data.sh" \
  "$STAGE/service.sh" \
  "$STAGE/uninstall.sh" \
  "$STAGE/bin/rzguestd" \
  "$STAGE/bin/rzctl"
chmod 0644 "$STAGE/zygisk/arm64-v8a.so" "$STAGE/module.prop"

MODULE_ZIP="$DIST/Rust-Zygisk-Runtime-Guest-v0.1.0.zip"
(
  cd "$STAGE"
  zip -qr "$MODULE_ZIP" .
)
sha256sum "$MODULE_ZIP" | tee "$DIST/SHA256SUMS.txt"

printf 'Built %s\n' "$MODULE_ZIP"

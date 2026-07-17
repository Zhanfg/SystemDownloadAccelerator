#!/system/bin/sh

MODDIR=${0%/*}
BIN="$MODDIR/bin/sda-alpha-detect"
DATA_DIR=/data/adb/sda-alpha
STAMP="$(date '+%Y%m%d_%H%M%S')"
OUT_DIR=/sdcard/Download

mkdir -p "$OUT_DIR" 2>/dev/null
if [ ! -d "$OUT_DIR" ] || [ ! -w "$OUT_DIR" ]; then
  OUT_DIR=/data/local/tmp
  mkdir -p "$OUT_DIR"
fi
OUT_FILE="$OUT_DIR/SDA_Alpha_Diagnostics_$STAMP.txt"

{
  echo "============================================================"
  echo "System Download Accelerator Alpha integrated diagnostics"
  echo "============================================================"
  echo "time=$(date '+%F %T %z')"
  echo "module=$MODDIR"
  echo "report=$OUT_FILE"
  echo "mode=one-shot-read-only"
  echo

  echo "[rust-detector]"
  if [ -x "$BIN" ]; then
    "$BIN" --module "$MODDIR" 2>&1 || echo "detector_exit=$?"
  else
    echo "detector_missing=$BIN"
  fi
  echo

  echo "[embedded-apk-install-state]"
  cat "$DATA_DIR/state/apk_install_status.txt" 2>/dev/null || echo "state=missing"
  echo

  echo "[installed-alpha-package]"
  pm path io.github.zhanfg.sda 2>&1 || true
  dumpsys package io.github.zhanfg.sda 2>/dev/null \
    | grep -E 'versionName=|versionCode=|codePath=|userId=|pkgFlags=|firstInstallTime=|lastUpdateTime=' \
    | head -n 80 || true
  echo

  echo "[root-framework]"
  magisk -V 2>/dev/null | sed 's/^/magisk_version_code=/' || true
  ksud -V 2>/dev/null | sed 's/^/kernelsu=/' || true
  apd --version 2>/dev/null | sed 's/^/apatch=/' || true
  getenforce 2>/dev/null | sed 's/^/selinux=/' || true
  uname -a 2>/dev/null | sed 's/^/kernel=/' || true
  echo

  echo "[download-notification]"
  dumpsys notification --noredact 2>/dev/null \
    | grep -n -E 'com.android.providers.downloads|DownloadProvider|channelId=active|tag=1:' \
    | head -n 240 || true
  echo

  echo "[recent-runtime-log]"
  logcat -d -v time -t 1200 2>/dev/null \
    | grep -E 'SystemDownloadAccelerator|SDA[-_ ]|SDA/|LSPosed|libxposed|Zygisk|DownloadProvider|com.android.providers.downloads' \
    | tail -n 320 || true
  echo

  echo "[module-install-log]"
  tail -n 120 "$DATA_DIR/log/install.log" 2>/dev/null || echo "install_log=missing"
  echo
  echo "diagnostics_complete=true"
} | tee "$OUT_FILE"

chmod 0644 "$OUT_FILE" 2>/dev/null
sync

echo
echo "检测完成。报告已保存："
echo "$OUT_FILE"

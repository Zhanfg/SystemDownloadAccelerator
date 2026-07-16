#!/system/bin/sh

MODDIR=${0%/*}
CTL="$MODDIR/bin/rzctl"
OUT_DIR=/sdcard/Download
STAMP="$(date '+%Y%m%d_%H%M%S')"
OUT_FILE="$OUT_DIR/rzruntime_doctor_$STAMP.txt"

mkdir -p "$OUT_DIR"

{
  echo "Rust Zygisk Runtime diagnostics"
  echo "time=$(date '+%F %T %z')"
  echo "module=$MODDIR"
  echo
  echo "[status]"
  "$CTL" status 2>&1 || true
  echo
  echo "[host]"
  "$CTL" host 2>&1 || true
  echo
  echo "[doctor]"
  "$CTL" doctor 2>&1 || true
  echo
  echo "[safe-mode]"
  "$CTL" safe-mode status 2>&1 || true
  echo
  echo "[supervisor-state]"
  cat /data/adb/rzruntime/state/supervisor.json 2>/dev/null || echo "missing"
  echo
  echo "[fallback-state]"
  cat /data/adb/rzruntime/state/last_guest.txt 2>/dev/null || echo "missing"
  echo
  echo "[recent-log]"
  tail -n 120 /data/adb/rzruntime/log/rzruntime.log 2>/dev/null || echo "missing"
} | tee "$OUT_FILE"

chmod 0644 "$OUT_FILE" 2>/dev/null

echo
echo "Saved: $OUT_FILE"

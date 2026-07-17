#!/system/bin/sh

MODDIR=${0%/*}
DATA_DIR=/data/adb/sda-alpha
LOG_FILE="$DATA_DIR/log/install.log"
STATE_FILE="$DATA_DIR/state/apk_install_status.txt"
APK="$MODDIR/apk/SystemDownloadAccelerator.apk"
PACKAGE=io.github.zhanfg.sda

mkdir -p "$DATA_DIR/log" "$DATA_DIR/state"
chmod 0700 "$DATA_DIR" "$DATA_DIR/log" "$DATA_DIR/state"

log_line() {
  echo "$(date '+%F %T %z') $*" >> "$LOG_FILE"
}

# Wait for Android's package service. The detector itself is never started here.
COUNT=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$COUNT" -lt 180 ]; do
  sleep 2
  COUNT=$((COUNT + 1))
done

if pm path "$PACKAGE" >/dev/null 2>&1; then
  log_line "package already installed; preserving existing APK and app data"
  {
    echo "status=already-installed"
    echo "timestamp=$(date '+%s')"
  } > "$STATE_FILE"
  chmod 0600 "$STATE_FILE"
  exit 0
fi

if [ ! -s "$APK" ]; then
  log_line "embedded APK missing"
  {
    echo "status=embedded-apk-missing"
    echo "timestamp=$(date '+%s')"
  } > "$STATE_FILE"
  chmod 0600 "$STATE_FILE"
  exit 0
fi

RESULT="$(pm install -r -d --user 0 "$APK" 2>&1)"
CODE=$?
if [ "$CODE" -ne 0 ]; then
  log_line "user-scoped install failed: $RESULT"
  RESULT="$(pm install -r -d "$APK" 2>&1)"
  CODE=$?
fi

if [ "$CODE" -eq 0 ]; then
  log_line "embedded Alpha APK installed: $RESULT"
  STATUS=installed
else
  log_line "embedded Alpha APK install failed: $RESULT"
  STATUS=install-failed
fi

{
  echo "status=$STATUS"
  echo "timestamp=$(date '+%s')"
  echo "result=$RESULT"
} > "$STATE_FILE"
chmod 0600 "$STATE_FILE"

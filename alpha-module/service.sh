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

write_state() {
  {
    echo "status=$1"
    echo "timestamp=$(date '+%s')"
    echo "installed_version_code=${2:-unknown}"
    echo "embedded_version_code=${3:-unknown}"
    [ -n "${4:-}" ] && echo "result=$4"
  } > "$STATE_FILE"
  chmod 0600 "$STATE_FILE"
}

COUNT=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$COUNT" -lt 180 ]; do
  sleep 2
  COUNT=$((COUNT + 1))
done

if [ ! -s "$APK" ]; then
  log_line "embedded APK missing"
  write_state embedded-apk-missing
  exit 0
fi

EMBEDDED_CODE="$(sed -n 's/^appVersionCode=//p' "$MODDIR/module.prop" | head -n 1)"
case "$EMBEDDED_CODE" in
  ''|*[!0-9]*)
    log_line "invalid embedded appVersionCode: $EMBEDDED_CODE"
    write_state invalid-embedded-version unknown "$EMBEDDED_CODE"
    exit 0
    ;;
esac

INSTALLED_CODE=""
if pm path "$PACKAGE" >/dev/null 2>&1; then
  INSTALLED_CODE="$(dumpsys package "$PACKAGE" 2>/dev/null \
    | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' \
    | head -n 1)"
fi

case "$INSTALLED_CODE" in
  *[!0-9]*) INSTALLED_CODE="" ;;
esac

if [ -n "$INSTALLED_CODE" ] && [ "$INSTALLED_CODE" -ge "$EMBEDDED_CODE" ]; then
  log_line "installed APK is current or newer: installed=$INSTALLED_CODE embedded=$EMBEDDED_CODE"
  write_state up-to-date "$INSTALLED_CODE" "$EMBEDDED_CODE"
  exit 0
fi

RESULT="$(pm install --user 0 -r "$APK" 2>&1)"
CODE=$?
if [ "$CODE" -ne 0 ]; then
  log_line "user-scoped install/update failed: $RESULT"
  RESULT="$(pm install -r "$APK" 2>&1)"
  CODE=$?
fi

if [ "$CODE" -eq 0 ]; then
  if [ -n "$INSTALLED_CODE" ]; then
    STATUS=updated
  else
    STATUS=installed
  fi
  log_line "embedded APK $STATUS: $RESULT"
else
  STATUS=install-failed
  log_line "embedded APK install/update failed: $RESULT"
fi

write_state "$STATUS" "${INSTALLED_CODE:-none}" "$EMBEDDED_CODE" "$RESULT"

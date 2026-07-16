#!/system/bin/sh

DATA_DIR=/data/adb/rzruntime
PID_FILE="$DATA_DIR/run/rzguestd.pid"
LOG_FILE="$DATA_DIR/log/rzguestd.log"
DAEMON="$MODDIR/bin/rzguestd"

mkdir -p "$DATA_DIR/run" "$DATA_DIR/state" "$DATA_DIR/log"
chmod 0700 "$DATA_DIR" "$DATA_DIR/run" "$DATA_DIR/state" "$DATA_DIR/log"

if [ -r "$PID_FILE" ]; then
  OLD_PID="$(cat "$PID_FILE" 2>/dev/null)"
  if [ -n "$OLD_PID" ] && [ -d "/proc/$OLD_PID" ]; then
    CMDLINE="$(tr '\000' ' ' < "/proc/$OLD_PID/cmdline" 2>/dev/null)"
    case "$CMDLINE" in
      *rzguestd*) kill "$OLD_PID" 2>/dev/null ;;
    esac
  fi
fi

rm -f "$DATA_DIR/run/control.sock" "$PID_FILE"

if [ -x "$DAEMON" ]; then
  "$DAEMON" >> "$LOG_FILE" 2>&1 &
else
  echo "$(date '+%F %T') rzguestd binary missing or not executable" >> "$LOG_FILE"
fi

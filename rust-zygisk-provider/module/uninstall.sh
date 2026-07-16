#!/system/bin/sh

DATA_DIR=/data/adb/rzruntime
PID_FILE="$DATA_DIR/run/rzguestd.pid"

if [ -r "$PID_FILE" ]; then
  PID="$(cat "$PID_FILE" 2>/dev/null)"
  if [ -n "$PID" ]; then
    kill "$PID" 2>/dev/null
  fi
fi

rm -rf "$DATA_DIR"

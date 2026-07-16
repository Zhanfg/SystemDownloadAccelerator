#!/system/bin/sh

MODDIR=${0%/*}
DATA_DIR=/data/adb/rzruntime
RUN_DIR="$DATA_DIR/run"
STATE_DIR="$DATA_DIR/state"
LOG_DIR="$DATA_DIR/log"
LOG_FILE="$LOG_DIR/rzruntime.log"
SUPERVISOR="$MODDIR/bin/rzsupervisord"

mkdir -p "$RUN_DIR" "$STATE_DIR" "$LOG_DIR"
chmod 0700 "$DATA_DIR" "$RUN_DIR" "$STATE_DIR" "$LOG_DIR"

stop_pid_file() {
  PID_FILE="$1"
  EXPECTED="$2"
  if [ ! -r "$PID_FILE" ]; then
    return
  fi
  PID="$(cat "$PID_FILE" 2>/dev/null)"
  if [ -z "$PID" ] || [ ! -d "/proc/$PID" ]; then
    rm -f "$PID_FILE"
    return
  fi
  CMDLINE="$(tr '\000' ' ' < "/proc/$PID/cmdline" 2>/dev/null)"
  case "$CMDLINE" in
    *"$EXPECTED"*)
      kill "$PID" 2>/dev/null
      sleep 1
      [ -d "/proc/$PID" ] && kill -9 "$PID" 2>/dev/null
      ;;
  esac
  rm -f "$PID_FILE"
}

stop_pid_file "$RUN_DIR/rzsupervisord.pid" rzsupervisord
stop_pid_file "$RUN_DIR/rzguestd.pid" rzguestd
rm -f "$RUN_DIR/control.sock"

if [ -f "$LOG_FILE" ]; then
  SIZE="$(wc -c < "$LOG_FILE" 2>/dev/null)"
  if [ -n "$SIZE" ] && [ "$SIZE" -gt 1048576 ]; then
    mv -f "$LOG_FILE" "$LOG_FILE.1"
  fi
fi

if [ -x "$SUPERVISOR" ]; then
  echo "$(date '+%F %T') starting Rust runtime supervisor" >> "$LOG_FILE"
  "$SUPERVISOR" >> "$LOG_FILE" 2>&1 &
else
  echo "$(date '+%F %T') rzsupervisord binary missing or not executable" >> "$LOG_FILE"
fi

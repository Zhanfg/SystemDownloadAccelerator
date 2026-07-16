#!/system/bin/sh

MODDIR=${0%/*}
DATA_DIR=/data/adb/rzruntime

mkdir -p "$DATA_DIR/run" "$DATA_DIR/state" "$DATA_DIR/log" "$DATA_DIR/config"
chmod 0700 "$DATA_DIR" "$DATA_DIR/run" "$DATA_DIR/state" "$DATA_DIR/log" "$DATA_DIR/config"

if [ ! -f "$DATA_DIR/config/runtime.conf" ] && [ -f "$MODDIR/config/default.conf" ]; then
  cp -f "$MODDIR/config/default.conf" "$DATA_DIR/config/runtime.conf"
fi
chmod 0600 "$DATA_DIR/config/runtime.conf" 2>/dev/null

rm -f "$DATA_DIR/run/control.sock"
cat /proc/sys/kernel/random/boot_id > "$DATA_DIR/state/boot_id" 2>/dev/null
chmod 0600 "$DATA_DIR/state/boot_id" 2>/dev/null

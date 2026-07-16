#!/system/bin/sh

DATA_DIR=/data/adb/rzruntime

mkdir -p "$DATA_DIR/run" "$DATA_DIR/state" "$DATA_DIR/log" "$DATA_DIR/config"
chmod 0700 "$DATA_DIR" "$DATA_DIR/run" "$DATA_DIR/state" "$DATA_DIR/log" "$DATA_DIR/config"

if [ ! -f "$DATA_DIR/config/runtime.conf" ] && [ -f "$MODDIR/config/default.conf" ]; then
  cp -f "$MODDIR/config/default.conf" "$DATA_DIR/config/runtime.conf"
  chmod 0600 "$DATA_DIR/config/runtime.conf"
fi

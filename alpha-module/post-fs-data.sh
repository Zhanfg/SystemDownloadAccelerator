#!/system/bin/sh

DATA_DIR=/data/adb/sda-alpha
mkdir -p "$DATA_DIR/log" "$DATA_DIR/state"
chmod 0700 "$DATA_DIR" "$DATA_DIR/log" "$DATA_DIR/state"

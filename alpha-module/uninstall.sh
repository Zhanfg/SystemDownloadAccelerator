#!/system/bin/sh

# Remove only this wrapper module's diagnostic state. The Alpha APK and its app data
# are intentionally preserved because the user may have installed or configured it separately.
rm -rf /data/adb/sda-alpha

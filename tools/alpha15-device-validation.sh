#!/system/bin/sh
# SystemDownloadAccelerator Alpha 15 real-device validation helper.
# Read-only except for its own state/output directories under /data/local/tmp and /sdcard/Download.

set -u

STATE_ROOT=/data/local/tmp/sda-alpha15-validation
OUT_ROOT=/sdcard/Download
SDA_PACKAGE=io.github.zhanfg.sda
DOWNLOADS_PACKAGE=com.android.providers.downloads
SAMPLE_INTERVAL=2

usage() {
  cat <<'EOF'
Usage:
  sh alpha15-device-validation.sh start [label]
  sh alpha15-device-validation.sh sample
  sh alpha15-device-validation.sh finish [downloaded_file] [expected_sha256]
  sh alpha15-device-validation.sh status

Recommended flow:
  1. su -c 'sh alpha15-device-validation.sh start fresh-range'
  2. Start one large system download and let it run for at least 20-30 seconds.
  3. su -c 'sh alpha15-device-validation.sh finish /sdcard/Download/file.bin EXPECTED_SHA256'

The script never deletes downloads or app data. It only removes its own previous sampler PID/state.
EOF
}

require_root() {
  uid="$(id -u 2>/dev/null || echo unknown)"
  if [ "$uid" != "0" ]; then
    echo "[ERROR] Root is required. Current uid=$uid" >&2
    exit 1
  fi
}

now_epoch() { date +%s; }
now_text() { date '+%Y-%m-%d %H:%M:%S %z'; }

safe_label() {
  echo "${1:-manual}" | tr -cd 'A-Za-z0-9._-' | cut -c1-48
}

active_dir() {
  if [ -s "$STATE_ROOT/active_dir" ]; then
    cat "$STATE_ROOT/active_dir"
  fi
}

package_uid() {
  pkg="$1"
  dumpsys package "$pkg" 2>/dev/null \
    | sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' \
    | head -n 1
}

package_version() {
  pkg="$1"
  dumpsys package "$pkg" 2>/dev/null \
    | grep -m1 -E 'versionName=|versionCode=' || true
}

find_spool_root() {
  for path in \
    /data/user/0/com.android.providers.downloads/cache/sda-range-spool \
    /data/data/com.android.providers.downloads/cache/sda-range-spool; do
    if [ -d "$path" ]; then
      echo "$path"
      return 0
    fi
  done
  return 1
}

capture_engine_state() {
  target="$1"
  for prefs in \
    /data/user/0/$SDA_PACKAGE/shared_prefs/module_settings.xml \
    /data/data/$SDA_PACKAGE/shared_prefs/module_settings.xml; do
    if [ -r "$prefs" ]; then
      {
        echo "prefs=$prefs"
        grep -E 'engine_status|engine_detail|engine_threads|engine_bytes|engine_updated_at|name=\"enabled\"|max_threads|initial_threads|min_size_mb|chunk_size_mb' "$prefs" || true
      } > "$target"
      return 0
    fi
  done
  echo "module_settings.xml unavailable" > "$target"
}

capture_spool() {
  target="$1"
  spool="$(find_spool_root 2>/dev/null || true)"
  {
    echo "timestamp=$(now_text)"
    echo "spool=${spool:-unavailable}"
    if [ -n "$spool" ] && [ -d "$spool" ]; then
      find "$spool" -maxdepth 2 -type f -printf '%p %s bytes\n' 2>/dev/null \
        || find "$spool" -maxdepth 2 -type f -exec ls -ln {} \; 2>/dev/null \
        || true
      du -sk "$spool" 2>/dev/null || true
    fi
  } > "$target"
}

capture_network() {
  target="$1"
  downloads_uid="$(package_uid "$DOWNLOADS_PACKAGE")"
  {
    echo "timestamp=$(now_text)"
    echo "downloads_uid=${downloads_uid:-unknown}"
    if command -v ss >/dev/null 2>&1; then
      ss -tnpe 2>/dev/null || ss -tnp 2>/dev/null || ss -tn 2>/dev/null || true
    else
      echo "ss unavailable"
      cat /proc/net/tcp 2>/dev/null || true
      cat /proc/net/tcp6 2>/dev/null || true
    fi
  } > "$target"
}

capture_packages() {
  dir="$1"
  {
    echo "captured_at=$(now_text)"
    echo "fingerprint=$(getprop ro.build.fingerprint)"
    echo "android_release=$(getprop ro.build.version.release)"
    echo "sdk=$(getprop ro.build.version.sdk)"
    echo "security_patch=$(getprop ro.build.version.security_patch)"
    echo "rom_display=$(getprop ro.build.display.id)"
    echo "device=$(getprop ro.product.device)"
    echo "model=$(getprop ro.product.model)"
    echo "kernel=$(uname -a)"
    echo "sda_uid=$(package_uid "$SDA_PACKAGE")"
    echo "downloads_uid=$(package_uid "$DOWNLOADS_PACKAGE")"
    echo "sda_path=$(pm path "$SDA_PACKAGE" 2>/dev/null | tr '\n' ' ')"
    echo "downloads_path=$(pm path "$DOWNLOADS_PACKAGE" 2>/dev/null | tr '\n' ' ')"
    echo "sda_version=$(package_version "$SDA_PACKAGE" | tr '\n' ' ')"
    echo "downloads_version=$(package_version "$DOWNLOADS_PACKAGE" | tr '\n' ' ')"
  } > "$dir/device.txt"

  dumpsys package "$SDA_PACKAGE" > "$dir/dumpsys-sda-package.txt" 2>&1 || true
  dumpsys package "$DOWNLOADS_PACKAGE" > "$dir/dumpsys-downloads-package.txt" 2>&1 || true
  dumpsys activity services "$DOWNLOADS_PACKAGE" > "$dir/dumpsys-downloads-services.txt" 2>&1 || true
  dumpsys jobscheduler > "$dir/dumpsys-jobscheduler.txt" 2>&1 || true
  capture_engine_state "$dir/engine-start.txt"
  capture_spool "$dir/spool-start.txt"
  capture_network "$dir/network-start.txt"
}

sampler_loop() {
  dir="$1"
  samples="$dir/samples"
  mkdir -p "$samples"
  index=0
  while :; do
    index=$((index + 1))
    stamp="$(date '+%Y%m%d-%H%M%S')-$index"
    capture_engine_state "$samples/engine-$stamp.txt"
    capture_spool "$samples/spool-$stamp.txt"
    capture_network "$samples/network-$stamp.txt"
    sleep "$SAMPLE_INTERVAL"
  done
}

stop_sampler() {
  dir="$(active_dir)"
  [ -n "$dir" ] || return 0
  if [ -s "$dir/sampler.pid" ]; then
    pid="$(cat "$dir/sampler.pid")"
    case "$pid" in
      ''|*[!0-9]*) ;;
      *)
        if kill -0 "$pid" 2>/dev/null; then
          kill "$pid" 2>/dev/null || true
          sleep 1
          kill -9 "$pid" 2>/dev/null || true
        fi
        ;;
    esac
  fi
}

start_validation() {
  require_root
  label="$(safe_label "${1:-manual}")"
  [ -n "$label" ] || label=manual
  mkdir -p "$STATE_ROOT"
  stop_sampler

  stamp="$(date '+%Y%m%d-%H%M%S')"
  dir="$STATE_ROOT/$stamp-$label"
  mkdir -p "$dir"
  echo "$dir" > "$STATE_ROOT/active_dir"
  echo "$(now_epoch)" > "$dir/start_epoch"
  date '+%m-%d %H:%M:%S.000' > "$dir/logcat_start"
  echo "$label" > "$dir/label"

  capture_packages "$dir"
  sampler_loop "$dir" > "$dir/sampler.stdout" 2>&1 &
  echo $! > "$dir/sampler.pid"

  echo "[OK] Alpha 15 validation started"
  echo "[INFO] session=$dir"
  echo "[INFO] sampler_pid=$(cat "$dir/sampler.pid") interval=${SAMPLE_INTERVAL}s"
  echo "[NEXT] Start the intended system download now."
}

manual_sample() {
  require_root
  dir="$(active_dir)"
  if [ -z "$dir" ] || [ ! -d "$dir" ]; then
    echo "[ERROR] No active validation session." >&2
    exit 1
  fi
  stamp="$(date '+%Y%m%d-%H%M%S')-manual"
  mkdir -p "$dir/samples"
  capture_engine_state "$dir/samples/engine-$stamp.txt"
  capture_spool "$dir/samples/spool-$stamp.txt"
  capture_network "$dir/samples/network-$stamp.txt"
  echo "[OK] Manual sample captured: $stamp"
}

collect_logs() {
  dir="$1"
  start="$(cat "$dir/logcat_start" 2>/dev/null || true)"
  if [ -n "$start" ]; then
    logcat -d -v threadtime -T "$start" > "$dir/logcat-since-start.txt" 2>&1 || true
  else
    logcat -d -v threadtime -t 12000 > "$dir/logcat-tail.txt" 2>&1 || true
  fi

  grep -E 'SysDownloadAccel|SysDownloadConfirm|SysDownloadHistory|io.github.zhanfg.sda|com.android.providers.downloads' \
    "$dir"/logcat-*.txt > "$dir/logcat-sda-filtered.txt" 2>/dev/null || true

  if [ -d /data/adb/lspd/log ]; then
    find /data/adb/lspd/log -maxdepth 1 -type f -name '*.log' -mmin -180 -print \
      > "$dir/lsposed-log-files.txt" 2>/dev/null || true
    while IFS= read -r file; do
      [ -r "$file" ] || continue
      grep -E 'SystemDownloadAccelerator|SysDownloadAccel|io.github.zhanfg.sda' "$file" \
        >> "$dir/lsposed-sda-filtered.txt" 2>/dev/null || true
    done < "$dir/lsposed-log-files.txt"
  fi
}

hash_file() {
  file="$1"
  target="$2"
  if [ ! -f "$file" ]; then
    echo "file_missing=$file" > "$target"
    return 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$file" | awk '{print $1}')"
  elif command -v toybox >/dev/null 2>&1; then
    actual="$(toybox sha256sum "$file" | awk '{print $1}')"
  else
    echo "sha256_tool_unavailable" > "$target"
    return 1
  fi
  {
    echo "file=$file"
    echo "size=$(wc -c < "$file" 2>/dev/null || stat -c %s "$file" 2>/dev/null || echo unknown)"
    echo "sha256=$actual"
  } > "$target"
  echo "$actual"
}

finish_validation() {
  require_root
  dir="$(active_dir)"
  if [ -z "$dir" ] || [ ! -d "$dir" ]; then
    echo "[ERROR] No active validation session." >&2
    exit 1
  fi

  stop_sampler
  echo "$(now_epoch)" > "$dir/finish_epoch"
  capture_engine_state "$dir/engine-finish.txt"
  capture_spool "$dir/spool-finish.txt"
  capture_network "$dir/network-finish.txt"
  collect_logs "$dir"

  downloaded="${1:-}"
  expected="$(echo "${2:-}" | tr 'A-F' 'a-f')"
  if [ -n "$downloaded" ]; then
    actual="$(hash_file "$downloaded" "$dir/download-hash.txt" 2>/dev/null || true)"
    if [ -n "$expected" ] && [ -n "$actual" ]; then
      actual_lower="$(echo "$actual" | tr 'A-F' 'a-f')"
      if [ "$actual_lower" = "$expected" ]; then
        echo "hash_match=true" >> "$dir/download-hash.txt"
      else
        echo "hash_match=false" >> "$dir/download-hash.txt"
        echo "expected_sha256=$expected" >> "$dir/download-hash.txt"
      fi
    fi
  fi

  label="$(cat "$dir/label" 2>/dev/null || echo manual)"
  stamp="$(basename "$dir" | cut -d- -f1-2)"
  archive="$OUT_ROOT/SDA-Alpha15-Validation-$stamp-$label.tar.gz"
  mkdir -p "$OUT_ROOT"
  tar -czf "$archive" -C "$STATE_ROOT" "$(basename "$dir")"
  chmod 0644 "$archive" 2>/dev/null || true
  rm -f "$STATE_ROOT/active_dir"

  echo "[OK] Validation finished"
  echo "[OUTPUT] $archive"
  if [ -f "$dir/download-hash.txt" ]; then
    cat "$dir/download-hash.txt"
  fi
}

show_status() {
  dir="$(active_dir)"
  if [ -z "$dir" ] || [ ! -d "$dir" ]; then
    echo "status=idle"
    exit 0
  fi
  echo "status=active"
  echo "session=$dir"
  if [ -s "$dir/sampler.pid" ]; then
    pid="$(cat "$dir/sampler.pid")"
    echo "sampler_pid=$pid"
    if kill -0 "$pid" 2>/dev/null; then echo "sampler_alive=true"; else echo "sampler_alive=false"; fi
  fi
  capture_engine_state "$dir/status-engine.txt"
  cat "$dir/status-engine.txt"
}

case "${1:-}" in
  start) shift; start_validation "${1:-manual}" ;;
  sample) manual_sample ;;
  finish) shift; finish_validation "${1:-}" "${2:-}" ;;
  status) show_status ;;
  *) usage; exit 2 ;;
esac

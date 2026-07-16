#!/system/bin/sh

SKIPUNZIP=0

ui_print "- Rust Zygisk Runtime guest preview"
ui_print "- Rust owns lifecycle policy, IPC, daemon and diagnostics"
ui_print "- C++ is restricted to the public Zygisk ABI adapter"

ABI="$(getprop ro.product.cpu.abi)"
case "$ABI" in
  arm64-v8a) ;;
  *) abort "! Initial preview supports arm64-v8a only; detected: $ABI" ;;
esac

if [ ! -f "$MODPATH/zygisk/arm64-v8a.so" ]; then
  abort "! Missing zygisk/arm64-v8a.so"
fi

if [ ! -f "$MODPATH/bin/rzguestd" ] || [ ! -f "$MODPATH/bin/rzctl" ]; then
  abort "! Missing Rust runtime binaries"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/bin/rzguestd" 0 0 0755
set_perm "$MODPATH/bin/rzctl" 0 0 0755
set_perm "$MODPATH/zygisk/arm64-v8a.so" 0 0 0644

ui_print "- Installed in Guest mode"
ui_print "- A compatible Zygisk provider must be enabled"
ui_print "- Target process: com.android.providers.downloads"

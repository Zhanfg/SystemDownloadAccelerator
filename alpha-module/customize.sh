#!/system/bin/sh

ui_print "- System Download Accelerator Alpha 15"
ui_print "- Resume-aligned bounded parallel HTTP Range engine"
ui_print "- Range -> HTTP 200 downgrade and offset mismatch fail closed"
ui_print "- Original ColorOS copy loop remains the only destination writer"
ui_print "- Includes the LSPosed API 102 APK"
ui_print "- Existing app data is preserved during upgrades"
ui_print "- Action runs one read-only Rust diagnostic"
ui_print "- No persistent diagnostic service is installed"

set_perm "$MODPATH/module.prop" 0 0 0644
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/bin/sda-alpha-detect" 0 0 0755
set_perm "$MODPATH/apk/SystemDownloadAccelerator.apk" 0 0 0644

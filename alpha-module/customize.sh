#!/system/bin/sh

ui_print "- System Download Accelerator Alpha 12"
ui_print "- Includes the original LSPosed APK"
ui_print "- Action runs one read-only Rust diagnostic"
ui_print "- No persistent diagnostic service is installed"

set_perm "$MODPATH/module.prop" 0 0 0644
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/bin/sda-alpha-detect" 0 0 0755
set_perm "$MODPATH/apk/SystemDownloadAccelerator.apk" 0 0 0644

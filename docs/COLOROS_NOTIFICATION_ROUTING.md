# ColorOS notification routing — hotfix4

ColorOS 16 may publish DownloadProvider notifications without the AOSP
DownloadNotifier tags used by upstream Android.

hotfix4 changes notification control/progress matching so the provider process,
not the tag string, is the trust boundary:

- hooks every NotificationManager notify* overload carrying a Notification,
  including notifyAsPackage-style paths;
- derives Notification/id/tag argument positions from the method signature;
- if AOSP tags are absent, resolves unfinished rows directly from
  content://downloads/all_downloads;
- when multiple rows are active, notification title/text is used first; if no
  unique title match exists, OEM group semantics are preserved;
- a missing source tag means all active provider rows instead of only database
  rows with a null notificationpackage;
- NOTIF_POST_SEEN and CONTROL_TARGET_MISS telemetry show whether ColorOS reached
  the hook and whether a provider row could be resolved.

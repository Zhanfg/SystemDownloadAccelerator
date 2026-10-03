# Native download icon policy — hotfix5

SDA no longer invents or mixes download branding.

Policy:
- native ColorOS/AOSP DownloadProvider notifications keep their original small
  icon untouched;
- SDA observes the provider small Icon and reuses that exact Icon for fallback
  and live-progress notifications whenever available;
- only before an OEM icon has been observed does SDA fall back to Android's
  framework stat_sys_download icon;
- custom actions use framework semantic icons: copy, pause, resume and cancel;
- an existing OEM Cancel action is still preserved instead of being replaced;
- the SDA launcher icon no longer uses Android's generic placeholder app icon;
  it uses the platform download symbol.

No custom drawable assets are shipped for download/control branding.

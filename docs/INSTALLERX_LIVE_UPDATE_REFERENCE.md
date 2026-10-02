# InstallerX live-update reference

SDA's Android 16 live-update implementation is independently implemented
against public Android/AndroidX APIs after comparing behavior with
wxxsfxyzm/InstallerX-Revived.

Observed reference behavior in InstallerX-Revived:
- androidx.core 1.19.1
- NotificationCompat.Builder
- dedicated high-importance live channel
- setOngoing(true)
- setRequestPromotedOngoing(true)
- setShortCriticalText(...)
- NotificationCompat.ProgressStyle
- explicit progress segments
- setStyledByProgress(true)
- approximately 500 ms notification update cadence

SDA intentionally does not copy InstallerX's installation-specific state
machine, dynamic-color logic, Xiaomi island compatibility code, or service
architecture. DownloadProvider remains the source of truth for downloads and
SDA only mirrors validated progress into the live notification surface.

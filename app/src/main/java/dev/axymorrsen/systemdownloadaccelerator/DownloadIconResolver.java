package dev.axymorrsen.systemdownloadaccelerator;

import android.app.Notification;
import android.graphics.drawable.Icon;

/**
 * Keeps SDA visually aligned with the system DownloadProvider.
 *
 * Native ColorOS/AOSP download notifications remain the visual authority. SDA
 * only falls back to framework icons when the provider has not exposed one yet.
 */
final class DownloadIconResolver {
    private static volatile Icon nativeSmallIcon;

    private DownloadIconResolver() {}

    static void observe(Notification notification) {
        if (notification == null) return;
        try {
            Icon icon = notification.getSmallIcon();
            if (icon != null) {
                nativeSmallIcon = icon;
            }
        } catch (Throwable ignored) {
        }
    }

    static Icon nativeSmallIcon() {
        return nativeSmallIcon;
    }

    static int fallbackDownloadIcon() {
        return android.R.drawable.stat_sys_download;
    }

    static int copyIcon() {
        return android.R.drawable.ic_menu_copy;
    }

    static int pauseIcon() {
        return android.R.drawable.ic_media_pause;
    }

    static int resumeIcon() {
        return android.R.drawable.ic_media_play;
    }

    static int cancelIcon() {
        return android.R.drawable.ic_menu_close_clear_cancel;
    }
}

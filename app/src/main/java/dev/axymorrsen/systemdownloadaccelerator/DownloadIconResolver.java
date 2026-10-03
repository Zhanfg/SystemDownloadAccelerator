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
        android.content.res.Resources system =
                android.content.res.Resources.getSystem();
        String[] names = {
                "ic_menu_copy",
                "ic_menu_copy_material",
                "ic_content_copy",
                "ic_copy"
        };
        for (String name : names) {
            int id = system.getIdentifier(
                    name,
                    "drawable",
                    "android");
            if (id != 0) {
                return id;
            }
        }

        // Modern SystemUI renders notification actions primarily from text.
        // Returning 0 is preferable to showing a semantically wrong Share or
        // Save glyph when the framework does not expose its Copy resource.
        return 0;
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

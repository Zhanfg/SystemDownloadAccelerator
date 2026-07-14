package io.github.zhanfg.sda.ui;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowInsets;
import android.view.WindowManager;

/** Classifies the current activity window, not the physical display orientation. */
public final class AdaptiveWindowInfo {
    public enum Mode {
        COMPACT,
        STANDARD,
        SIDE
    }

    public final int widthPx;
    public final int heightPx;
    public final int widthDp;
    public final int heightDp;
    public final Mode mode;

    private AdaptiveWindowInfo(int widthPx, int heightPx, float density) {
        this.widthPx = Math.max(1, widthPx);
        this.heightPx = Math.max(1, heightPx);
        this.widthDp = Math.round(this.widthPx / density);
        this.heightDp = Math.round(this.heightPx / density);
        if (widthDp < 360 || heightDp < 300) {
            mode = Mode.COMPACT;
        } else if (widthDp >= 600 || (widthDp >= 520 && heightDp < 520)) {
            mode = Mode.SIDE;
        } else {
            mode = Mode.STANDARD;
        }
    }

    public static AdaptiveWindowInfo from(Activity activity) {
        float density = activity.getResources().getDisplayMetrics().density;
        int width;
        int height;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowManager windowManager = activity.getSystemService(WindowManager.class);
            android.view.WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
            Rect bounds = metrics.getBounds();
            android.graphics.Insets bars = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            width = bounds.width() - bars.left - bars.right;
            height = bounds.height() - bars.top - bars.bottom;
        } else {
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            width = metrics.widthPixels;
            height = metrics.heightPixels;
        }
        return new AdaptiveWindowInfo(width, height, density);
    }

    public String key() {
        return mode.name() + ':' + widthDp / 40 + ':' + heightDp / 40;
    }
}

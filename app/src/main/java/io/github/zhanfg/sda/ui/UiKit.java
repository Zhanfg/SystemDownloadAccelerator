package io.github.zhanfg.sda.ui;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class UiKit {
    final Activity activity;
    final boolean dark;
    final int scrim;
    final int background;
    final int surface;
    final int surfaceHigh;
    final int textPrimary;
    final int textSecondary;
    final int primary;
    final int onPrimary;
    final int outline;

    UiKit(Activity activity) {
        this.activity = activity;
        dark = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        scrim = Color.argb(dark ? 150 : 112, 0, 0, 0);
        background = dark ? Color.rgb(27, 29, 34) : Color.rgb(248, 249, 252);
        surface = dark ? Color.rgb(36, 39, 46) : Color.rgb(255, 255, 255);
        surfaceHigh = dark ? Color.rgb(47, 51, 60) : Color.rgb(238, 242, 247);
        textPrimary = dark ? Color.rgb(241, 243, 248) : Color.rgb(29, 33, 40);
        textSecondary = dark ? Color.rgb(180, 186, 197) : Color.rgb(92, 101, 114);
        onPrimary = Color.WHITE;
        outline = dark ? Color.rgb(81, 87, 99) : Color.rgb(214, 220, 229);
        int accent = Color.rgb(43, 123, 143);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                accent = activity.getColor(android.R.color.system_accent1_600);
            } catch (Throwable ignored) {
            }
        }
        primary = accent;
    }

    int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value == null ? "" : value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setIncludeFontPadding(false);
        return view;
    }

    TextView ellipsized(String value, float sp, int color, boolean bold, int maxLines) {
        TextView view = text(value, sp, color, bold);
        view.setMaxLines(maxLines);
        view.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        return view;
    }

    GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(Math.round(radiusDp)));
        return drawable;
    }

    GradientDrawable topRounded(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        float radius = dp(Math.round(radiusDp));
        drawable.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        return drawable;
    }

    GradientDrawable outlined(int color, int strokeColor, float radiusDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    Button button(String label, boolean primaryButton) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primaryButton ? onPrimary : textPrimary);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(rounded(primaryButton ? primary : surfaceHigh, 18));
        button.setStateListAnimator(null);
        return button;
    }

    View divider() {
        View divider = new View(activity);
        divider.setBackgroundColor(outline);
        divider.setAlpha(dark ? 0.52f : 0.72f);
        return divider;
    }

    LinearLayout infoRow(String label, String value, boolean chevron) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        TextView key = text(label, 14, textSecondary, false);
        row.addView(key, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.36f));

        TextView val = text(value, 14, textPrimary, false);
        val.setGravity(Gravity.END);
        val.setMaxLines(2);
        row.addView(val, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, chevron ? 0.58f : 0.64f));

        if (chevron) {
            TextView arrow = text("›", 25, textSecondary, false);
            arrow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(
                    dp(24), ViewGroup.LayoutParams.WRAP_CONTENT);
            arrowParams.leftMargin = dp(4);
            row.addView(arrow, arrowParams);
        }
        return row;
    }
}

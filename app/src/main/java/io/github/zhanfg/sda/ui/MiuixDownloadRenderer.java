package io.github.zhanfg.sda.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Miuix renderer: bottom sheet in standard windows, side sheet in wide/low windows. */
public final class MiuixDownloadRenderer implements DownloadUiRenderer {
    @Override
    public View create(Activity activity, DownloadUiState state, DecisionHandler handler) {
        UiKit ui = new UiKit(activity);
        AdaptiveWindowInfo window = AdaptiveWindowInfo.from(activity);
        boolean side = window.mode == AdaptiveWindowInfo.Mode.SIDE;

        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(ui.scrim);
        root.setClickable(true);
        root.setOnClickListener(view -> handler.decide(false));

        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(side
                ? ui.rounded(ui.background, 30)
                : ui.topRounded(ui.background, window.mode == AdaptiveWindowInfo.Mode.COMPACT ? 24 : 32));
        sheet.setElevation(ui.dp(18));
        sheet.setClickable(true);
        sheet.setOnClickListener(view -> { });
        sheet.setPadding(ui.dp(window.mode == AdaptiveWindowInfo.Mode.COMPACT ? 14 : 20),
                ui.dp(10), ui.dp(window.mode == AdaptiveWindowInfo.Mode.COMPACT ? 14 : 20), ui.dp(18));

        int width = side
                ? Math.min(ui.dp(420), window.widthPx - ui.dp(24))
                : Math.min(window.widthPx, ui.dp(760));
        int gravity = side ? Gravity.END | Gravity.CENTER_VERTICAL
                : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        FrameLayout.LayoutParams sheetParams = new FrameLayout.LayoutParams(
                Math.max(ui.dp(280), width), ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        if (side) sheetParams.rightMargin = ui.dp(12);
        root.addView(sheet, sheetParams);
        root.setTag(sheet);
        root.setContentDescription(side ? "side" : "bottom");

        if (!side) {
            View handle = new View(activity);
            handle.setBackground(ui.rounded(ui.outline, 3));
            LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(ui.dp(42), ui.dp(4));
            handleParams.gravity = Gravity.CENTER_HORIZONTAL;
            handleParams.bottomMargin = ui.dp(12);
            sheet.addView(handle, handleParams);
        }

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        sheet.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48)));

        TextView close = ui.text("×", 30, ui.textPrimary, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(ui.rounded(ui.surfaceHigh, 18));
        close.setOnClickListener(view -> handler.decide(false));
        top.addView(close, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));

        TextView title = ui.text("下载文件", 20, ui.textPrimary, true);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        top.addView(new View(activity), new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));

        MaxHeightScrollView scroll = new MaxHeightScrollView(activity);
        scroll.setMaxHeightPx(Math.max(ui.dp(120),
                window.heightPx - ui.dp(window.mode == AdaptiveWindowInfo.Mode.COMPACT ? 150 : 190)));
        scroll.setClipToPadding(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = ui.dp(8);
        sheet.addView(scroll, scrollParams);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout fileCard = new LinearLayout(activity);
        fileCard.setOrientation(LinearLayout.VERTICAL);
        fileCard.setPadding(ui.dp(16), ui.dp(15), ui.dp(16), ui.dp(15));
        fileCard.setBackground(ui.rounded(ui.surfaceHigh, 22));
        body.addView(fileCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView fileName = ui.ellipsized(state.fileName, 17, ui.textPrimary, true, 2);
        fileCard.addView(fileName);
        TextView meta = ui.text(state.formattedSize() + " · " + state.compactType(),
                13, ui.textSecondary, false);
        meta.setPadding(0, ui.dp(7), 0, 0);
        fileCard.addView(meta);

        if (window.mode != AdaptiveWindowInfo.Mode.COMPACT || window.heightDp >= 360) {
            TextView url = ui.ellipsized(state.url, 12, ui.textSecondary, false, 2);
            url.setTextIsSelectable(true);
            url.setPadding(0, ui.dp(9), 0, 0);
            fileCard.addView(url);
        }

        LinearLayout options = new LinearLayout(activity);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4));
        options.setBackground(ui.rounded(ui.surface, 22));
        LinearLayout.LayoutParams optionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        optionsParams.topMargin = ui.dp(12);
        body.addView(options, optionsParams);

        addOption(ui, options, "保存位置", state.destination);
        addDivider(ui, options);
        addOption(ui, options, "下载方式", state.threadMode);
        if (window.mode != AdaptiveWindowInfo.Mode.COMPACT) {
            addDivider(ui, options);
            addOption(ui, options, "来源应用", state.sourcePackage);
        }

        Button confirm = ui.button("开始下载", true);
        confirm.setOnClickListener(view -> handler.decide(true));
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(56));
        confirmParams.topMargin = ui.dp(16);
        sheet.addView(confirm, confirmParams);

        TextView cancel = ui.text("取消下载", 14, ui.textSecondary, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(view -> handler.decide(false));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(42));
        cancelParams.topMargin = ui.dp(4);
        sheet.addView(cancel, cancelParams);
        return root;
    }

    private void addOption(UiKit ui, LinearLayout parent, String label, String value) {
        parent.addView(ui.infoRow(label, value, true), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addDivider(UiKit ui, LinearLayout parent) {
        parent.addView(ui.divider(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)));
    }

    @Override
    public void animateIn(View root) {
        Object tagged = root.getTag();
        if (!(tagged instanceof View)) return;
        View sheet = (View) tagged;
        boolean side = "side".contentEquals(root.getContentDescription());
        root.setAlpha(0f);
        sheet.setAlpha(0.94f);
        if (side) sheet.setTranslationX(root.getResources().getDisplayMetrics().widthPixels * 0.42f);
        else sheet.setTranslationY(root.getResources().getDisplayMetrics().heightPixels * 0.42f);
        root.animate().alpha(1f).setDuration(190L).start();
        sheet.animate()
                .alpha(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(340L)
                .setInterpolator(new DecelerateInterpolator(1.7f))
                .start();
    }

    @Override
    public void animateOut(View root, boolean accepted, Runnable endAction) {
        Object tagged = root.getTag();
        View sheet = tagged instanceof View ? (View) tagged : root;
        boolean side = "side".contentEquals(root.getContentDescription());
        if (side) sheet.animate().translationX(sheet.getWidth() * 0.34f).alpha(0f)
                .setDuration(220L).setInterpolator(new DecelerateInterpolator()).start();
        else sheet.animate().translationY(sheet.getHeight() * 0.34f).alpha(0f)
                .setDuration(220L).setInterpolator(new DecelerateInterpolator()).start();
        root.animate().alpha(0f).setDuration(240L).withEndAction(endAction).start();
    }
}

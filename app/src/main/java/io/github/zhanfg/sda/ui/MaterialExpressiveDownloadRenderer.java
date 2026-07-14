package io.github.zhanfg.sda.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Responsive Material 3 Expressive renderer inspired by InstallerX PositionDialog. */
public final class MaterialExpressiveDownloadRenderer implements DownloadUiRenderer {
    @Override
    public View create(Activity activity, DownloadUiState state, DecisionHandler handler) {
        UiKit ui = new UiKit(activity);
        AdaptiveWindowInfo window = AdaptiveWindowInfo.from(activity);
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(ui.scrim);
        root.setClickable(true);
        root.setOnClickListener(view -> handler.decide(false));
        root.setPadding(ui.dp(window.mode == AdaptiveWindowInfo.Mode.COMPACT ? 8 : 16),
                ui.dp(12), ui.dp(window.mode == AdaptiveWindowInfo.Mode.COMPACT ? 8 : 16), ui.dp(12));

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.rounded(ui.background,
                window.mode == AdaptiveWindowInfo.Mode.COMPACT ? 22 : 30));
        card.setElevation(ui.dp(20));
        card.setClickable(true);
        card.setOnClickListener(view -> { });
        card.setPadding(ui.dp(window.mode == AdaptiveWindowInfo.Mode.COMPACT ? 16 : 22),
                ui.dp(window.mode == AdaptiveWindowInfo.Mode.COMPACT ? 16 : 22),
                ui.dp(window.mode == AdaptiveWindowInfo.Mode.COMPACT ? 16 : 22),
                ui.dp(18));

        int maxWidthDp = window.mode == AdaptiveWindowInfo.Mode.SIDE ? 420 : 560;
        int width = Math.min(window.widthPx - ui.dp(window.mode == AdaptiveWindowInfo.Mode.COMPACT ? 16 : 32),
                ui.dp(maxWidthDp));
        int gravity = window.mode == AdaptiveWindowInfo.Mode.SIDE
                ? Gravity.END | Gravity.CENTER_VERTICAL
                : Gravity.CENTER;
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                Math.max(ui.dp(280), width), ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        if (window.mode == AdaptiveWindowInfo.Mode.SIDE) {
            cardParams.rightMargin = ui.dp(8);
        }
        root.addView(card, cardParams);
        root.setTag(card);

        TextView title = ui.text("下载文件",
                window.mode == AdaptiveWindowInfo.Mode.COMPACT ? 21 : 24,
                ui.textPrimary, true);
        title.setGravity(Gravity.START);
        card.addView(title);

        MaxHeightScrollView scroll = new MaxHeightScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setMaxHeightPx(Math.max(ui.dp(120),
                window.heightPx - ui.dp(window.mode == AdaptiveWindowInfo.Mode.COMPACT ? 150 : 190)));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = ui.dp(12);
        card.addView(scroll, scrollParams);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView fileName = ui.ellipsized(state.fileName,
                window.mode == AdaptiveWindowInfo.Mode.COMPACT ? 16 : 18,
                ui.textPrimary, true, 2);
        body.addView(fileName);

        if (window.mode != AdaptiveWindowInfo.Mode.COMPACT || window.heightDp >= 360) {
            TextView url = ui.ellipsized(state.url, 13, ui.textSecondary, false, 2);
            url.setTextIsSelectable(true);
            url.setPadding(0, ui.dp(7), 0, 0);
            body.addView(url);
        }

        LinearLayout summary = new LinearLayout(activity);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        summary.setBackground(ui.rounded(ui.surfaceHigh, 20));
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = ui.dp(14);
        body.addView(summary, summaryParams);

        addRow(ui, summary, "文件大小", state.formattedSize());
        addDivider(ui, summary);
        addRow(ui, summary, "保存位置", state.destination);
        addDivider(ui, summary);
        addRow(ui, summary, "下载方式", state.threadMode);
        if (window.mode != AdaptiveWindowInfo.Mode.COMPACT) {
            addDivider(ui, summary);
            addRow(ui, summary, "来源", state.sourcePackage);
        }

        if (window.heightDp >= 420) {
            TextView hint = ui.text(
                    "确认后转入系统实时下载；服务器不支持 Range 时自动回退。",
                    12, ui.textSecondary, false);
            hint.setPadding(0, ui.dp(12), 0, 0);
            body.addView(hint);
        }

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(54));
        actionsParams.topMargin = ui.dp(16);
        card.addView(actions, actionsParams);

        Button cancel = ui.button("取消", false);
        cancel.setOnClickListener(view -> handler.decide(false));
        Button confirm = ui.button("开始下载", true);
        confirm.setOnClickListener(view -> handler.decide(true));
        actions.addView(cancel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        View gap = new View(activity);
        actions.addView(gap, new LinearLayout.LayoutParams(ui.dp(10), 1));
        actions.addView(confirm, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return root;
    }

    private void addRow(UiKit ui, LinearLayout parent, String label, String value) {
        parent.addView(ui.infoRow(label, value, false), new LinearLayout.LayoutParams(
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
        View card = (View) tagged;
        root.setAlpha(0f);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.setTranslationY(24f);
        root.animate().alpha(1f).setDuration(180L).start();
        card.animate()
                .scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(290L)
                .setInterpolator(new OvershootInterpolator(0.46f))
                .start();
    }

    @Override
    public void animateOut(View root, boolean accepted, Runnable endAction) {
        Object tagged = root.getTag();
        View card = tagged instanceof View ? (View) tagged : root;
        float targetY = accepted ? -18f : 18f;
        card.animate()
                .alpha(0f)
                .scaleX(accepted ? 0.97f : 0.94f)
                .scaleY(accepted ? 0.97f : 0.94f)
                .translationY(targetY)
                .setDuration(190L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        root.animate().alpha(0f).setDuration(220L).withEndAction(endAction).start();
    }
}

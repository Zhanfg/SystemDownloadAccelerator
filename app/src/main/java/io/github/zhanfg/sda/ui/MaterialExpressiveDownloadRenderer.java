package io.github.zhanfg.sda.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Centered Material 3 Expressive renderer inspired by InstallerX PositionDialog. */
public final class MaterialExpressiveDownloadRenderer implements DownloadUiRenderer {
    @Override
    public View create(Activity activity, DownloadUiState state, DecisionHandler handler) {
        UiKit ui = new UiKit(activity);
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(ui.scrim);
        root.setClickable(true);
        root.setOnClickListener(view -> handler.decide(false));
        root.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.rounded(ui.background, 30));
        card.setElevation(ui.dp(20));
        card.setClickable(true);
        card.setOnClickListener(view -> { });
        card.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(18));

        int width = Math.min(
                activity.getResources().getDisplayMetrics().widthPixels - ui.dp(32),
                ui.dp(560)
        );
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                width,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        root.addView(card, cardParams);
        root.setTag(card);

        TextView title = ui.text("下载文件", 24, ui.textPrimary, true);
        title.setGravity(Gravity.START);
        card.addView(title);

        MaxHeightScrollView scroll = new MaxHeightScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        int maxHeight = Math.round(
                activity.getResources().getDisplayMetrics().heightPixels * 0.62f
        );
        scroll.setMaxHeightPx(maxHeight);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        scrollParams.topMargin = ui.dp(14);
        card.addView(scroll, scrollParams);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView fileName = ui.ellipsized(state.fileName, 18, ui.textPrimary, true, 2);
        body.addView(fileName);

        TextView url = ui.ellipsized(state.url, 13, ui.textSecondary, false, 2);
        url.setTextIsSelectable(true);
        url.setPadding(0, ui.dp(7), 0, 0);
        body.addView(url);

        LinearLayout summary = new LinearLayout(activity);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        summary.setBackground(ui.rounded(ui.surfaceHigh, 20));
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        summaryParams.topMargin = ui.dp(16);
        body.addView(summary, summaryParams);

        addRow(ui, summary, "文件大小", state.formattedSize());
        addDivider(ui, summary);
        addRow(ui, summary, "保存位置", state.destination);
        addDivider(ui, summary);
        addRow(ui, summary, "下载方式", state.threadMode);
        addDivider(ui, summary);
        addRow(ui, summary, "来源", state.sourcePackage);

        TextView hint = ui.text(
                "确认后由系统下载服务接管；服务器不支持 Range 时自动回退。",
                12,
                ui.textSecondary,
                false
        );
        hint.setPadding(0, ui.dp(12), 0, 0);
        body.addView(hint);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ui.dp(54)
        );
        actionsParams.topMargin = ui.dp(18);
        card.addView(actions, actionsParams);

        Button cancel = ui.button("取消", false);
        cancel.setOnClickListener(view -> handler.decide(false));
        Button confirm = ui.button("开始下载", true);
        confirm.setOnClickListener(view -> handler.decide(true));
        actions.addView(cancel, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
        ));
        View gap = new View(activity);
        actions.addView(gap, new LinearLayout.LayoutParams(ui.dp(10), 1));
        actions.addView(confirm, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
        ));
        return root;
    }

    private void addRow(UiKit ui, LinearLayout parent, String label, String value) {
        parent.addView(ui.infoRow(label, value, false), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void addDivider(UiKit ui, LinearLayout parent) {
        parent.addView(ui.divider(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ui.dp(1)
        ));
    }

    @Override
    public void animateIn(View root) {
        Object tagged = root.getTag();
        if (!(tagged instanceof View)) {
            return;
        }
        View card = (View) tagged;
        card.setAlpha(0f);
        card.setScaleX(0.90f);
        card.setScaleY(0.90f);
        card.setTranslationY(36f);
        card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(320L)
                .setInterpolator(new OvershootInterpolator(0.72f))
                .start();
    }
}

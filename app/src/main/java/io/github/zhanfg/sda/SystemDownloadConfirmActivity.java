package io.github.zhanfg.sda;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import io.github.zhanfg.sda.ui.AdaptiveWindowInfo;
import io.github.zhanfg.sda.ui.DownloadUiRenderer;
import io.github.zhanfg.sda.ui.DownloadUiState;
import io.github.zhanfg.sda.ui.MaterialExpressiveDownloadRenderer;
import io.github.zhanfg.sda.ui.MiuixDownloadRenderer;

/** Transparent privileged host with responsive dual renderers and a Live Update handoff. */
public final class SystemDownloadConfirmActivity extends Activity {
    private static final String ACTION_DECISION =
            "io.github.zhanfg.sda.action.SYSTEM_DOWNLOAD_DECISION";
    private static final String DOWNLOAD_PROVIDER = "com.android.providers.downloads";
    private static final Uri LIVE_UPDATE_URI =
            Uri.parse("content://io.github.zhanfg.sda.liveupdate");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String token;
    private boolean decisionSent;
    private boolean rebuilding;
    private DownloadUiRenderer renderer;
    private DownloadUiState state;
    private View root;
    private String layoutKey;
    private Runnable pendingRebuild;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        overridePendingTransition(0, 0);
        configureTransparentWindow();

        token = getIntent().getStringExtra("token");
        if (TextUtils.isEmpty(token)) {
            finishWithoutAnimation();
            return;
        }

        state = DownloadUiState.consume(this, token);
        if (state == null) {
            finishWithoutAnimation();
            return;
        }
        render(true);
    }

    private void render(boolean animate) {
        if (decisionSent || rebuilding || state == null) return;
        rebuilding = true;
        String style = getSharedPreferences("module_settings", MODE_PRIVATE)
                .getString("ui_style", "material");
        renderer = "miuix".equals(style)
                ? new MiuixDownloadRenderer()
                : new MaterialExpressiveDownloadRenderer();

        View nextRoot = renderer.create(this, state, this::sendDecision);
        applySystemInsets(nextRoot);
        nextRoot.setAlpha(animate ? 1f : 0f);
        setContentView(nextRoot);
        root = nextRoot;
        layoutKey = AdaptiveWindowInfo.from(this).key();
        nextRoot.addOnLayoutChangeListener((view, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom) ->
                scheduleResponsiveRebuild());
        if (animate) {
            nextRoot.post(() -> renderer.animateIn(nextRoot));
        } else {
            nextRoot.animate().alpha(1f).setDuration(160L).start();
        }
        rebuilding = false;
    }

    private void scheduleResponsiveRebuild() {
        if (decisionSent || rebuilding) return;
        String nextKey = AdaptiveWindowInfo.from(this).key();
        if (nextKey.equals(layoutKey)) return;
        if (pendingRebuild != null) mainHandler.removeCallbacks(pendingRebuild);
        pendingRebuild = () -> {
            pendingRebuild = null;
            if (!decisionSent && !AdaptiveWindowInfo.from(this).key().equals(layoutKey)) {
                render(false);
            }
        };
        mainHandler.postDelayed(pendingRebuild, 120L);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        scheduleResponsiveRebuild();
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
        scheduleResponsiveRebuild();
    }

    private void configureTransparentWindow() {
        Window window = getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setDimAmount(0f);
        window.setWindowAnimations(0);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);
        boolean blur = getSharedPreferences("module_settings", MODE_PRIVATE)
                .getBoolean("use_blur", Build.VERSION.SDK_INT >= 31);
        if (blur && Build.VERSION.SDK_INT >= 31) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.setBlurBehindRadius(dp(24));
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }
        setFinishOnTouchOutside(false);
    }

    private void applySystemInsets(View target) {
        if (Build.VERSION.SDK_INT < 20) return;
        final int baseLeft = target.getPaddingLeft();
        final int baseTop = target.getPaddingTop();
        final int baseRight = target.getPaddingRight();
        final int baseBottom = target.getPaddingBottom();
        target.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(baseLeft + left, baseTop + top,
                    baseRight + right, baseBottom + bottom);
            return insets;
        });
        target.requestApplyInsets();
    }

    private void sendDecision(boolean allow) {
        if (decisionSent) return;
        decisionSent = true;
        if (pendingRebuild != null) mainHandler.removeCallbacks(pendingRebuild);

        if (allow) primeLiveUpdate();

        // The provider decision is dispatched before animation. If ColorOS interrupts or destroys the
        // transparent host during the exit transition, the real download has already been resumed.
        Intent decision = new Intent(ACTION_DECISION);
        decision.setPackage(DOWNLOAD_PROVIDER);
        decision.putExtra("token", token);
        decision.putExtra("decision", allow ? 1 : 0);
        sendBroadcast(decision);

        Runnable complete = this::finishWithoutAnimation;
        if (renderer != null && root != null) renderer.animateOut(root, allow, complete);
        else complete.run();
    }

    private void primeLiveUpdate() {
        if (state == null || state.downloadId < 0) return;
        try {
            Bundle extras = new Bundle();
            extras.putLong("download_id", state.downloadId);
            extras.putString("title", state.fileName);
            extras.putString("mime_type", state.mimeType);
            extras.putInt("status", 192);
            extras.putLong("total_bytes", state.fileSize);
            extras.putLong("current_bytes", 0L);
            getContentResolver().call(LIVE_UPDATE_URI, "prime", null, extras);
        } catch (Throwable ignored) {
            // Download still resumes; the provider-side mirror will publish the next real state.
        }
    }

    @Override
    public void onBackPressed() {
        sendDecision(false);
    }

    private void finishWithoutAnimation() {
        finish();
        overridePendingTransition(0, 0);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

package io.github.zhanfg.sda;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import io.github.zhanfg.sda.ui.DownloadUiRenderer;
import io.github.zhanfg.sda.ui.DownloadUiState;
import io.github.zhanfg.sda.ui.MaterialExpressiveDownloadRenderer;
import io.github.zhanfg.sda.ui.MiuixDownloadRenderer;

/**
 * Transparent privileged host. Business state is shared while Material and Miuix are independent
 * renderers, matching InstallerX's dual-UI architecture instead of recoloring one layout.
 */
public final class SystemDownloadConfirmActivity extends Activity {
    private static final String ACTION_DECISION =
            "io.github.zhanfg.sda.action.SYSTEM_DOWNLOAD_DECISION";
    private static final String DOWNLOAD_PROVIDER = "com.android.providers.downloads";

    private String token;
    private boolean decisionSent;
    private DownloadUiRenderer renderer;

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

        DownloadUiState state = DownloadUiState.consume(this, token);
        if (state == null) {
            finishWithoutAnimation();
            return;
        }

        String style = getSharedPreferences("module_settings", MODE_PRIVATE)
                .getString("ui_style", "material");
        renderer = "miuix".equals(style)
                ? new MiuixDownloadRenderer()
                : new MaterialExpressiveDownloadRenderer();

        View root = renderer.create(this, state, this::sendDecision);
        applySystemInsets(root);
        setContentView(root);
        root.post(() -> renderer.animateIn(root));
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
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        }
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

    private void applySystemInsets(View root) {
        if (Build.VERSION.SDK_INT < 20) {
            return;
        }
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
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
            view.setPadding(
                    baseLeft + left,
                    baseTop + top,
                    baseRight + right,
                    baseBottom + bottom
            );
            return insets;
        });
        root.requestApplyInsets();
    }

    private void sendDecision(boolean allow) {
        if (decisionSent) {
            return;
        }
        decisionSent = true;
        Intent decision = new Intent(ACTION_DECISION);
        decision.setPackage(DOWNLOAD_PROVIDER);
        decision.putExtra("token", token);
        decision.putExtra("decision", allow ? 1 : 0);
        sendBroadcast(decision);
        finishWithoutAnimation();
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

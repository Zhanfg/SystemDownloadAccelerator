package io.github.zhanfg.sda.ui;

import android.app.Activity;
import android.view.View;

/** One business state, two independent UI renderers with coordinated enter/exit motion. */
public interface DownloadUiRenderer {
    interface DecisionHandler {
        void decide(boolean allow);
    }

    View create(Activity activity, DownloadUiState state, DecisionHandler handler);

    void animateIn(View root);

    void animateOut(View root, boolean accepted, Runnable endAction);
}

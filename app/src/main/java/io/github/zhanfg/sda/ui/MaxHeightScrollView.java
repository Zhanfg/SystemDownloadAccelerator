package io.github.zhanfg.sda.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

final class MaxHeightScrollView extends ScrollView {
    private int maxHeightPx = Integer.MAX_VALUE;

    MaxHeightScrollView(Context context) {
        super(context);
    }

    MaxHeightScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setMaxHeightPx(int maxHeightPx) {
        this.maxHeightPx = Math.max(1, maxHeightPx);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int cappedHeight = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
        super.onMeasure(widthMeasureSpec, cappedHeight);
    }
}

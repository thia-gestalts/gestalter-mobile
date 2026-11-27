package thia.gestalter.android.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Lays children out left to right, wrapping onto a new line when the width runs out. */
public final class Flow extends ViewGroup {
    private final int gap;

    Flow(Context context, int gap) {
        super(context);
        this.gap = gap;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int limit = width - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, line = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (x > 0 && x + child.getMeasuredWidth() > limit) {
                x = 0;
                y += line + gap;
                line = 0;
            }
            x += child.getMeasuredWidth() + gap;
            line = Math.max(line, child.getMeasuredHeight());
        }
        setMeasuredDimension(width, y + line + getPaddingTop() + getPaddingBottom());
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int limit = right - left - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, line = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
            if (x > 0 && x + w > limit) {
                x = 0;
                y += line + gap;
                line = 0;
            }
            child.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + w, getPaddingTop() + y + h);
            x += w + gap;
            line = Math.max(line, h);
        }
    }
}

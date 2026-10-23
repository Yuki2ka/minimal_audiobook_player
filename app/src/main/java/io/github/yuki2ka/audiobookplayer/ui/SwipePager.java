package io.github.yuki2ka.audiobookplayer.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.Switch;

/**
 * Very small horizontal pager for the three pages. It only intercepts
 * horizontal drags (vertical list scrolling is untouched) and ignores drags
 * that start on widgets which need horizontal touch themselves (seek bars,
 * switches, text fields).
 */
public final class SwipePager extends FrameLayout {

    public interface Listener {
        /** {@code direction} is +1 for "next page", -1 for "previous page". */
        void onSwipe(int direction);
    }

    private Listener listener;
    private int touchSlop;
    private float minDistance;
    private float downX;
    private float downY;
    private boolean swiping;
    private boolean ignoreGesture;

    public SwipePager(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        minDistance = 72f * getResources().getDisplayMetrics().density;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                swiping = false;
                ignoreGesture = isInteractive(findHit(this, downX, downY));
                return false;
            case MotionEvent.ACTION_MOVE:
                if (ignoreGesture) {
                    return false;
                }
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!swiping && Math.abs(dx) > touchSlop * 2f && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                    swiping = true;
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                swiping = false;
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - downX;
                View page = visiblePage();
                if (page != null) {
                    page.setTranslationX(dx * 0.3f);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float dx = event.getX() - downX;
                snapBack();
                if (Math.abs(dx) > minDistance && listener != null) {
                    listener.onSwipe(dx < 0 ? 1 : -1);
                }
                swiping = false;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                snapBack();
                swiping = false;
                return true;
            default:
                return true;
        }
    }

    private void snapBack() {
        View page = visiblePage();
        if (page != null && page.getTranslationX() != 0f) {
            page.animate().translationX(0f).setDuration(120).start();
        }
    }

    private View visiblePage() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == VISIBLE) {
                return child;
            }
        }
        return null;
    }

    private static boolean isInteractive(View view) {
        View current = view;
        while (current != null) {
            if (current instanceof SeekBar || current instanceof EditText || current instanceof Switch) {
                return true;
            }
            if (!(current.getParent() instanceof View)) {
                return false;
            }
            current = (View) current.getParent();
        }
        return false;
    }

    private static View findHit(ViewGroup parent, float x, float y) {
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != VISIBLE) {
                continue;
            }
            float localX = x - child.getLeft() + parent.getScrollX() - child.getTranslationX();
            float localY = y - child.getTop() + parent.getScrollY() - child.getTranslationY();
            if (localX >= 0 && localY >= 0 && localX < child.getWidth() && localY < child.getHeight()) {
                if (child instanceof ViewGroup) {
                    View deeper = findHit((ViewGroup) child, localX, localY);
                    if (deeper != null) {
                        return deeper;
                    }
                }
                return child;
            }
        }
        return null;
    }
}

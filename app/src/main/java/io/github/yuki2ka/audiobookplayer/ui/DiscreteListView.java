package io.github.yuki2ka.audiobookplayer.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.ListAdapter;

import io.github.yuki2ka.audiobookplayer.model.FileListRows;

import io.github.yuki2ka.audiobookplayer.R;
import io.github.yuki2ka.audiobookplayer.util.DiscreteDrag;

/**
 * The All-files ListView can switch to whole-row, no-fling scrolling. In that
 * mode each complete row crossed by the finger is applied immediately, and the
 * activity rotates the circular queue at either end. Taps (including the
 * per-row delete button) and horizontal page swipes are left to their owners.
 */
public final class DiscreteListView extends ListView {

    public interface SwipeListener {
        /**
         * Called for newly crossed rows during a vertical gesture, and for a
         * short swipe on release. {@code direction} is {@link #FORWARD} when
         * the finger moved up, or {@link #BACKWARD} when it moved down.
         */
        void onDiscreteSwipe(int direction, int steps);
    }

    public static final int BACKWARD = -1;
    public static final int FORWARD = 1;

    private final int touchSlop;
    private final DiscreteDrag discreteDrag = new DiscreteDrag();
    private final int defaultPaddingLeft;
    private final int defaultPaddingTop;
    private final int defaultPaddingRight;
    private final int defaultPaddingBottom;
    private final boolean defaultClipToPadding;
    private final boolean defaultFastScroll;
    private final int defaultOverScrollMode;

    private SwipeListener swipeListener;
    private boolean discreteMode;
    /** Exact extent of a discrete row; supplied by the adapter. */
    private int discreteRowHeight;
    private boolean trackingTouch;
    private boolean ignoreGesture;
    private boolean capturedSwipe;
    private float downX;
    private float downY;

    public DiscreteListView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        defaultPaddingLeft = getPaddingLeft();
        defaultPaddingTop = getPaddingTop();
        defaultPaddingRight = getPaddingRight();
        defaultPaddingBottom = getPaddingBottom();
        defaultClipToPadding = getClipToPadding();
        defaultFastScroll = isFastScrollEnabled();
        defaultOverScrollMode = getOverScrollMode();
    }

    public void setSwipeListener(SwipeListener listener) {
        swipeListener = listener;
    }

    public boolean isDiscreteMode() {
        return discreteMode;
    }

    /**
     * The adapter measures a one-line file row for the finger's step. Folder
     * rows stay compact in both modes; the bottom gutter accounts for their
     * smaller height separately.
     */
    public void setDiscreteRowHeight(int heightPx) {
        discreteRowHeight = heightPx;
        if (discreteMode) {
            updateDiscretePadding(getHeight());
            requestLayout();
        }
    }

    public void setDiscreteMode(boolean enabled) {
        if (discreteMode == enabled) {
            return;
        }
        discreteMode = enabled;
        trackingTouch = false;
        ignoreGesture = false;
        capturedSwipe = false;
        discreteDrag.reset();
        setSmoothScrollbarEnabled(!enabled);
        setFastScrollEnabled(enabled ? false : defaultFastScroll);
        setOverScrollMode(enabled ? View.OVER_SCROLL_NEVER : defaultOverScrollMode);
        setClipToPadding(enabled || defaultClipToPadding);
        if (enabled) {
            // Keep whole rows visible even with compact folder delimiters.
            updateDiscretePadding(getHeight());
        } else {
            setPadding(defaultPaddingLeft, defaultPaddingTop,
                    defaultPaddingRight, defaultPaddingBottom);
        }
        requestLayout();
        invalidate();
    }

    /** Recalculates the bottom inset after the adapter's row height changes. */
    public void refreshDiscreteLayout() {
        if (discreteMode) {
            updateDiscretePadding(getHeight());
            requestLayout();
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (discreteMode) {
            updateDiscretePadding(height);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (discreteMode) {
            // The actual ListView item layout params determine whether margins
            // contribute to its height. Use the laid-out rows rather than
            // assuming the framework kept XML margins in its own LayoutParams.
            updateDiscretePadding(getHeight());
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!discreteMode) {
            return super.onInterceptTouchEvent(event);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                trackingTouch = true;
                capturedSwipe = false;
                discreteDrag.reset();
                ignoreGesture = startsOnInteractiveChild(event.getX(), event.getY());
                return false;

            case MotionEvent.ACTION_MOVE:
                if (!trackingTouch || ignoreGesture) {
                    return false;
                }
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                // Let the outer SwipePager own a horizontal gesture.
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                    trackingTouch = false;
                    return false;
                }
                if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx) * 1.25f) {
                    capturedSwipe = true;
                    return true;
                }
                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                trackingTouch = false;
                ignoreGesture = false;
                return false;

            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!discreteMode || !capturedSwipe) {
            return super.onTouchEvent(event);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                // Apply each newly crossed row now, not all of them on release.
                // Do not pass the drag to AbsListView: it would also scroll by
                // pixels and start a fling when the finger is released.
                applyDelta(discreteDrag.update(event.getY() - downY, rowExtent()));
                return true;

            case MotionEvent.ACTION_UP:
                // finish() applies only the unreported remainder. It also keeps
                // the useful short-swipe behaviour: one row even below rowExtent.
                applyDelta(discreteDrag.finish(event.getY() - downY, rowExtent()));
                resetGesture();
                return true;

            case MotionEvent.ACTION_CANCEL:
                resetGesture();
                return true;

            default:
                return true;
        }
    }

    private void applyDelta(int signedSteps) {
        if (signedSteps == 0 || swipeListener == null) {
            return;
        }
        swipeListener.onDiscreteSwipe(signedSteps > 0 ? FORWARD : BACKWARD,
                Math.abs(signedSteps));
    }

    private void resetGesture() {
        trackingTouch = false;
        ignoreGesture = false;
        capturedSwipe = false;
        discreteDrag.reset();
    }

    private boolean startsOnInteractiveChild(float x, float y) {
        int position = pointToPosition((int) x, (int) y);
        if (position == INVALID_POSITION) {
            return false;
        }
        View row = getChildAt(position - getFirstVisiblePosition());
        if (row == null) {
            return false;
        }
        return hasInteractiveDescendant(row, x - row.getLeft(), y - row.getTop());
    }

    /** The item root itself is clickable, but the delete button must keep taps. */
    private static boolean hasInteractiveDescendant(View parent, float x, float y) {
        if (!(parent instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) parent;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            float childX = x - child.getLeft() + group.getScrollX();
            float childY = y - child.getTop() + group.getScrollY();
            if (childX < 0f || childY < 0f
                    || childX >= child.getWidth() || childY >= child.getHeight()) {
                continue;
            }
            if (child.isClickable() || child.isLongClickable() || child.isFocusable()) {
                return true;
            }
            if (hasInteractiveDescendant(child, childX, childY)) {
                return true;
            }
        }
        return false;
    }

    private int rowExtent() {
        if (discreteMode && discreteRowHeight > 0) {
            return discreteRowHeight;
        }
        if (getChildCount() > 1) {
            int extent = getChildAt(1).getTop() - getChildAt(0).getTop();
            if (extent > 0) {
                return extent;
            }
        }
        if (getChildCount() > 0) {
            View child = getChildAt(0);
            int extent = child.getHeight();
            ViewGroup.LayoutParams params = child.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                extent += margins.topMargin + margins.bottomMargin;
            }
            if (extent > 0) {
                return extent;
            }
        }
        // AbsListView.LayoutParams do not retain the XML row margins. Using an
        // imaginary +4 dp here made the first padding calculation disagree with
        // the laid-out rows and could expose a clipped final row.
        return getResources().getDimensionPixelSize(R.dimen.row_min_height);
    }

    private void updateDiscretePadding(int height) {
        if (!discreteMode || height <= 0) {
            return;
        }
        int extent = rowExtent();
        if (extent <= 0) {
            return;
        }
        // Fit complete rows using their actual fixed discrete heights, not
        // height % fileHeight: folder headers and closing rules are shorter.
        ListAdapter adapter = getAdapter();
        int used = 0;
        int folderHeight = getResources().getDimensionPixelSize(R.dimen.folder_delimiter_height);
        if (adapter != null) {
            for (int i = getFirstVisiblePosition(); i < adapter.getCount(); i++) {
                int rowHeight = adapter.getItemViewType(i) == FileListRows.TYPE_FOLDER
                        ? folderHeight : extent;
                if (used + rowHeight > height) {
                    break;
                }
                used += rowHeight;
            }
        }
        int bottomPadding = height - used;
        if (getPaddingTop() != 0 || getPaddingBottom() != bottomPadding) {
            setPadding(getPaddingLeft(), 0, getPaddingRight(), bottomPadding);
        }
    }
}

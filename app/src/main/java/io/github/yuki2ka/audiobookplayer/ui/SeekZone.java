package io.github.yuki2ka.audiobookplayer.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import io.github.yuki2ka.audiobookplayer.util.SeekCurve;

/**
 * The seek row: the three time labels plus the seek bar, in one touchable band.
 *
 * <p>A 44 dp slider is a small target on a low-resolution screen, and the time
 * labels above it used to be dead space. Touches that miss the slider are
 * mapped to the same position on it, so the whole row — labels included —
 * scrubs the track without costing any extra layout height.</p>
 *
 * <p>Touches inside the bar are left alone, so dragging the thumb keeps its
 * native behaviour.</p>
 */
public final class SeekZone extends LinearLayout {

    /** Receives the scrub from the parts of the row outside the slider. */
    public interface Listener {
        /** Called while the finger moves; update the thumb and the labels. */
        void onSeekPreview(int progress);

        /** Called on release; perform the actual seek. */
        void onSeekCommit(int progress);
    }

    private SeekBar slider;
    private Listener listener;
    private boolean scrubbing;
    /**
     * Latched per gesture: once a press starts on the bar it stays the bar's
     * gesture, even if the finger wanders above or below it while dragging.
     * Otherwise the zone would intercept a drag halfway through and strand the
     * slider with a cancelled gesture.
     */
    private boolean sliderGesture;
    private float downX;
    private float downY;
    private final int touchSlop;

    public SeekZone(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** The slider whose range and insets this zone mirrors. */
    public void setSlider(SeekBar bar) {
        this.slider = bar;
    }

    public void setListener(Listener value) {
        this.listener = value;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (slider == null || listener == null || !isEnabled()) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            sliderGesture = isInsideSlider(event);
        }
        // Inside the bar the slider handles everything itself, drags included.
        return !sliderGesture;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (slider == null || listener == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scrubbing = true;
                downX = event.getX();
                downY = event.getY();
                preview(downX);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!scrubbing) {
                    return false;
                }
                float dx = Math.abs(event.getX() - downX);
                float dy = Math.abs(event.getY() - downY);
                if (dx <= touchSlop && dy <= touchSlop) {
                    return true;
                }
                if (dy > dx) {
                    // A vertical drag belongs to the enclosing ScrollView (the
                    // landscape panel scrolls); stop scrubbing and let it win.
                    scrubbing = false;
                    return false;
                }
                // Horizontal scrub: keep the ScrollView from stealing it.
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                preview(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
                if (!scrubbing) {
                    return false;
                }
                scrubbing = false;
                commit(event.getX());
                return true;
            case MotionEvent.ACTION_CANCEL:
                scrubbing = false;
                return true;
            default:
                return false;
        }
    }

    private boolean isInsideSlider(MotionEvent event) {
        if (slider.getVisibility() != VISIBLE) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        return x >= slider.getLeft() && x <= slider.getRight()
                && y >= slider.getTop() && y <= slider.getBottom();
    }

    private int progressAt(float x) {
        return SeekCurve.progressForX(x, getWidth(),
                slider.getPaddingStart(), slider.getPaddingEnd(), slider.getMax());
    }

    private void preview(float x) {
        int progress = progressAt(x);
        slider.setProgress(progress);
        listener.onSeekPreview(progress);
    }

    private void commit(float x) {
        int progress = progressAt(x);
        slider.setProgress(progress);
        listener.onSeekCommit(progress);
    }
}

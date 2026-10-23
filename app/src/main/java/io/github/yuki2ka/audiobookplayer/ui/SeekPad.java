package io.github.yuki2ka.audiobookplayer.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.yuki2ka.audiobookplayer.R;
import io.github.yuki2ka.audiobookplayer.util.SeekCurve;

/**
 * The proportional seek pad next to the play button.
 *
 * <p>It seeks by an amount that depends on where it is touched: the edge next
 * to the play button is the minimum ({@value #MIN_SECONDS} s), the outer edge is
 * the maximum ({@value #MAX_SECONDS} s). Dragging changes the amount live, and
 * the current value is shown inside the pad. The curve is quadratic so the
 * inner half stays fine-grained (a 2-6 s nudge is easy on a small screen) while
 * the outer part still reaches a full minute.</p>
 *
 * <p>Two views of this class exist (back and forward). The arrow, the label and
 * the ripple background are built in code so the layout stays short and no ids
 * are duplicated.</p>
 */
public final class SeekPad extends FrameLayout {

    public interface Listener {
        /** Performs the seek; {@code deltaMs} is negative for "back". */
        void onSeek(long deltaMs);
    }

    private static final int MIN_SECONDS = SeekCurve.MIN_SECONDS;

    private final ImageView icon;
    private final TextView value;
    private final int colorIdle;
    private final int colorActive;

    private Listener listener;
    private int direction = 1;
    private int seconds = MIN_SECONDS;

    public SeekPad(Context context, AttributeSet attrs) {
        super(context, attrs);
        colorIdle = context.getColor(R.color.text_secondary);
        colorActive = context.getColor(R.color.accent);
        // Read the direction before building the views: it decides the arrow.
        TypedArray typed = context.obtainStyledAttributes(attrs, R.styleable.SeekPad);
        try {
            direction = typed.getInt(R.styleable.SeekPad_padDirection, 1) >= 0 ? 1 : -1;
        } finally {
            typed.recycle();
        }

        setClickable(true);
        setBackgroundResource(R.drawable.bg_button_soft);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));

        value = new TextView(context);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.setMarginStart(dp(6));
        row.addView(value, valueParams);

        applyDirection();
        showRange();
    }

    /** {@code -1} for the back pad, {@code +1} for the forward pad. */
    public void setDirection(int value) {
        direction = value >= 0 ? 1 : -1;
        applyDirection();
        showRange();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private void applyDirection() {
        icon.setImageResource(direction > 0 ? R.drawable.ic_chevron_right : R.drawable.ic_chevron_left);
        tint(colorIdle);
    }

    private void tint(int color) {
        ColorStateList list = ColorStateList.valueOf(color);
        icon.setImageTintList(list);
        value.setTextColor(color);
    }

    /** Idle label: the range this pad covers, so the gesture is discoverable. */
    private void showRange() {
        seconds = MIN_SECONDS;
        value.setText(getContext().getString(R.string.pad_range));
        setContentDescription(getContext().getString(
                direction > 0 ? R.string.pad_forward : R.string.pad_back, MIN_SECONDS));
    }

    private void setSeconds(int newSeconds) {
        seconds = newSeconds;
        value.setText(getContext().getString(R.string.pad_seconds, seconds));
        setContentDescription(getContext().getString(
                direction > 0 ? R.string.pad_forward : R.string.pad_back, seconds));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Keep the drag on this pad instead of letting a parent scroll.
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                setPressed(true);
                tint(colorActive);
                track(event.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                track(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
                track(event.getX());
                // Seek first (uses the value under the finger), then reset the
                // label; resetting earlier would always seek the minimum.
                performClick();
                release();
                return true;
            case MotionEvent.ACTION_CANCEL:
                release();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (listener != null) {
            listener.onSeek(direction * seconds * 1000L);
        }
        return true;
    }

    private void release() {
        setPressed(false);
        tint(colorIdle);
        showRange();
    }

    /** Maps a touch x to 2..60 s: inner edge = 2 s, outer edge = 60 s. */
    private void track(float x) {
        int width = getWidth();
        if (width <= 0) {
            return;
        }
        int next = SeekCurve.secondsFor(SeekCurve.fraction(direction, x, width));
        if (next != seconds) {
            setSeconds(next);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

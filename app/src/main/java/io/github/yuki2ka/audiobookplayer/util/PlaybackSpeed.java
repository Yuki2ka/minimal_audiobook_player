package io.github.yuki2ka.audiobookplayer.util;

/**
 * Speed arithmetic for the two play buttons. Pure Java (no Android imports) so
 * the press logic and the labels can be unit tested on a desktop JVM.
 *
 * <p>Speeds are stored as integer <em>percent</em> of normal speed: {@code 100}
 * is x1.0, {@code 130} is x1.3. Integers avoid the float rounding that would
 * otherwise make "is the fast speed already active?" a guess, and they keep the
 * slider position exactly representable.</p>
 */
public final class PlaybackSpeed {

    /** Normal speed; the plain play button always requests this. */
    public static final int NORMAL_PERCENT = 100;

    /**
     * Fast play button: x1.1 … x2.0 in 0.1 steps, defaults to x1.3. The settings
     * slider has {@link #FAST_STEP_COUNT} + 1 positions.
     */
    public static final int MIN_FAST_PERCENT = 110;
    public static final int MAX_FAST_PERCENT = 200;
    public static final int DEFAULT_FAST_PERCENT = 130;
    public static final int FAST_STEP_PERCENT = 10;
    public static final int FAST_STEP_COUNT = (MAX_FAST_PERCENT - MIN_FAST_PERCENT) / FAST_STEP_PERCENT;

    /** What a press of a play button has to do. */
    public static final int ACTION_PAUSE = 0;
    public static final int ACTION_PLAY = 1;
    public static final int ACTION_SWITCH = 2;

    private PlaybackSpeed() {
    }

    /**
     * Decides what a play button press means.
     *
     * <ul>
     *   <li>Playing at the requested speed → pause (second press of the same
     *       button).</li>
     *   <li>Playing at another speed → switch speed, keep playing.</li>
     *   <li>Paused (or nothing loaded) → play, using the requested speed.</li>
     * </ul>
     */
    public static int actionFor(boolean playing, int activePercent, int requestedPercent) {
        if (!playing) {
            return ACTION_PLAY;
        }
        return activePercent == requestedPercent ? ACTION_PAUSE : ACTION_SWITCH;
    }

    /** {@code true} for anything faster than normal speed. */
    public static boolean isFast(int percent) {
        return percent > NORMAL_PERCENT;
    }

    /**
     * Label for a speed: {@code 110 → "x1.1"}, {@code 130 → "x1.3"},
     * {@code 200 → "x2"}, {@code 125 → "x1.25"}.
     */
    public static String label(int percent) {
        int clamped = percent < 0 ? 0 : percent;
        StringBuilder sb = new StringBuilder(6);
        sb.append('x').append(clamped / 100);
        int hundredths = clamped % 100;
        if (hundredths != 0) {
            sb.append('.');
            int tenths = hundredths / 10;
            sb.append(tenths);
            if (hundredths % 10 != 0) {
                sb.append(hundredths % 10);
            }
        }
        return sb.toString();
    }

    /** Same label, from the float the engine reports back. */
    public static String label(float speed) {
        return label(Math.round(speed * 100f));
    }

    /** Speed (percent) for a slider position; out-of-range steps clamp. */
    public static int fromStep(int step) {
        int value = MIN_FAST_PERCENT + step * FAST_STEP_PERCENT;
        return value < MIN_FAST_PERCENT
                ? MIN_FAST_PERCENT : (value > MAX_FAST_PERCENT ? MAX_FAST_PERCENT : value);
    }

    /** Slider position of a speed; the inverse of {@link #fromStep}. */
    public static int toStep(int percent) {
        int clamped = percent < MIN_FAST_PERCENT
                ? MIN_FAST_PERCENT : (percent > MAX_FAST_PERCENT ? MAX_FAST_PERCENT : percent);
        return Math.round((float) (clamped - MIN_FAST_PERCENT) / FAST_STEP_PERCENT);
    }

    /** Snaps a percent value to the 0.1 grid the slider can express. */
    public static int snap(int percent) {
        return fromStep(toStep(percent));
    }

    /** Converts a percent value to the float {@code MediaPlayer} wants. */
    public static float toFloat(int percent) {
        return percent / 100f;
    }

    /** Converts an engine speed back to percent, rounding away 1.2999… noise. */
    public static int toPercent(float speed) {
        return Math.round(speed * 100f);
    }
}

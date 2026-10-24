package io.github.yuki2ka.audiobookplayer.util;

/**
 * Maps a position inside the proportional seek pad (0 = next to the play
 * button, 1 = outer edge) to a seek amount in seconds.
 *
 * <p>Pure Java and Android-free so the curve is covered by the desktop tests.
 * The curve is quadratic: the inner half of the pad covers 2-16 s in 1-2 s
 * steps (fine control for "I missed a sentence"), the outer half stretches to
 * a full minute for "I fell asleep an hour ago".</p>
 */
public final class SeekCurve {

    public static final int MIN_SECONDS = 2;
    public static final int MAX_SECONDS = 60;

    private SeekCurve() {
    }

    /**
     * Position of a touch inside a pad as 0..1, measured from the edge that
     * faces the play button: 0 next to the button, 1 at the outer edge.
     *
     * <p>Both pads therefore grow outwards: the forward pad counts up to the
     * right, the back pad counts up to the left (mirrored).</p>
     *
     * @param direction {@code -1} for the back pad, {@code +1} for the forward pad
     * @param x         touch position inside the pad, in pixels
     * @param width     pad width, in pixels
     */
    public static float fraction(int direction, float x, int width) {
        if (width <= 0) {
            return 0f;
        }
        float value = direction > 0 ? x / width : (width - x) / width;
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    /**
     * Position of a touch inside the whole seek row (the time labels above the
     * slider and the slider itself) as 0..1.
     *
     * <p>The slider keeps its track inset from the edges so the thumb is never
     * clipped; a tap anywhere in the row has to use the same usable width, or
     * tapping above a given point would seek somewhere else than tapping the
     * bar below it.</p>
     *
     * @param x          touch position in pixels, relative to the row
     * @param width      row width in pixels
     * @param insetStart left track inset in pixels
     * @param insetEnd   right track inset in pixels
     */
    public static float fractionForX(float x, float width, float insetStart, float insetEnd) {
        float usable = width - insetStart - insetEnd;
        if (usable <= 0f) {
            return 0f;
        }
        float value = (x - insetStart) / usable;
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    /** Progress for a tapped row, given the slider's maximum. */
    public static int progressForX(float x, float width, float insetStart, float insetEnd, int max) {
        if (max <= 0) {
            return 0;
        }
        return Math.round(fractionForX(x, width, insetStart, insetEnd) * max);
    }

    /** {@code fraction} is clamped to 0..1; the result is 2..60 seconds. */
    public static int secondsFor(float fraction) {
        float clamped = fraction < 0f ? 0f : (fraction > 1f ? 1f : fraction);
        int range = MAX_SECONDS - MIN_SECONDS;
        return MIN_SECONDS + Math.round(clamped * clamped * range);
    }

    public static long millisecondsFor(float fraction) {
        return secondsFor(fraction) * 1000L;
    }

    /**
     * Target position for a "+/- step" action (the -N/+N buttons, the volume
     * keys), clamped to the track.
     *
     * @param position  current position in ms
     * @param direction {@code -1} back, {@code +1} forward
     * @param stepMs    step size in ms
     * @param duration  track duration in ms (0 or less = unknown, no seek)
     */
    public static int steppedPosition(int position, int direction, int stepMs, int duration) {
        if (duration <= 0) {
            return position;
        }
        long target = (long) position + (long) (direction >= 0 ? 1 : -1) * stepMs;
        if (target < 0) {
            target = 0;
        } else if (target > duration) {
            target = duration;
        }
        return (int) target;
    }
}

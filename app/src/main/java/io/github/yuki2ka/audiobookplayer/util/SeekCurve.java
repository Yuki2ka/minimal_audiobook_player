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

    /** {@code fraction} is clamped to 0..1; the result is 2..60 seconds. */
    public static int secondsFor(float fraction) {
        float clamped = fraction < 0f ? 0f : (fraction > 1f ? 1f : fraction);
        int range = MAX_SECONDS - MIN_SECONDS;
        return MIN_SECONDS + Math.round(clamped * clamped * range);
    }

    public static long millisecondsFor(float fraction) {
        return secondsFor(fraction) * 1000L;
    }
}

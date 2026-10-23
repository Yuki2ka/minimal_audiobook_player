package io.github.yuki2ka.audiobookplayer.util;

/**
 * Formats playback times. Pure Java (no Android imports) so it can be unit
 * tested on a desktop JVM.
 *
 * <p>Output examples: {@code 0:07}, {@code 12:34}, {@code 1:02:33}.
 * Hours are only shown when needed, which keeps the labels narrow enough for
 * small screens.</p>
 */
public final class TimeFormat {

    private TimeFormat() {
    }

    public static String clock(long milliseconds) {
        if (milliseconds < 0) {
            milliseconds = 0;
        }
        long totalSeconds = milliseconds / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder sb = new StringBuilder(8);
        if (hours > 0) {
            sb.append(hours).append(':');
            if (minutes < 10) {
                sb.append('0');
            }
        }
        sb.append(minutes).append(':');
        if (seconds < 10) {
            sb.append('0');
        }
        sb.append(seconds);
        return sb.toString();
    }

    public static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    public static long clamp(long value, long min, long max) {
        return value < min ? min : (value > max ? max : value);
    }
}

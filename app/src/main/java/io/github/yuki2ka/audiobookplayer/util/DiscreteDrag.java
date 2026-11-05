package io.github.yuki2ka.audiobookplayer.util;

/**
 * Converts a finger's cumulative vertical distance into whole-row deltas.
 * Positive steps move forward through the list (finger up); negative steps
 * move backward (finger down). The class is Android-free for JVM testing.
 *
 * <p>Only the delta since the previous call is returned. This lets the view
 * update while the finger is still moving, including direction reversals,
 * without applying the whole gesture again on release.</p>
 */
public final class DiscreteDrag {

    private int deliveredSteps;

    /** Returns newly crossed whole rows, or zero inside the current row. */
    public int update(float fingerDistanceY, int rowExtent) {
        if (rowExtent <= 0) {
            return 0;
        }
        // Java truncation toward zero intentionally waits for a complete row.
        int targetSteps = (int) (-fingerDistanceY / (float) rowExtent);
        int delta = targetSteps - deliveredSteps;
        deliveredSteps = targetSteps;
        return delta;
    }

    /**
     * Applies any remaining whole-row delta. A short captured swipe still
     * moves one row on release, matching the original discrete-list behaviour.
     */
    public int finish(float fingerDistanceY, int rowExtent) {
        int delta = update(fingerDistanceY, rowExtent);
        if (deliveredSteps == 0 && fingerDistanceY != 0f) {
            deliveredSteps = fingerDistanceY < 0f ? 1 : -1;
            return deliveredSteps;
        }
        return delta;
    }

    public void reset() {
        deliveredSteps = 0;
    }
}

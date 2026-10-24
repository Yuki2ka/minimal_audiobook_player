package io.github.yuki2ka.audiobookplayer.util;

/**
 * Turns the volume-key event stream into a seek or a volume gesture.
 *
 * <p>Android forwards a volume key to a remote-volume media session as an
 * <em>adjustment</em>: {@code +1} while the key goes down (and once more for
 * every auto-repeat), then {@code 0} when it is released. There is no separate
 * down/up pair, so the gestures are told apart like this:</p>
 *
 * <ul>
 *   <li>a release before {@link #SHORT_PRESS_MS} is a tap &rarr; {@link #SEEK};</li>
 *   <li>a repeat before the release means the key is held &rarr; {@link #VOLUME};</li>
 *   <li>a release after {@link #SHORT_PRESS_MS} with no repeat: held on a device
 *       that does not auto-repeat &rarr; still {@link #VOLUME};</li>
 *   <li>no release at all (see {@link #onTimeout()}) &rarr; {@link #VOLUME}, so a
 *       swallowed event can never leave the keys doing nothing.</li>
 * </ul>
 *
 * <p>Pure logic with an injected clock so it can be tested on the desktop JVM;
 * the Android side only supplies the events and applies the result.</p>
 */
public final class VolumeGesture {

    /** Nothing to do. */
    public static final int NONE = 0;
    /** Short press: seek by the configured step. */
    public static final int SEEK = 1;
    /** Hold: change the music stream volume. */
    public static final int VOLUME = 2;

    /** A release earlier than this counts as a tap. */
    public static final int SHORT_PRESS_MS = 450;
    /** Safety net for platforms that never deliver the release event. */
    public static final int RELEASE_TIMEOUT_MS = 2500;

    private boolean pressing;
    private boolean ramping;
    private int direction;
    private long pressedAt;

    /**
     * Feeds one adjustment: {@code +1}/{@code -1} for a key event, {@code 0}
     * for the release.
     *
     * @return {@link #NONE}, {@link #SEEK} or {@link #VOLUME}
     */
    public int onAdjust(int value, long nowMs) {
        if (value == 0) {
            return onRelease(nowMs);
        }
        direction = value > 0 ? 1 : -1;
        if (pressing) {
            // A repeat: the key is held down, so this gesture is about volume.
            ramping = true;
            return VOLUME;
        }
        pressing = true;
        ramping = false;
        pressedAt = nowMs;
        return NONE;
    }

    private int onRelease(long nowMs) {
        if (!pressing) {
            return NONE;
        }
        pressing = false;
        if (ramping) {
            // The hold already acted as a volume gesture.
            ramping = false;
            return NONE;
        }
        return nowMs - pressedAt < SHORT_PRESS_MS ? SEEK : VOLUME;
    }

    /**
     * Called when the release event did not arrive in time. Keeps the key
     * useful instead of leaving the gesture dangling forever.
     */
    public int onTimeout() {
        if (!pressing) {
            return NONE;
        }
        pressing = false;
        ramping = false;
        return VOLUME;
    }

    /** Direction of the current (or last) press: {@code +1} or {@code -1}. */
    public int direction() {
        return direction;
    }

    /** True between the key going down and its release. */
    public boolean isPressing() {
        return pressing;
    }

    /** Forgets any gesture in progress, e.g. when the screen turns on. */
    public void reset() {
        pressing = false;
        ramping = false;
        direction = 0;
    }
}

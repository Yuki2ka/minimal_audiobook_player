package io.github.yuki2ka.audiobookplayer.playback;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import io.github.yuki2ka.audiobookplayer.data.Prefs;
import io.github.yuki2ka.audiobookplayer.util.SeekCurve;

/**
 * Turns the hardware volume keys into seek buttons while the screen is off.
 *
 * <p>The screen has to be off for this to be desirable: lying in bed, the phone
 * is in a pocket and the volume rocker is the only reachable control. With the
 * screen on, the keys keep their normal meaning (the system volume UI).</p>
 *
 * <h3>How the keys are captured</h3>
 * While the screen is off, playback is running and the option is enabled, a
 * {@link MediaSession} is made active and reports a <em>remote</em> volume
 * ({@link VolumeProvider}). The system then routes the volume rocker to
 * {@link VolumeProvider#onAdjustVolume(int)} instead of the audio stream. On
 * screen-on (or pause) the session is deactivated again, so normal volume
 * behaviour and the system volume panel come back.
 *
 * <h3>Short press vs. hold</h3>
 * {@code onAdjustVolume} reports adjustments, not key up/down, so the two
 * gestures are told apart by timing: the first event arms a timer shorter than
 * the platform key-repeat delay. If nothing else arrives, it was a short press
 * and the pad-equivalent seek runs. If another adjustment arrives first, the
 * key is being held, the seek is cancelled and the stream volume is adjusted
 * for the rest of the gesture. A single hold can therefore cost at most one
 * delayed seek on devices that repeat faster than expected.
 */
public final class VolumeKeySeeker {

    /** Playback side of the bridge, implemented by {@link PlayerEngine}. */
    public interface Target {
        int position();

        int duration();

        void seekTo(int positionMs);
    }

    /**
     * How long a lone adjustment waits before it counts as a short press.
     * Slightly longer than the platform key-repeat delay (~500 ms), so a held
     * key produces its first repeat before this fires.
     */
    private static final int SHORT_PRESS_MS = 600;
    /** Silence after which a hold is considered finished. */
    private static final int HOLD_IDLE_MS = 500;

    private final Context context;
    private final AudioManager audioManager;
    private final PowerManager powerManager;
    private final Prefs prefs;
    private final Target target;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaSession session;
    private VolumeProvider provider;
    private boolean screenOn;
    private boolean playing;
    private boolean pending;
    private boolean holding;
    private int pendingDirection;

    private final Runnable shortPress = new Runnable() {
        @Override
        public void run() {
            if (!pending) {
                return;
            }
            pending = false;
            seek(pendingDirection);
        }
    };

    private final Runnable holdFinished = new Runnable() {
        @Override
        public void run() {
            holding = false;
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOn = false;
                refresh();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOn = true;
                cancelPending();
                refresh();
            }
        }
    };

    public VolumeKeySeeker(Context context, Target target) {
        this.context = context.getApplicationContext();
        this.target = target;
        this.prefs = new Prefs(this.context);
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.powerManager = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
        this.screenOn = powerManager == null || powerManager.isInteractive();
        registerScreenReceiver();
    }

    private void registerScreenReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(screenReceiver, filter);
            }
        } catch (RuntimeException ignored) {
            // Some ROMs restrict receiver registration; the keys simply keep
            // their normal meaning there.
        }
    }

    /** Called by the player whenever playback starts or stops. */
    public void setPlaying(boolean value) {
        playing = value;
        if (!playing) {
            cancelPending();
        }
        refresh();
    }

    /** Called after the setting changed. */
    public void refresh() {
        boolean wanted = playing && !screenOn && prefs.volumeKeysSeek();
        if (wanted) {
            activate();
        } else {
            deactivate();
        }
    }

    private void activate() {
        if (audioManager == null) {
            return;
        }
        try {
            if (session == null) {
                session = new MediaSession(context, "Audiobooks volume keys");
                provider = new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                    @Override
                    public void onAdjustVolume(int direction) {
                        onAdjust(direction);
                    }
                };
            }
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, Math.max(0, target.position()), 1f)
                    .build());
            session.setPlaybackToRemote(provider);
            session.setActive(true);
        } catch (RuntimeException e) {
            // A ROM that refuses remote volume must not break playback.
            if (session != null) {
                try {
                    session.release();
                } catch (RuntimeException ignored) {
                    // Nothing sensible to do.
                }
            }
            session = null;
            provider = null;
        }
    }

    private void deactivate() {
        cancelPending();
        if (session != null) {
            try {
                session.setActive(false);
            } catch (RuntimeException ignored) {
                // Nothing sensible to do; the session is inert either way.
            }
        }
    }

    private void cancelPending() {
        handler.removeCallbacks(shortPress);
        handler.removeCallbacks(holdFinished);
        pending = false;
        holding = false;
    }

    private void onAdjust(int direction) {
        if (audioManager == null) {
            return;
        }
        // Screen came back on between the key press and the callback.
        if (screenOn || !prefs.volumeKeysSeek()) {
            adjustStream(direction);
            return;
        }
        pendingDirection = direction >= 0 ? 1 : -1;
        if (holding) {
            adjustStream(direction);
            handler.removeCallbacks(holdFinished);
            handler.postDelayed(holdFinished, HOLD_IDLE_MS);
            return;
        }
        if (pending) {
            // Second adjustment before the short-press timer fired: the key is
            // held down, so this is a volume gesture after all.
            handler.removeCallbacks(shortPress);
            pending = false;
            holding = true;
            adjustStream(direction);
            handler.removeCallbacks(holdFinished);
            handler.postDelayed(holdFinished, HOLD_IDLE_MS);
            return;
        }
        pending = true;
        handler.postDelayed(shortPress, SHORT_PRESS_MS);
    }

    /** Seeks by the configured step, clamped to the track. */
    private void seek(int direction) {
        int duration = target.duration();
        if (duration <= 0) {
            return;
        }
        int next = SeekCurve.steppedPosition(
                target.position(), direction, prefs.seekStep() * 1000, duration);
        target.seekTo(next);
    }

    private void adjustStream(int direction) {
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    direction >= 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI);
        } catch (RuntimeException ignored) {
            // Ignore ROMs that refuse the call.
        }
    }

    /** True when the volume keys are currently handled by this app. */
    public boolean isCapturing() {
        return session != null && session.isActive();
    }
}

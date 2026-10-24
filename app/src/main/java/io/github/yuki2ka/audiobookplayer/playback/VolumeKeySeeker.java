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
import android.os.SystemClock;

import io.github.yuki2ka.audiobookplayer.data.Prefs;
import io.github.yuki2ka.audiobookplayer.util.SeekCurve;
import io.github.yuki2ka.audiobookplayer.util.VolumeGesture;

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
 * <p><b>The session must have a callback.</b> {@code MediaSession} drops every
 * command while no {@code MediaSession.Callback} is installed: the internal
 * {@code postToCallback()} only queues work once {@code setCallback()} created
 * its handler. Without one the system routes the keys to the app and then
 * throws them away, so the keys neither seek nor change the volume.</p>
 *
 * <h3>What the platform actually sends</h3>
 * {@code MediaSessionLegacyHelper.sendVolumeKeyEvent()} forwards the key event
 * as an adjustment, including the release: {@code ACTION_DOWN} (and every
 * repeat) arrives as {@code +1}/{@code -1}, {@code ACTION_UP} as {@code 0}.
 * {@link VolumeGesture} turns that stream into a tap (seek) or a hold (stream
 * volume) without depending on key auto-repeat; the timeout below is only the
 * safety net for platforms that swallow the release event entirely.
 */
public final class VolumeKeySeeker {

    /** Playback side of the bridge, implemented by {@link PlayerEngine}. */
    public interface Target {
        int position();

        int duration();

        void seekTo(int positionMs);
    }

    private final Context context;
    private final AudioManager audioManager;
    private final PowerManager powerManager;
    private final Prefs prefs;
    private final Target target;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaSession session;
    private VolumeProvider provider;

    /**
     * Required for the session to deliver anything at all; see the class
     * comment. None of the media callbacks are needed here.
     */
    private final MediaSession.Callback callback = new MediaSession.Callback() {
    };

    private boolean screenOn;
    private boolean playing;

    private final VolumeGesture gesture = new VolumeGesture();

    private final Runnable releaseTimeout = new Runnable() {
        @Override
        public void run() {
            // No release event arrived: keep the key useful as a volume step.
            if (gesture.onTimeout() == VolumeGesture.VOLUME) {
                adjustStream(gesture.direction());
            }
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
                cancelGesture();
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
            cancelGesture();
        }
        refresh();
    }

    /** Called after the setting changed. */
    public void refresh() {
        if (playing && !screenOn && prefs.volumeKeysSeek()) {
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
                // Without this callback the system silently drops every
                // volume adjustment it routes here (see the class comment).
                session.setCallback(callback, handler);
                provider = new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                    @Override
                    public void onAdjustVolume(int direction) {
                        onAdjust(direction);
                    }

                    @Override
                    public void onSetVolumeTo(int volume) {
                        // Relative control has no slider; nothing to apply.
                    }
                };
                session.setPlaybackToRemote(provider);
            }
            publishState();
            session.setActive(true);
        } catch (RuntimeException e) {
            releaseSession();
        }
    }

    /** Keeps the system's idea of the session position up to date. */
    private void publishState() {
        if (session == null) {
            return;
        }
        try {
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, Math.max(0, target.position()), 1f)
                    .build());
        } catch (RuntimeException ignored) {
            // A stale session must not break key handling.
        }
    }

    private void deactivate() {
        cancelGesture();
        if (session != null) {
            try {
                session.setActive(false);
            } catch (RuntimeException ignored) {
                // Nothing sensible to do; the session is inert either way.
            }
        }
    }

    private void releaseSession() {
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

    private void cancelGesture() {
        handler.removeCallbacks(releaseTimeout);
        gesture.reset();
    }

    /**
     * One adjustment from the system: {@code direction} is {@code +1} for the
     * down-facing side, {@code -1} for the up-facing side, or {@code 0} when the
     * key is released.
     */
    private void onAdjust(int direction) {
        if (audioManager == null) {
            return;
        }
        if (direction != 0 && (screenOn || !prefs.volumeKeysSeek())) {
            // Session is on its way out: keep the key useful meanwhile.
            adjustStream(direction);
            return;
        }
        int action = gesture.onAdjust(direction, SystemClock.uptimeMillis());
        handler.removeCallbacks(releaseTimeout);
        if (gesture.isPressing()) {
            handler.postDelayed(releaseTimeout, VolumeGesture.RELEASE_TIMEOUT_MS);
        }
        if (action == VolumeGesture.SEEK) {
            seek(gesture.direction());
        } else if (action == VolumeGesture.VOLUME) {
            adjustStream(gesture.direction());
        }
    }

    /**
     * Seeks by the configured step, clamped to the track. When there is nothing
     * to seek (unknown duration yet) the key still does something useful
     * instead of being swallowed.
     */
    private void seek(int direction) {
        int duration = target.duration();
        if (duration <= 0) {
            adjustStream(direction);
            return;
        }
        int next = SeekCurve.steppedPosition(
                target.position(), direction, prefs.seekStep() * 1000, duration);
        target.seekTo(next);
        publishState();
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

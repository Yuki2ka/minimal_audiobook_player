package io.github.yuki2ka.audiobookplayer.playback;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.PowerManager;

import java.io.IOException;

/**
 * Thin wrapper around a single {@link MediaPlayer}.
 *
 * <p>Memory-wise this is deliberately small: exactly one decoder exists while a
 * file is loaded and it is released on every track change. No second player is
 * kept for gapless pre-loading, which matters on low-RAM devices.</p>
 *
 * <p>Also handled here: audio focus (pause on transient loss, duck on
 * "can duck"), headphone unplugging, and a partial wake lock so playback
 * continues with the screen off.</p>
 */
public final class PlayerEngine {

    public interface Listener {
        void onPrepared(int durationMs);

        void onPlayStateChanged(boolean playing);

        void onTrackEnded();

        void onError(int what, int extra);
    }

    private static final int IDLE = 0;
    private static final int PREPARING = 1;
    private static final int READY = 2;
    private static final int PLAYING = 3;
    private static final int PAUSED = 4;
    private static final int ENDED = 5;
    private static final int ERROR = 6;

    private final Context context;
    private final AudioManager audioManager;

    private MediaPlayer player;
    private Listener listener;
    private String path;
    private int state = IDLE;
    private int durationMs;
    private int startMs;
    private boolean playWhenPrepared;
    private boolean pausedForFocus;
    private boolean ducked;

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int change) {
                    switch (change) {
                        case AudioManager.AUDIOFOCUS_GAIN:
                            if (ducked) {
                                setVolume(1f);
                                ducked = false;
                            }
                            if (pausedForFocus && state == PAUSED) {
                                pausedForFocus = false;
                                play();
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS:
                            pausedForFocus = false;
                            if (state == PLAYING) {
                                pauseInternal();
                                abandonFocus();
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            if (state == PLAYING) {
                                pausedForFocus = true;
                                pauseInternal();
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            if (state == PLAYING && !ducked) {
                                setVolume(0.2f);
                                ducked = true;
                            }
                            break;
                        default:
                            break;
                    }
                }
            };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pausedForFocus = false;
                pauseInternal();
                abandonFocus();
            }
        }
    };

    public PlayerEngine(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        registerNoisyReceiver();
    }

    private void registerNoisyReceiver() {
        try {
            context.registerReceiver(noisyReceiver,
                    new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        } catch (RuntimeException ignored) {
            // Some ROMs restrict receiver registration; playback still works.
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean hasTrack() {
        return player != null && path != null && state != IDLE && state != ERROR;
    }

    public String currentPath() {
        return path;
    }

    public boolean isPlaying() {
        return state == PLAYING;
    }

    public int duration() {
        return durationMs;
    }

    public int position() {
        if (player == null) {
            return 0;
        }
        if (state != READY && state != PLAYING && state != PAUSED && state != ENDED) {
            return 0;
        }
        try {
            int value = player.getCurrentPosition();
            return value < 0 ? 0 : value;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    /**
     * Loads a file and optionally starts playing as soon as it is prepared.
     *
     * @param startMs   position to resume from, 0 for the beginning
     * @param autoPlay  start playback automatically
     */
    public void open(String filePath, int startMs, boolean autoPlay) {
        stopAndRelease();
        this.path = filePath;
        this.startMs = Math.max(0, startMs);
        this.playWhenPrepared = autoPlay;
        this.durationMs = 0;
        this.state = PREPARING;
        this.ducked = false;
        this.pausedForFocus = false;

        final MediaPlayer created = new MediaPlayer();
        player = created;
        try {
            created.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            created.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
            created.setDataSource(filePath);
            created.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    if (mp != player) {
                        return;
                    }
                    state = READY;
                    durationMs = Math.max(0, mp.getDuration());
                    if (startMs > 0 && startMs < durationMs) {
                        mp.seekTo(startMs);
                    }
                    if (playWhenPrepared) {
                        playWhenPrepared = false;
                        play();
                    } else {
                        notifyState(false);
                    }
                    if (listener != null) {
                        listener.onPrepared(durationMs);
                    }
                }
            });
            created.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (mp != player) {
                        return;
                    }
                    state = ENDED;
                    abandonFocus();
                    notifyState(false);
                    if (listener != null) {
                        listener.onTrackEnded();
                    }
                }
            });
            created.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    if (mp != player) {
                        return true;
                    }
                    state = ERROR;
                    abandonFocus();
                    if (listener != null) {
                        listener.onError(what, extra);
                    }
                    return true;
                }
            });
            created.prepareAsync();
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            state = ERROR;
            stopAndRelease();
            if (listener != null) {
                listener.onError(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
            }
        }
    }

    public void play() {
        if (player == null) {
            return;
        }
        if (state == PREPARING) {
            // Remember the intent; playback starts as soon as prepare finishes.
            playWhenPrepared = true;
            return;
        }
        if (state == ENDED) {
            seekTo(0);
        }
        if (state != READY && state != PAUSED) {
            return;
        }
        if (!requestFocus()) {
            return;
        }
        try {
            player.start();
            state = PLAYING;
            notifyState(true);
        } catch (IllegalStateException ignored) {
            // The player was released in the meantime.
        }
    }

    public void pause() {
        if (state == PREPARING) {
            playWhenPrepared = false;
            return;
        }
        pauseInternal();
        abandonFocus();
    }

    public void toggle() {
        if (state == PLAYING) {
            pause();
        } else {
            play();
        }
    }

    private void pauseInternal() {
        if (player == null || state != PLAYING) {
            return;
        }
        try {
            player.pause();
            state = PAUSED;
            notifyState(false);
        } catch (IllegalStateException ignored) {
            // Ignore races with completion.
        }
    }

    public void seekTo(int positionMs) {
        if (player == null || state == IDLE || state == PREPARING || state == ERROR) {
            return;
        }
        int target = positionMs;
        if (durationMs > 0 && target > durationMs) {
            target = durationMs;
        }
        if (target < 0) {
            target = 0;
        }
        try {
            player.seekTo(target);
            if (state == ENDED && target < durationMs) {
                state = PAUSED;
            }
        } catch (IllegalStateException ignored) {
            // Ignore.
        }
    }

    /** Stops playback and frees the decoder. */
    public void stopAndRelease() {
        if (player != null) {
            try {
                player.reset();
            } catch (IllegalStateException ignored) {
                // Ignore.
            }
            player.release();
            player = null;
        }
        state = IDLE;
        durationMs = 0;
        startMs = 0;
        playWhenPrepared = false;
        path = null;
        ducked = false;
        pausedForFocus = false;
        abandonFocus();
    }

    private void notifyState(boolean playing) {
        if (listener != null) {
            listener.onPlayStateChanged(playing);
        }
    }

    private void setVolume(float volume) {
        if (player != null) {
            try {
                player.setVolume(volume, volume);
            } catch (IllegalStateException ignored) {
                // Ignore.
            }
        }
    }

    @SuppressWarnings("deprecation")
    private boolean requestFocus() {
        if (audioManager == null) {
            return true;
        }
        int result = audioManager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        if (audioManager != null) {
            audioManager.abandonAudioFocus(focusListener);
        }
    }
}

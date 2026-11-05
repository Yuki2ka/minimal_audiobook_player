package io.github.yuki2ka.audiobookplayer.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.yuki2ka.audiobookplayer.util.PlaybackSpeed;
import io.github.yuki2ka.audiobookplayer.util.TimeFormat;

/**
 * Small settings store. SharedPreferences is used only for scalar values; the
 * (potentially long) list of playback positions lives in SQLite instead.
 */
public final class Prefs {

    private static final String FILE = "settings";

    private static final String KEY_ROOT = "root_path";
    private static final String KEY_SEEK_STEP = "seek_step_seconds";
    private static final String KEY_NO_CONFIRM = "delete_without_confirm";
    private static final String KEY_KEEP_AWAKE = "keep_screen_on";
    private static final String KEY_VOLUME_SEEK = "volume_keys_seek";
    private static final String KEY_DISCRETE_FILE_SCROLL = "discrete_file_scroll";
    private static final String KEY_LAST_FILE = "last_file";
    private static final String KEY_LAST_MODE = "last_mode";
    private static final String KEY_LAST_FOLDER = "last_folder";
    private static final String KEY_LAST_PAGE = "last_page";
    private static final String KEY_ROOT_HISTORY = "root_history";
    private static final String KEY_FAST_SPEED = "fast_speed_percent";

    /** How many previously chosen folders are offered in Settings. */
    private static final int MAX_HISTORY = 6;

    public static final int MODE_UNIFIED = 0;
    public static final int MODE_FOLDER = 1;

    public static final int DEFAULT_SEEK_STEP = 10;
    public static final int MIN_SEEK_STEP = 1;
    public static final int MAX_SEEK_STEP = 60;

    /** Speed of the fast play button, in percent (x1.1 … x2.0, default x1.3). */
    public static final int DEFAULT_FAST_SPEED = PlaybackSpeed.DEFAULT_FAST_PERCENT;
    public static final int MIN_FAST_SPEED = PlaybackSpeed.MIN_FAST_PERCENT;
    public static final int MAX_FAST_SPEED = PlaybackSpeed.MAX_FAST_PERCENT;

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String root(String fallback) {
        String value = prefs.getString(KEY_ROOT, null);
        return value == null || value.length() == 0 ? fallback : value;
    }

    public void setRoot(String path) {
        prefs.edit().putString(KEY_ROOT, path).apply();
        rememberRoot(path);
    }

    /**
     * Previously chosen folders, most recent first. Stored as newline separated
     * text: paths cannot contain a newline, so no JSON dependency is needed.
     */
    public List<String> rootHistory() {
        String raw = prefs.getString(KEY_ROOT_HISTORY, "");
        if (raw == null || raw.length() == 0) {
            return Collections.emptyList();
        }
        String[] parts = raw.split("\n");
        ArrayList<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.length() > 0 && !result.contains(part)) {
                result.add(part);
            }
        }
        return result;
    }

    /** Moves {@code path} to the front of the history (called by setRoot). */
    public void rememberRoot(String path) {
        if (path == null || path.length() == 0) {
            return;
        }
        ArrayList<String> list = new ArrayList<>(rootHistory());
        list.remove(path);
        list.add(0, path);
        while (list.size() > MAX_HISTORY) {
            list.remove(list.size() - 1);
        }
        saveHistory(list);
    }

    public void forgetRoot(String path) {
        ArrayList<String> list = new ArrayList<>(rootHistory());
        if (list.remove(path)) {
            saveHistory(list);
        }
    }

    private void saveHistory(List<String> list) {
        StringBuilder sb = new StringBuilder(list.size() * 32);
        for (String item : list) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(item);
        }
        prefs.edit().putString(KEY_ROOT_HISTORY, sb.toString()).apply();
    }

    public int seekStep() {
        int value = prefs.getInt(KEY_SEEK_STEP, DEFAULT_SEEK_STEP);
        return TimeFormat.clamp(value, MIN_SEEK_STEP, MAX_SEEK_STEP);
    }

    public void setSeekStep(int seconds) {
        prefs.edit()
                .putInt(KEY_SEEK_STEP, TimeFormat.clamp(seconds, MIN_SEEK_STEP, MAX_SEEK_STEP))
                .apply();
    }

    public boolean deleteWithoutConfirm() {
        return prefs.getBoolean(KEY_NO_CONFIRM, false);
    }

    public void setDeleteWithoutConfirm(boolean value) {
        prefs.edit().putBoolean(KEY_NO_CONFIRM, value).apply();
    }

    /**
     * When enabled, the hardware volume keys seek (by the configured step)
     * while the screen is off; holding a key still changes the volume.
     */
    public boolean volumeKeysSeek() {
        return prefs.getBoolean(KEY_VOLUME_SEEK, false);
    }

    public void setVolumeKeysSeek(boolean value) {
        prefs.edit().putBoolean(KEY_VOLUME_SEEK, value).apply();
    }

    /**
     * Scroll the unified file list one whole row per vertical swipe, with no
     * pixel scrolling or fling. The circular queue can be browsed in either
     * direction without reaching a hard end.
     */
    public boolean discreteFileScroll() {
        return prefs.getBoolean(KEY_DISCRETE_FILE_SCROLL, false);
    }

    public void setDiscreteFileScroll(boolean value) {
        prefs.edit().putBoolean(KEY_DISCRETE_FILE_SCROLL, value).apply();
    }

    public boolean keepScreenOn() {
        return prefs.getBoolean(KEY_KEEP_AWAKE, false);
    }

    public void setKeepScreenOn(boolean value) {
        prefs.edit().putBoolean(KEY_KEEP_AWAKE, value).apply();
    }

    /**
     * Speed of the fast play button in percent (100 = x1.0), always on the
     * x0.1 grid and inside x1.1 … x2.0, so a stale stored value can never
     * produce a silly speed.
     */
    public int fastSpeedPercent() {
        return PlaybackSpeed.snap(prefs.getInt(KEY_FAST_SPEED, DEFAULT_FAST_SPEED));
    }

    public void setFastSpeedPercent(int percent) {
        prefs.edit().putInt(KEY_FAST_SPEED, PlaybackSpeed.snap(percent)).apply();
    }

    /** Slider position (0-based) of the configured fast speed. */
    public int fastSpeedStep() {
        return PlaybackSpeed.toStep(fastSpeedPercent());
    }

    public String lastFile() {
        return prefs.getString(KEY_LAST_FILE, null);
    }

    public void setLastFile(String path) {
        prefs.edit().putString(KEY_LAST_FILE, path).apply();
    }

    public int lastMode() {
        return prefs.getInt(KEY_LAST_MODE, MODE_UNIFIED);
    }

    public void setLastMode(int mode) {
        prefs.edit().putInt(KEY_LAST_MODE, mode).apply();
    }

    public String lastFolder() {
        return prefs.getString(KEY_LAST_FOLDER, null);
    }

    public void setLastFolder(String path) {
        prefs.edit().putString(KEY_LAST_FOLDER, path).apply();
    }

    public int lastPage() {
        return prefs.getInt(KEY_LAST_PAGE, 0);
    }

    public void setLastPage(int page) {
        prefs.edit().putInt(KEY_LAST_PAGE, page).apply();
    }
}

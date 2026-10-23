package io.github.yuki2ka.audiobookplayer.util;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.File;

/**
 * All storage-permission logic in one place.
 *
 * <ul>
 *   <li>Android 7 - 9: {@code READ/WRITE_EXTERNAL_STORAGE} runtime permissions
 *       (write access is needed to delete files).</li>
 *   <li>Android 10: legacy external storage via
 *       {@code requestLegacyExternalStorage} in the manifest, plus write access
 *       for deletions.</li>
 *   <li>Android 11+: "All files access"
 *       ({@code MANAGE_EXTERNAL_STORAGE}) is required to browse any folder and
 *       to delete files. If the user refuses, {@code READ_MEDIA_AUDIO}
 *       (Android 13+) or {@code READ_EXTERNAL_STORAGE} still allows reading
 *       media files, so playback works and only deletion is limited.</li>
 *   <li>App-specific external folders never need a permission. This is the
 *       documented fallback for users who do not want to grant broad access.</li>
 * </ul>
 */
public final class Storage {

    /** No access at all. */
    public static final int ACCESS_NONE = 0;
    /** Files can be listed and played, deleting may fail. */
    public static final int ACCESS_READ = 1;
    /** Full access, including deleting audio files. */
    public static final int ACCESS_FULL = 2;

    private static final String[] COMMON_FOLDER_NAMES = {
            "Audiobooks", "Audiobook", "AudioBooks", "audiobooks", "Books", "Hörbücher", "Audiolibros"
    };

    private Storage() {
    }

    public static String appFolder(Context context) {
        File external = context.getExternalFilesDir(null);
        File folder = external != null
                ? new File(external, "Audiobooks")
                : new File(context.getFilesDir(), "Audiobooks");
        //noinspection ResultOfMethodCallIgnored
        folder.mkdirs();
        return folder.getAbsolutePath();
    }

    /** First existing "books" folder in shared storage, otherwise the app folder. */
    public static String defaultRoot(Context context) {
        File base = Environment.getExternalStorageDirectory();
        if (base != null) {
            for (String name : COMMON_FOLDER_NAMES) {
                File candidate = new File(base, name);
                if (candidate.isDirectory() && candidate.canRead()) {
                    return candidate.getAbsolutePath();
                }
            }
        }
        return appFolder(context);
    }

    public static int accessLevel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                return ACCESS_FULL;
            }
            if (Build.VERSION.SDK_INT >= 33) {
                return granted(context, Manifest.permission.READ_MEDIA_AUDIO) ? ACCESS_READ : ACCESS_NONE;
            }
            return granted(context, Manifest.permission.READ_EXTERNAL_STORAGE) ? ACCESS_READ : ACCESS_NONE;
        }
        if (!granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)) {
            return ACCESS_NONE;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            return granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ? ACCESS_FULL : ACCESS_READ;
        }
        // Android 10 with legacy external storage: write permission still exists.
        return granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ? ACCESS_FULL : ACCESS_READ;
    }

    public static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** True when {@code path} lives in an app-specific directory (no permission needed). */
    public static boolean isAppFolder(Context context, String path) {
        if (path == null) {
            return false;
        }
        File external = context.getExternalFilesDir(null);
        if (external != null && isInside(path, external.getAbsolutePath())) {
            return true;
        }
        return isInside(path, context.getFilesDir().getAbsolutePath());
    }

    private static boolean isInside(String path, String parent) {
        return path.equals(parent) || path.startsWith(parent + File.separator);
    }

    /** Whether the library can be scanned without showing the permission card. */
    public static boolean canRead(Context context, String path) {
        return accessLevel(context) != ACCESS_NONE || isAppFolder(context, path);
    }

    /**
     * Requests the runtime read permission for this Android version.
     * On Android 7 - 9 this also grants write access and therefore full access.
     */
    public static void requestReadAccess(Activity activity, int requestCode) {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= 33) {
            permissions = new String[]{Manifest.permission.READ_MEDIA_AUDIO};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            permissions = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        } else {
            permissions = new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
        }
        activity.requestPermissions(permissions, requestCode);
    }

    /**
     * Opens the "All files access" system screen (Android 11+). The user has to
     * toggle the switch manually, which is why the app re-checks on resume.
     */
    public static void openAllFilesSettings(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                activity.startActivityForResult(
                        new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), requestCode);
            } catch (ActivityNotFoundException ignored) {
                // Nothing else we can do; the settings screen explains the state.
            }
        }
    }
}

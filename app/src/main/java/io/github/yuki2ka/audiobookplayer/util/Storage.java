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

    /** Where people keep audiobooks: preferred default and quick-pick folder. */
    public static final String BOOKS = "Books";
    private static final String MUSIC = "Music";
    private static final String DOWNLOADS = "Downloads";


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

    /**
     * Default library folder: always {@code <shared storage>/Books} (the
     * canonical place for audiobooks), even when it does not exist yet, so the
     * empty state can tell the user exactly where to put files. Settings offers
     * one-tap switches to Music / Downloads / other recent folders.
     */
    public static String defaultRoot(Context context) {
        String books = sharedFolder(context, BOOKS);
        return books != null ? books : appFolder(context);
    }

    /** Absolute path of a standard shared-storage folder ({@code Music}, ...). */
    public static String sharedFolder(Context context, String name) {
        File base = Environment.getExternalStorageDirectory();
        if (base == null) {
            return null;
        }
        return new File(base, name).getAbsolutePath();
    }

    public static String musicFolder(Context context) {
        return sharedFolder(context, MUSIC);
    }

    /** Root of the removable SD card, or {@code null} when there is none. */
    public static String removableRoot(Context context) {
        File[] dirs = context.getExternalFilesDirs(null);
        if (dirs != null) {
            for (int i = 1; i < dirs.length; i++) {
                // /storage/XXXX-XXXX/Android/data/<package>/files -> volume root
                if (dirs[i] == null) {
                    continue;
                }
                String path = dirs[i].getAbsolutePath();
                int marker = path.indexOf("/Android/");
                if (marker > 0) {
                    return path.substring(0, marker);
                }
            }
        }
        File storage = new File("/storage");
        File[] children = storage.listFiles();
        if (children != null) {
            for (File child : children) {
                String name = child.getName();
                if (!"emulated".equals(name) && !"self".equals(name) && child.isDirectory()) {
                    return child.getAbsolutePath();
                }
            }
        }
        return null;
    }

    /** Creates the folder when possible; returns true when it exists afterwards. */
    public static boolean ensureFolder(String path) {
        if (path == null) {
            return false;
        }
        File folder = new File(path);
        if (folder.isDirectory()) {
            return true;
        }
        try {
            return folder.mkdirs() || folder.isDirectory();
        } catch (SecurityException e) {
            return false;
        }
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

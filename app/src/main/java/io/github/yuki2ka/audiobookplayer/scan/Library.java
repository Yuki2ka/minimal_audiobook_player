package io.github.yuki2ka.audiobookplayer.scan;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import io.github.yuki2ka.audiobookplayer.data.Db;
import io.github.yuki2ka.audiobookplayer.model.AudioFile;
import io.github.yuki2ka.audiobookplayer.model.FolderNode;
import io.github.yuki2ka.audiobookplayer.model.ScanResult;
import io.github.yuki2ka.audiobookplayer.playback.PlayQueue;

/**
 * Application-scoped library: owns the last scan result, the rotating unified
 * queue and the path-to-position map used by the adapters.
 *
 * <p>All public methods are called from the main thread; scanning happens on a
 * single background thread and posts its result back.</p>
 */
public final class Library {

    public interface Listener {
        void onScanStarted();

        void onScanProgress(int files);

        /** A new scan result is available. */
        void onLibraryChanged();

        void onScanFinished(int files, String error, boolean userInitiated);
    }

    private final Db db;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor;
    private final PlayQueue queue = new PlayQueue();
    private final HashMap<String, Long> positions = new HashMap<>();

    private Listener listener;
    private ScanResult result;
    private boolean scanning;
    private Future<?> job;
    private int generation;
    private String rootPath;

    public Library(Context context, Db db) {
        this.db = db;
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "library-scan");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isScanning() {
        return scanning;
    }

    public ScanResult result() {
        return result;
    }

    public String rootPath() {
        return result != null ? result.root : rootPath;
    }

    public List<AudioFile> files() {
        return queue.items();
    }

    public PlayQueue queue() {
        return queue;
    }

    public FolderNode node(String path) {
        return result == null || path == null ? null : result.nodes.get(path);
    }

    public FolderNode rootNode() {
        return result == null ? null : result.rootNode;
    }

    public AudioFile find(String path) {
        return queue.find(path);
    }

    public HashMap<String, Long> positions() {
        return positions;
    }

    public void putPosition(String path, long positionMs) {
        if (path != null) {
            positions.put(path, positionMs);
        }
    }

    public void removePosition(String path) {
        if (path != null) {
            positions.remove(path);
        }
    }

    public void clearPositions() {
        positions.clear();
    }

    /**
     * Starts a scan. A running scan is cancelled (its result is discarded).
     *
     * @param anchorPath file that should be first in the unified list after the
     *                   scan (usually the current or last played file)
     */
    public void scan(String root, String anchorPath, boolean userInitiated) {
        this.rootPath = root;
        final int generation = ++this.generation;
        final boolean fromUser = userInitiated;
        scanning = true;
        if (job != null) {
            job.cancel(true);
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                if (generation == Library.this.generation && listener != null) {
                    listener.onScanStarted();
                }
            }
        });

        final Scanner.Progress progress = new Scanner.Progress() {
            @Override
            public void onProgress(final int files) {
                if (generation != Library.this.generation) {
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (generation == Library.this.generation && listener != null) {
                            listener.onScanProgress(files);
                        }
                    }
                });
            }
        };

        job = executor.submit(new Runnable() {
            @Override
            public void run() {
                final ScanResult scanResult = Scanner.scan(root, progress);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        apply(generation, scanResult, anchorPath, fromUser);
                    }
                });
            }
        });
    }

    private void apply(int generation, ScanResult scanResult, String anchorPath, boolean fromUser) {
        if (generation != this.generation) {
            return;
        }
        scanning = false;
        result = scanResult;
        queue.reset(scanResult.allFiles, anchorPath);
        positions.clear();
        positions.putAll(db.loadPositions());
        if (listener != null) {
            listener.onLibraryChanged();
            listener.onScanFinished(scanResult.count(), scanResult.error, fromUser);
        }
    }

    /** Rebuilds the queue order after files were deleted, without a rescan. */
    public void rebuildQueue(String anchorPath) {
        if (result == null) {
            return;
        }
        queue.reset(result.allFiles, anchorPath);
        positions.clear();
        positions.putAll(db.loadPositions());
    }

    /** Removes a deleted file from the in-memory scan result and the queue. */
    public void removeFile(AudioFile file) {
        if (file == null || result == null) {
            return;
        }
        result.allFiles.remove(file);
        FolderNode parent = result.nodes.get(file.parentPath());
        if (parent != null) {
            parent.files.remove(file);
            FolderNode node = parent;
            while (node != null) {
                node.totalFiles = Math.max(0, node.totalFiles - 1);
                node = result.nodes.get(node.parentPath());
            }
        }
        queue.items().remove(file);
        positions.remove(file.path);
    }
}

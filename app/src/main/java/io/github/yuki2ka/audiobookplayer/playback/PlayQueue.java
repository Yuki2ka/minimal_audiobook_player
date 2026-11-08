package io.github.yuki2ka.audiobookplayer.playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.yuki2ka.audiobookplayer.model.AudioFile;
import io.github.yuki2ka.audiobookplayer.model.FolderNode;
import io.github.yuki2ka.audiobookplayer.model.QueueEntry;
import io.github.yuki2ka.audiobookplayer.model.TreeOrder;

/**
 * The unified ("All files") list is a rotating queue of the folder tree.
 *
 * <p>The queue holds <em>one</em> ordered list of {@link QueueEntry items}:
 * audio files and the folder separators between them. Rotating therefore moves
 * both kinds of row in the same way — a folder's opening line travels with the
 * files below it, and a folder's closing line travels with the files above it.
 * Nothing is sorted or regrouped after the scan; the order is always a rotation
 * of the tree walk produced by {@link TreeOrder}.</p>
 *
 * <p>Rotation rules:</p>
 * <ul>
 *   <li>the file that is playing is the first <em>file</em>; the separator that
 *       names the folder it is in stays directly above it (the same rule the
 *       list had before separators became rows of their own);</li>
 *   <li>when a file finishes, its row moves to the bottom and the following file
 *       rises to the top ({@link #advanceFrom(String)});</li>
 *   <li>"previous" moves back one file, wrapping to the end
 *       ({@link #retreatFrom(String)}), so the queue behaves like a ring;</li>
 *   <li>discrete list browsing can rotate the visible head in either direction
 *       without changing the track that is playing.</li>
 * </ul>
 *
 * <p>Because the list is a ring, a folder can straddle the wrap-around point:
 * its opening line then appears above its first file and its closing line above
 * the wrap, which is why both lines exist.</p>
 *
 * <p>Pure Java (no Android imports) so the rotation rules are covered by the
 * desktop unit tests.</p>
 */
public final class PlayQueue {

    private final ArrayList<QueueEntry> items = new ArrayList<>();
    private String anchor;

    /** Builds the queue from a scanned folder tree, rotated to {@code anchorPath}. */
    public void reset(FolderNode root, String anchorPath) {
        resetEntries(TreeOrder.entries(root), anchorPath);
    }

    /**
     * Rebuilds the queue from an already flattened tree (see
     * {@link TreeOrder#entries}) and rotates it so that {@code anchorPath} — the
     * last or current file — is the first file again.
     */
    public void resetEntries(List<QueueEntry> orderedEntries, String anchorPath) {
        items.clear();
        if (orderedEntries != null) {
            items.addAll(orderedEntries);
        }
        rotateTo(anchorPath);
        anchor = firstFilePath();
    }

    /** Live list of files and separators; the adapter reads it directly. */
    public List<QueueEntry> entries() {
        return items;
    }

    /** Only the audio files, in queue order. */
    public List<AudioFile> files() {
        return new ArrayList<>(filesIn());
    }

    /** Reusable variant of {@link #files()} for callers that avoid an allocation. */
    public void copyFiles(List<AudioFile> out) {
        if (out == null) {
            return;
        }
        out.clear();
        for (int i = 0, n = items.size(); i < n; i++) {
            QueueEntry entry = items.get(i);
            if (entry.isFile() && entry.file != null) {
                out.add(entry.file);
            }
        }
    }

    public String anchor() {
        return anchor;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** Number of rows, separators included (the adapter's row count). */
    public int size() {
        return items.size();
    }

    /** Number of audio files (separators are not counted). */
    public int fileCount() {
        int count = 0;
        for (int i = 0, n = items.size(); i < n; i++) {
            if (items.get(i).isFile()) {
                count++;
            }
        }
        return count;
    }

    /** Queue index of a file row, or -1. */
    public int indexOf(String path) {
        if (path == null) {
            return -1;
        }
        for (int i = 0, n = items.size(); i < n; i++) {
            QueueEntry entry = items.get(i);
            if (entry.isFile() && entry.file != null && path.equals(entry.file.path)) {
                return i;
            }
        }
        return -1;
    }

    public AudioFile find(String path) {
        int index = indexOf(path);
        return index < 0 ? null : items.get(index).file;
    }

    /** Queue index of the first audio file, or -1. */
    public int firstFileIndex() {
        for (int i = 0, n = items.size(); i < n; i++) {
            if (items.get(i).isFile()) {
                return i;
            }
        }
        return -1;
    }

    /** The first audio file (the one at the top of the list), or {@code null}. */
    public AudioFile firstFile() {
        int index = firstFileIndex();
        return index < 0 ? null : items.get(index).file;
    }

    /**
     * Rotates the queue so that the file at {@code index} (or the first file
     * below a separator row) is the first audio row. Whole rows move, so the
     * separators in front of the file stay in front of it.
     */
    public void promoteToFront(int index) {
        if (index < 0 || index >= items.size()) {
            return;
        }
        int file = items.get(index).isFile() ? index : nextFileIndex(index);
        if (file < 0) {
            return;
        }
        rotateTo(items.get(file).file.path);
    }

    /**
     * Rotates the queue so {@code path} is the first audio row of the list. The
     * separator that names its folder stays directly above it; everything listed
     * before that separator moves to the bottom in the same order.
     */
    public void rotateTo(String path) {
        int index = indexOf(path);
        if (index < 0) {
            return;
        }
        int shift = leadStart(index);
        if (shift > 0) {
            Collections.rotate(items, -shift);
        }
        anchor = firstFilePath();
    }

    /**
     * The current file finished: move it towards the bottom and return the next
     * file, or {@code null} when there is only one file in the queue (the
     * finished track then stays loaded so Play restarts it).
     */
    public AudioFile advanceFrom(String currentPath) {
        rotateTo(currentPath);
        if (fileCount() < 2) {
            return null;
        }
        int next = nextFileIndex(firstFileIndex() + 1);
        if (next < 0) {
            next = firstFileIndex();
        }
        AudioFile file = items.get(next).file;
        rotateTo(file.path);
        return firstFile();
    }

    /** "Previous file": step back one file, wrapping to the end of the queue. */
    public AudioFile retreatFrom(String currentPath) {
        rotateTo(currentPath);
        if (fileCount() < 2) {
            return null;
        }
        int previous = previousFileIndex(firstFileIndex() - 1);
        if (previous < 0) {
            previous = lastFileIndex();
        }
        AudioFile file = items.get(previous).file;
        rotateTo(file.path);
        return firstFile();
    }

    /**
     * Discrete list swipe toward later files: move the first row to the end.
     * This is a view rotation only; playback keeps its current track.
     */
    public void rotateViewForward() {
        if (!canRotate()) {
            return;
        }
        items.add(items.remove(0));
        anchor = firstFilePath();
    }

    /**
     * Discrete list swipe toward earlier files: insert the last row at the
     * beginning. This lets the circular list move backward without a hard end.
     */
    public void rotateViewBackward() {
        if (!canRotate()) {
            return;
        }
        items.add(0, items.remove(items.size() - 1));
        anchor = firstFilePath();
    }

    /**
     * Start of the block that must lead {@code index}: the separators that name
     * the file's folder and its parent folders, when they sit directly in front
     * of it. That is the case for the first file of a folder, which is the only
     * row that can lead a rotation without pushing the file itself off the top:
     * every other file becomes the head of the list, with its folder's closing
     * line right below it (the folder name reappears when the ring wraps).
     */
    private int leadStart(int index) {
        int start = index;
        for (int before = index - 1; before >= 0; before--) {
            QueueEntry entry = items.get(before);
            if (entry.isFolder() && !entry.isFolderEnd()) {
                start = before;
            } else {
                break;
            }
        }
        return start;
    }

    private int nextFileIndex(int from) {
        for (int i = Math.max(0, from), n = items.size(); i < n; i++) {
            if (items.get(i).isFile()) {
                return i;
            }
        }
        return -1;
    }

    private int previousFileIndex(int from) {
        for (int i = from; i >= 0; i--) {
            if (items.get(i).isFile()) {
                return i;
            }
        }
        return -1;
    }

    private int lastFileIndex() {
        return previousFileIndex(items.size() - 1);
    }

    private List<AudioFile> filesIn() {
        ArrayList<AudioFile> files = new ArrayList<>(items.size());
        copyFiles(files);
        return files;
    }

    private String firstFilePath() {
        AudioFile file = firstFile();
        return file == null ? null : file.path;
    }

    /**
     * Rotation is only meaningful when at least two folders can trade places;
     * below that, single-row rotations would just move a folder's lines away
     * from its files.
     */
    private boolean canRotate() {
        return items.size() >= 4;
    }
}

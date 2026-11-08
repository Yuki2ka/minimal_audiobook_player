package io.github.yuki2ka.audiobookplayer.model;

/**
 * One element of the unified ("All files") queue order: either an audio file or
 * a folder separator.
 *
 * <p>The order is a depth-first walk of the scanned folder tree — a folder opens
 * with a separator that names it, then its sub-folders follow, then its own
 * files, and a separator without a name closes it. Because files and separators
 * sit in the <em>same</em> list, rotating the queue (a tap on a row, the next
 * track, a discrete browse swipe) moves whole blocks and never splits a folder
 * from its opening or closing line.</p>
 *
 * <p>Pure Java and immutable, so the order is covered by the desktop tests.</p>
 */
public final class QueueEntry {

    public static final int TYPE_FILE = 0;
    public static final int TYPE_FOLDER = 1;

    /** {@link #TYPE_FILE} or {@link #TYPE_FOLDER}. */
    public final int type;
    /** The audio file for {@link #TYPE_FILE}, otherwise {@code null}. */
    public final AudioFile file;
    /** Folder path for {@link #TYPE_FOLDER}, otherwise {@code null}. */
    public final String path;
    /** Name shown in the separator; {@code null} closes a folder (just a line). */
    public final String name;
    /** Nesting level: 0 for the first level inside the library root, 1 for its children… */
    public final int depth;

    private QueueEntry(int type, AudioFile file, String path, String name, int depth) {
        this.type = type;
        this.file = file;
        this.path = path;
        this.name = name;
        this.depth = depth;
    }

    public static QueueEntry file(AudioFile file, int depth) {
        return new QueueEntry(TYPE_FILE, file, null, null, depth);
    }

    /** Opens a folder: the row shows its name. */
    public static QueueEntry folderStart(String path, String name, int depth) {
        return new QueueEntry(TYPE_FOLDER, null, path, name, depth);
    }

    /** Closes a folder: the row is a bare line, no name. */
    public static QueueEntry folderEnd(String path, int depth) {
        return new QueueEntry(TYPE_FOLDER, null, path, null, depth);
    }

    public boolean isFile() {
        return type == TYPE_FILE;
    }

    public boolean isFolder() {
        return type == TYPE_FOLDER;
    }

    /** Whether this separator closes its folder instead of opening it. */
    public boolean isFolderEnd() {
        return type == TYPE_FOLDER && name == null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof QueueEntry)) {
            return false;
        }
        QueueEntry entry = (QueueEntry) other;
        if (type != entry.type || depth != entry.depth) {
            return false;
        }
        if (type == TYPE_FILE) {
            return file == null ? entry.file == null : file.equals(entry.file);
        }
        boolean sameName = name == null ? entry.name == null : name.equals(entry.name);
        return sameName && (path == null ? entry.path == null : path.equals(entry.path));
    }

    @Override
    public int hashCode() {
        int result = type * 31 + depth;
        if (type == TYPE_FILE) {
            return result * 31 + (file == null ? 0 : file.hashCode());
        }
        result = result * 31 + (path == null ? 0 : path.hashCode());
        return result * 31 + (name == null ? 0 : name.hashCode());
    }

    @Override
    public String toString() {
        if (type == TYPE_FILE) {
            return "file(" + (file == null ? "null" : file.name) + ", depth " + depth + ")";
        }
        return (name == null ? "folderEnd(" : "folderStart(") + path + ", depth " + depth + ")";
    }
}

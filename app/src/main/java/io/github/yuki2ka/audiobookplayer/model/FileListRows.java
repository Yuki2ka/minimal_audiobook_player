package io.github.yuki2ka.audiobookplayer.model;

import java.util.List;

/**
 * Presentation rows for the unified ("All files") list.
 *
 * <p>The rows are the queue itself: every {@link QueueEntry} — audio file,
 * opening separator or closing separator — is exactly one row. Nothing is
 * grouped or re-ordered here, so the visible list is always a rotation of the
 * tree order and a folder's opening line, its files and its closing line travel
 * together when the list rotates.</p>
 *
 * <p>Deliberately free of Android imports so the mapping is covered by the
 * desktop tests.</p>
 */
public final class FileListRows {

    public static final int TYPE_FOLDER = QueueEntry.TYPE_FOLDER;
    public static final int TYPE_FILE = QueueEntry.TYPE_FILE;

    private List<QueueEntry> entries;

    /** Points the rows at the live queue list (no copy, no regrouping). */
    public void rebuild(List<QueueEntry> orderedEntries) {
        this.entries = orderedEntries;
    }

    /** Number of adapter rows. */
    public int size() {
        return entries == null ? 0 : entries.size();
    }

    public int typeAt(int row) {
        QueueEntry entry = entryAt(row);
        return entry == null ? TYPE_FILE : entry.type;
    }

    /** Returns the audio file, or {@code null} for a separator row. */
    public AudioFile fileAt(int row) {
        QueueEntry entry = entryAt(row);
        return entry == null ? null : entry.file;
    }

    /** Name of an opening separator; {@code null} for a closing line or a file. */
    public String folderNameAt(int row) {
        QueueEntry entry = entryAt(row);
        return entry == null ? null : entry.name;
    }

    /** Whether this separator closes a folder (a bare line, no name). */
    public boolean isFolderEnd(int row) {
        QueueEntry entry = entryAt(row);
        return entry != null && entry.isFolderEnd();
    }

    /** Nesting depth of a separator row (0 = top level inside the library root). */
    public int depthAt(int row) {
        QueueEntry entry = entryAt(row);
        return entry == null ? 0 : entry.depth;
    }

    /**
     * Queue index shown at (or immediately below) an adapter row. Separator rows
     * map to themselves, which keeps discrete scrolling and the row mapping
     * one-to-one with the queue.
     */
    public int queueIndexAtOrAfter(int row) {
        if (entries == null || entries.isEmpty()) {
            return -1;
        }
        return Math.max(0, Math.min(row, entries.size() - 1));
    }

    private QueueEntry entryAt(int row) {
        if (entries == null || row < 0 || row >= entries.size()) {
            return null;
        }
        return entries.get(row);
    }
}

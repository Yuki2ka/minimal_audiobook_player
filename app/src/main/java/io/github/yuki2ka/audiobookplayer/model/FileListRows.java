package io.github.yuki2ka.audiobookplayer.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Presentation rows for the flat audio queue. Folder names become delimiters
 * between contiguous runs of files instead of being repeated in every file's
 * subtitle. This class deliberately has no Android imports so the grouping and
 * adapter-position mapping can be tested on a desktop JVM.
 */
public final class FileListRows {

    public static final int TYPE_FOLDER = 0;
    public static final int TYPE_FILE = 1;

    private static final class Entry {
        final int type;
        final AudioFile file;
        final String folderName;
        final int fileIndex;
        final boolean groupEnd;

        Entry(int type, AudioFile file, String folderName, int fileIndex, boolean groupEnd) {
            this.type = type;
            this.file = file;
            this.folderName = folderName;
            this.fileIndex = fileIndex;
            this.groupEnd = groupEnd;
        }
    }

    private final ArrayList<Entry> entries = new ArrayList<>();
    private final ArrayList<Integer> fileRows = new ArrayList<>();

    /** Rebuilds the display mapping while leaving the supplied queue untouched. */
    public void rebuild(List<AudioFile> files, boolean showFolderNames) {
        entries.clear();
        fileRows.clear();
        if (files == null) {
            return;
        }

        String previousParent = null;
        for (int i = 0; i < files.size(); i++) {
            AudioFile file = files.get(i);
            String parent = file.parentPath();
            boolean newGroup = showFolderNames && (i == 0 || !parent.equals(previousParent));
            if (newGroup) {
                // The index points at the first file below the delimiter.
                entries.add(new Entry(TYPE_FOLDER, null, file.parentName, i, false));
            }

            boolean groupEnd = showFolderNames && (i + 1 == files.size()
                    || !parent.equals(files.get(i + 1).parentPath()));
            fileRows.add(entries.size());
            entries.add(new Entry(TYPE_FILE, file, null, i, groupEnd));
            previousParent = parent;
        }
    }

    public int size() {
        return entries.size();
    }

    public int typeAt(int row) {
        return entries.get(row).type;
    }

    /** Returns the audio file, or {@code null} for a folder delimiter. */
    public AudioFile fileAt(int row) {
        return entries.get(row).file;
    }

    public String folderNameAt(int row) {
        return entries.get(row).folderName;
    }

    /** Whether this file is the last one before its folder group closes. */
    public boolean isGroupEnd(int row) {
        return entries.get(row).groupEnd;
    }

    /** Adapter row for a zero-based index in the underlying file queue. */
    public int rowForFileIndex(int fileIndex) {
        if (fileIndex < 0 || fileIndex >= fileRows.size()) {
            return -1;
        }
        return fileRows.get(fileIndex);
    }

    /**
     * File index represented at (or immediately below) an adapter row. Folder
     * rows therefore map to their first file, which is useful to discrete
     * scrolling code without making it understand delimiter positions.
     */
    public int fileIndexAtOrAfter(int row) {
        if (entries.isEmpty()) {
            return -1;
        }
        int clamped = Math.max(0, Math.min(row, entries.size() - 1));
        return entries.get(clamped).fileIndex;
    }
}

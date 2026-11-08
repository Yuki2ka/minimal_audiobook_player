package io.github.yuki2ka.audiobookplayer.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the unified ("All files") order as a depth-first walk of the scanned
 * folder tree.
 *
 * <p>For every folder, in this order:</p>
 * <ol>
 *   <li>an <em>opening</em> separator naming the folder,</li>
 *   <li>its sub-folders (same rules, one level deeper),</li>
 *   <li>its own audio files,</li>
 *   <li>a <em>closing</em> separator: a bare line, no name.</li>
 * </ol>
 *
 * <p>The library root itself is not named: it has no separator of its own, so
 * the top level of the list simply starts with the first sub-folder's name.
 * Folders that contain no audio anywhere (no files of their own and no such
 * sub-folders) are skipped, so the list only ever shows lines that lead to a
 * file — including right after the last file of a folder was deleted.
 * Every separator is part of the same ordered list as the files
 * ({@link QueueEntry}), which is what lets the queue be rotated without ever
 * separating a folder from its opening or closing line.</p>
 *
 * <p>Pure Java (no Android imports) so the order is covered by the desktop
 * tests.</p>
 */
public final class TreeOrder {

    private TreeOrder() {
    }

    /**
     * Flattens {@code root} into separators and files, in display order:
     *
     * <pre>
     * Book 1            &lt;- opening separator, depth 0
     *   Book 1/Part A   &lt;- opening separator, depth 1
     *   (a1.mp3, a2.mp3)
     *   ---------------- &lt;- closing separator of Book 1/Part A, depth 1
     * (b1.mp3, b2.mp3)   &lt;- Book 1's own files, depth 0
     * ----------------   &lt;- closing separator of Book 1, depth 0
     * </pre>
     *
     * @return a new mutable list; never {@code null}
     */
    public static ArrayList<QueueEntry> entries(FolderNode root) {
        ArrayList<QueueEntry> entries = new ArrayList<>();
        if (root == null) {
            return entries;
        }
        // The library root is not named: its children are the top level.
        append(root, 0, false, entries);
        return entries;
    }

    /**
     * The same order with the separators removed: the play sequence. It matches
     * the visible order exactly, so "next" always goes to the row below.
     */
    public static ArrayList<AudioFile> files(FolderNode root) {
        ArrayList<QueueEntry> entries = entries(root);
        ArrayList<AudioFile> files = new ArrayList<>(entries.size());
        for (int i = 0, n = entries.size(); i < n; i++) {
            QueueEntry entry = entries.get(i);
            if (entry.isFile() && entry.file != null) {
                files.add(entry.file);
            }
        }
        return files;
    }

    private static void append(FolderNode node, int depth, boolean named,
                               List<QueueEntry> entries) {
        if (node == null) {
            return;
        }
        if (named) {
            entries.add(QueueEntry.folderStart(node.path, node.name, depth));
        }
        int childDepth = named ? depth + 1 : depth;
        for (int i = 0, n = node.folders.size(); i < n; i++) {
            FolderNode child = node.folders.get(i);
            if (child != null && hasAudio(child)) {
                append(child, childDepth, true, entries);
            }
        }
        for (int i = 0, n = node.files.size(); i < n; i++) {
            AudioFile file = node.files.get(i);
            if (file != null) {
                entries.add(QueueEntry.file(file, depth));
            }
        }
        if (named) {
            entries.add(QueueEntry.folderEnd(node.path, depth));
        }
    }

    /** Whether a folder holds audio directly or in any of its sub-folders. */
    static boolean hasAudio(FolderNode node) {
        if (node == null) {
            return false;
        }
        if (!node.files.isEmpty()) {
            return true;
        }
        for (int i = 0, n = node.folders.size(); i < n; i++) {
            if (hasAudio(node.folders.get(i))) {
                return true;
            }
        }
        return false;
    }
}

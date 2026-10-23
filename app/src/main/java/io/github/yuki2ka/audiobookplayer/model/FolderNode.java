package io.github.yuki2ka.audiobookplayer.model;

import java.util.ArrayList;

/**
 * One folder of the library tree. The tree is built once per scan, so browsing
 * sub-folders afterwards is pure memory access: no extra directory I/O when the
 * user taps a folder.
 */
public final class FolderNode {

    public final String path;
    public final String name;
    public final ArrayList<FolderNode> folders = new ArrayList<>();
    /** Audio files directly inside this folder, naturally sorted. */
    public final ArrayList<AudioFile> files = new ArrayList<>();
    /** Recursive number of audio files, filled in by the scanner. */
    public int totalFiles;

    public FolderNode(String path, String name) {
        this.path = path;
        this.name = name;
    }

    public String parentPath() {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : "/";
    }

    public String parentName() {
        int slash = path.lastIndexOf('/');
        if (slash <= 0) {
            return null;
        }
        int previous = path.lastIndexOf('/', slash - 1);
        return path.substring(previous + 1, slash);
    }
}

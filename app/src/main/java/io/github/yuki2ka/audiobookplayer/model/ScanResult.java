package io.github.yuki2ka.audiobookplayer.model;

import java.util.ArrayList;
import java.util.HashMap;

/** Immutable result of one library scan. */
public final class ScanResult {

    public final String root;
    public final FolderNode rootNode;
    /**
     * Every audio file in unified display order: the depth-first folder tree
     * walk (see {@link TreeOrder}), not a flat name sort.
     */
    public final ArrayList<AudioFile> allFiles;
    public final HashMap<String, FolderNode> nodes;
    /** Null when the scan succeeded. */
    public final String error;

    public ScanResult(String root, FolderNode rootNode, ArrayList<AudioFile> allFiles,
                      HashMap<String, FolderNode> nodes, String error) {
        this.root = root;
        this.rootNode = rootNode;
        this.allFiles = allFiles;
        this.nodes = nodes;
        this.error = error;
    }

    public int count() {
        return allFiles.size();
    }
}

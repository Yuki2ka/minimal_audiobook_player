package io.github.yuki2ka.audiobookplayer.scan;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.yuki2ka.audiobookplayer.model.AudioFile;
import io.github.yuki2ka.audiobookplayer.model.FolderNode;
import io.github.yuki2ka.audiobookplayer.model.ScanResult;
import io.github.yuki2ka.audiobookplayer.util.NaturalSort;

/**
 * Recursively scans the user's audiobook folder.
 *
 * <p>Only the configured folder is touched, never the whole file system.
 * Directory listings are the only I/O; no file is opened or decoded, so a large
 * library scans quickly and without touching memory proportional to file
 * contents.</p>
 *
 * <p>Runs on a worker thread and can be cancelled by interrupting the thread
 * (checked between directory entries).</p>
 */
public final class Scanner {

    public interface Progress {
        void onProgress(int files);
    }

    private static final int MAX_DEPTH = 24;
    private static final int PROGRESS_STEP = 250;

    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac",
            "wav", "wave", "mka", "mp4", "3gp", "amr"));

    private static final Comparator<AudioFile> FILE_COMPARATOR = new Comparator<AudioFile>() {
        @Override
        public int compare(AudioFile a, AudioFile b) {
            int result = NaturalSort.compare(a.name, b.name);
            return result != 0 ? result : a.path.compareTo(b.path);
        }
    };

    private static final Comparator<FolderNode> FOLDER_COMPARATOR = new Comparator<FolderNode>() {
        @Override
        public int compare(FolderNode a, FolderNode b) {
            int result = NaturalSort.compare(a.name, b.name);
            return result != 0 ? result : a.path.compareTo(b.path);
        }
    };

    private Scanner() {
    }

    public static ScanResult scan(String rootPath, Progress progress) {
        String rootName = nameOf(rootPath);
        FolderNode rootNode = new FolderNode(rootPath, rootName);
        HashMap<String, FolderNode> nodes = new HashMap<>();
        nodes.put(rootPath, rootNode);
        ArrayList<AudioFile> allFiles = new ArrayList<>();

        File root = new File(rootPath);
        String error = null;
        if (!root.exists()) {
            error = "missing";
        } else if (!root.isDirectory()) {
            error = "not_a_directory";
        } else if (!root.canRead()) {
            error = "unreadable";
        }

        if (error == null) {
            Counter counter = new Counter(progress);
            walk(rootNode, allFiles, nodes, 0, counter);
            countFiles(rootNode);
            sortNode(rootNode);
            Collections.sort(allFiles, FILE_COMPARATOR);
        }

        return new ScanResult(rootPath, rootNode, allFiles, nodes, error);
    }

    private static void walk(FolderNode node, List<AudioFile> allFiles,
                             HashMap<String, FolderNode> nodes, int depth, Counter counter) {
        if (depth > MAX_DEPTH || Thread.currentThread().isInterrupted()) {
            return;
        }
        File directory = new File(node.path);
        File[] children;
        try {
            children = directory.listFiles();
        } catch (SecurityException e) {
            return;
        }
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            String fileName = child.getName();
            if (fileName.isEmpty() || fileName.charAt(0) == '.') {
                continue;
            }
            boolean isDirectory;
            try {
                isDirectory = child.isDirectory();
            } catch (SecurityException e) {
                continue;
            }
            if (isDirectory) {
                FolderNode sub = new FolderNode(child.getAbsolutePath(),
                        NaturalSort.normalize(fileName));
                node.folders.add(sub);
                nodes.put(sub.path, sub);
                walk(sub, allFiles, nodes, depth + 1, counter);
            } else if (isAudio(fileName)) {
                AudioFile file = new AudioFile(
                        child.getAbsolutePath(),
                        AudioFile.displayName(fileName),
                        node.name,
                        child.length(),
                        child.lastModified());
                node.files.add(file);
                allFiles.add(file);
                counter.increment();
            }
        }
    }

    /** Recursive file count per folder, shown in the folder list. */
    private static int countFiles(FolderNode node) {
        int total = node.files.size();
        for (FolderNode child : node.folders) {
            total += countFiles(child);
        }
        node.totalFiles = total;
        return total;
    }

    private static void sortNode(FolderNode node) {
        Collections.sort(node.files, FILE_COMPARATOR);
        Collections.sort(node.folders, FOLDER_COMPARATOR);
        for (FolderNode child : node.folders) {
            sortNode(child);
        }
    }

    private static boolean isAudio(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return AUDIO_EXTENSIONS.contains(extension);
    }

    private static String nameOf(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return NaturalSort.normalize(name);
    }

    private static final class Counter {
        private final Progress progress;
        private int count;

        Counter(Progress progress) {
            this.progress = progress;
        }

        void increment() {
            count++;
            if (progress != null && count % PROGRESS_STEP == 0) {
                progress.onProgress(count);
            }
        }
    }
}

package io.github.yuki2ka.audiobookplayer.model;

import io.github.yuki2ka.audiobookplayer.util.NaturalSort;

/**
 * Immutable description of one audio file. Only the fields that the UI needs
 * are kept: a full {@code java.io.File} per item would waste memory on long
 * lists (a 5 000 file library is easily 1 MB of objects).
 */
public final class AudioFile {

    /** Absolute path, exactly as returned by the scanner. */
    public final String path;
    /** Display name: file name without extension, NFC normalised. */
    public final String name;
    /** Name of the parent folder (last path segment), for the flat list. */
    public final String parentName;
    public final long size;
    public final long modified;

    private String fingerprint;

    public AudioFile(String path, String name, String parentName, long size, long modified) {
        this.path = path;
        this.name = name;
        this.parentName = parentName;
        this.size = size;
        this.modified = modified;
    }

    /** Lazily computed content hash; see {@link io.github.yuki2ka.audiobookplayer.util.Fingerprint}. */
    public String fingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String value) {
        this.fingerprint = value;
    }

    public String parentPath() {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : "/";
    }

    /**
     * Turns a raw file name into the name shown in the list: extension removed
     * and Unicode composed (NFC), so combining marks render correctly.
     */
    public static String displayName(String fileName) {
        String result = fileName;
        int dot = result.lastIndexOf('.');
        if (dot > 0 && dot > result.length() - 6) {
            result = result.substring(0, dot);
        }
        return NaturalSort.normalize(result);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AudioFile)) {
            return false;
        }
        return path.equals(((AudioFile) other).path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}

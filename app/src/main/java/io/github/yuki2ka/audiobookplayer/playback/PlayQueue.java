package io.github.yuki2ka.audiobookplayer.playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.yuki2ka.audiobookplayer.model.AudioFile;

/**
 * The unified ("All files") list is a rotating queue:
 *
 * <ul>
 *   <li>the file that is playing is always the first item;</li>
 *   <li>when a file finishes, it moves to the bottom and the next one rises
 *       to the top ({@link #advanceFrom(String)});</li>
 *   <li>"previous" moves the last item to the top
 *       ({@link #retreatFrom(String)}), so the queue behaves like a ring.</li>
 * </ul>
 *
 * <p>This is a pure-Java class (no Android imports) so the rotation rules are
 * covered by desktop unit tests.</p>
 */
public final class PlayQueue {

    private final ArrayList<AudioFile> items = new ArrayList<>();
    private String anchor;

    /**
     * Rebuilds the queue from a naturally sorted list and rotates it so that
     * {@code anchorPath} (the last played file) is first again.
     */
    public void reset(List<AudioFile> sorted, String anchorPath) {
        items.clear();
        items.addAll(sorted);
        rotateToInternal(anchorPath);
        anchor = items.isEmpty() ? null : items.get(0).path;
    }

    /** Live list; the adapter reads it directly and re-renders on change. */
    public List<AudioFile> items() {
        return items;
    }

    public String anchor() {
        return anchor;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    public int indexOf(String path) {
        if (path == null) {
            return -1;
        }
        for (int i = 0, n = items.size(); i < n; i++) {
            if (path.equals(items.get(i).path)) {
                return i;
            }
        }
        return -1;
    }

    public AudioFile find(String path) {
        int index = indexOf(path);
        return index < 0 ? null : items.get(index);
    }

    /** Moves the item at {@code index} to the top, keeping the rest in order. */
    public void promoteToFront(int index) {
        if (index < 0 || index >= items.size()) {
            return;
        }
        if (index > 0) {
            Collections.rotate(items, -index);
        }
        anchor = items.get(0).path;
    }

    public void rotateTo(String path) {
        rotateToInternal(path);
        if (path != null && !items.isEmpty() && path.equals(items.get(0).path)) {
            anchor = path;
        }
    }

    /**
     * The current file finished: move it to the bottom and return the new
     * first item, or {@code null} when there is no other file to play.
     */
    public AudioFile advanceFrom(String currentPath) {
        rotateToInternal(currentPath);
        if (items.size() < 2) {
            return null;
        }
        AudioFile finished = items.remove(0);
        items.add(finished);
        anchor = items.get(0).path;
        return items.get(0);
    }

    /** "Previous file": bring the last item to the top and return it. */
    public AudioFile retreatFrom(String currentPath) {
        rotateToInternal(currentPath);
        if (items.size() < 2) {
            return null;
        }
        AudioFile last = items.remove(items.size() - 1);
        items.add(0, last);
        anchor = last.path;
        return last;
    }

    private void rotateToInternal(String path) {
        int index = indexOf(path);
        if (index > 0) {
            // Rotate the whole list so the item comes first and everything that
            // was listed before it moves to the bottom, keeping the listening
            // order intact (c, d, a, b for a tap on c out of a, b, c, d).
            Collections.rotate(items, -index);
        }
    }
}

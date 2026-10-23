package io.github.yuki2ka.audiobookplayer.ui;

import android.app.Activity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;

import io.github.yuki2ka.audiobookplayer.R;
import io.github.yuki2ka.audiobookplayer.data.Db;
import io.github.yuki2ka.audiobookplayer.model.AudioFile;
import io.github.yuki2ka.audiobookplayer.model.FolderNode;
import io.github.yuki2ka.audiobookplayer.scan.Library;

/**
 * Controller for the folder explorer page: keeps the current folder, the
 * adapter, the scroll restoration and the "last played file per folder" state.
 */
public final class FolderBrowser {

    public interface Host {
        /** Called whenever a folder is opened, so the app can remember it. */
        void onFolderOpened(String path);
    }

    private final Activity activity;
    private final ListView list;
    private final TextView pathView;
    private final ImageButton upButton;
    private final View emptyView;
    private final FolderAdapter adapter;
    private final Library library;
    private final Db db;

    private Host host;
    private String currentPath;
    private String lastPlayedPath;
    private String playingPath;

    public FolderBrowser(Activity activity, Library library, Db db,
                         ListView list, TextView pathView, ImageButton upButton,
                         View emptyView, FolderAdapter.Callbacks callbacks) {
        this.activity = activity;
        this.library = library;
        this.db = db;
        this.list = list;
        this.pathView = pathView;
        this.upButton = upButton;
        this.emptyView = emptyView;
        this.adapter = new FolderAdapter(activity, callbacks, true);
        this.adapter.setPositions(library.positions());
        list.setAdapter(adapter);
        list.setEmptyView(emptyView);
        upButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                up();
            }
        });
    }

    public void setHost(Host host) {
        this.host = host;
    }

    public String currentPath() {
        return currentPath;
    }

    public FolderNode currentNode() {
        return currentPath == null ? null : library.node(currentPath);
    }

    public boolean canGoUp() {
        String root = library.rootPath();
        return currentPath != null && root != null && !currentPath.equals(root);
    }

    /** Opens the last used folder if it still exists, else the root. */
    public void restore(String preferredFolder) {
        if (library.rootNode() == null) {
            return;
        }
        if (preferredFolder != null && library.node(preferredFolder) != null) {
            show(preferredFolder);
        } else {
            show(library.rootPath());
        }
    }

    public void show(String path) {
        FolderNode node = library.node(path);
        if (node == null) {
            node = library.rootNode();
        }
        if (node == null) {
            return;
        }
        if (currentPath != null && currentPath.equals(node.path)) {
            // Same folder: refresh in place but keep the scroll position.
            adapter.setNode(node, canGoUpFor(node), node.parentName());
            emptyView.setVisibility(adapter.getCount() == 0 ? View.VISIBLE : View.GONE);
            return;
        }
        saveState();
        currentPath = node.path;
        boolean hasParent = !node.path.equals(library.rootPath());
        adapter.setNode(node, hasParent, node.parentName());
        adapter.setPlayingPath(playingPath);
        upButton.setEnabled(hasParent);
        upButton.setAlpha(hasParent ? 1f : 0.35f);
        pathView.setText(labelFor(node));
        emptyView.setVisibility(adapter.getCount() == 0 ? View.VISIBLE : View.GONE);
        restoreScroll(node);
        if (host != null) {
            host.onFolderOpened(node.path);
        }
    }

    private boolean canGoUpFor(FolderNode node) {
        return !node.path.equals(library.rootPath());
    }

    private void restoreScroll(FolderNode node) {
        Db.FolderState state = db.loadFolderState(node.path);
        int target = -1;
        if (state != null) {
            lastPlayedPath = state.lastPath;
            if (state.lastPath != null) {
                target = adapter.positionOfFile(state.lastPath);
            }
            if (target < 0 && state.scroll > 0) {
                target = state.scroll;
            }
        }
        if (playingPath != null) {
            AudioFile playing = library.find(playingPath);
            if (playing != null && node.path.equals(playing.parentPath())) {
                lastPlayedPath = playingPath;
                int index = adapter.positionOfFile(playingPath);
                if (index >= 0) {
                    target = index;
                }
            }
        }
        final int selection = target;
        list.post(new Runnable() {
            @Override
            public void run() {
                list.setSelection(Math.max(0, selection));
            }
        });
    }

    public void up() {
        if (!canGoUp()) {
            return;
        }
        int slash = currentPath.lastIndexOf('/');
        String parent = slash > 0 ? currentPath.substring(0, slash) : null;
        if (parent != null && library.node(parent) != null) {
            show(parent);
        }
    }

    public void setPlaying(String path) {
        playingPath = path;
        adapter.setPlayingPath(path);
        if (path != null) {
            AudioFile file = library.find(path);
            if (file != null && file.parentPath().equals(currentPath)) {
                lastPlayedPath = path;
            }
        }
    }

    /** Called after a scan replaced the tree. */
    public void refresh() {
        if (library.rootNode() == null) {
            return;
        }
        String target = currentPath;
        if (target == null || library.node(target) == null) {
            target = library.rootPath();
            currentPath = null;
        }
        show(target);
    }

    public void refreshPositions() {
        adapter.setPositions(library.positions());
    }

    /** Remembers the last played file and scroll offset of the current folder. */
    public void saveState() {
        if (currentPath == null) {
            return;
        }
        int scroll = list.getFirstVisiblePosition();
        String last = lastPlayedPath;
        if (last == null) {
            Db.FolderState state = db.loadFolderState(currentPath);
            last = state == null ? null : state.lastPath;
        }
        if (last != null) {
            db.saveFolderState(currentPath, last, scroll);
        }
    }

    /** Scrolls to a file of the current folder and marks it as last played. */
    public void revealFile(String path) {
        int index = adapter.positionOfFile(path);
        if (index >= 0) {
            lastPlayedPath = path;
            list.post(new Runnable() {
                @Override
                public void run() {
                    list.setSelection(index);
                }
            });
        }
    }

    private String labelFor(FolderNode node) {
        String root = library.rootPath();
        String rootName = root == null ? "" : new File(root).getName();
        String path = node.path;
        String relative = root != null && path.startsWith(root)
                ? path.substring(Math.min(root.length(), path.length()))
                : path;
        StringBuilder sb = new StringBuilder(rootName);
        int start = 0;
        for (int i = 0; i <= relative.length(); i++) {
            if (i == relative.length() || relative.charAt(i) == '/') {
                if (i > start) {
                    sb.append(" / ").append(relative, start, i);
                }
                start = i + 1;
            }
        }
        return sb.toString();
    }
}

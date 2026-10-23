package io.github.yuki2ka.audiobookplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

import io.github.yuki2ka.audiobookplayer.data.Db;
import io.github.yuki2ka.audiobookplayer.data.Prefs;
import io.github.yuki2ka.audiobookplayer.model.AudioFile;
import io.github.yuki2ka.audiobookplayer.model.FolderNode;
import io.github.yuki2ka.audiobookplayer.playback.PlayerEngine;
import io.github.yuki2ka.audiobookplayer.scan.Library;
import io.github.yuki2ka.audiobookplayer.ui.AudioFileAdapter;
import io.github.yuki2ka.audiobookplayer.ui.FolderAdapter;
import io.github.yuki2ka.audiobookplayer.ui.FolderBrowser;
import io.github.yuki2ka.audiobookplayer.ui.SeekPad;
import io.github.yuki2ka.audiobookplayer.ui.SwipePager;
import io.github.yuki2ka.audiobookplayer.util.Fingerprint;
import io.github.yuki2ka.audiobookplayer.util.Storage;
import io.github.yuki2ka.audiobookplayer.util.TimeFormat;

/**
 * The single screen of the app: three swipeable pages (flat list, folder
 * explorer, settings), the fixed player panel and the permission card.
 */
public final class MainActivity extends Activity implements
        AudioFileAdapter.Callbacks,
        FolderAdapter.Callbacks,
        FolderBrowser.Host,
        Library.Listener,
        PlayerEngine.Listener {

    private static final int PAGE_ALL = 0;
    private static final int PAGE_FOLDERS = 1;
    private static final int PAGE_SETTINGS = 2;

    private static final int RC_PICK_FOLDER = 31;
    private static final int RC_PERMISSION = 32;
    private static final int RC_ALL_FILES = 33;

    private static final int SAVE_EVERY_TICKS = 5;
    private static final int LONG_PRESS_SEEK_MS = 60000;

    private Prefs prefs;
    private Db db;
    private Library library;
    private PlayerEngine engine;

    private SwipePager pager;
    private View pageAll;
    private View pageFolders;
    private View pageSettings;
    private TextView[] tabs;

    private ListView listAll;
    private View emptyAll;
    private TextView emptyTitle;
    private TextView emptyBody;
    private View scanSpinner;
    private Button emptySettings;

    private ListView listFolders;
    private ImageButton folderUp;
    private TextView folderPath;
    private TextView emptyFolders;

    private TextView timePosition;
    private TextView timeTotal;
    private TextView timeRemaining;
    private SeekBar seekBar;
    private SeekPad padBack;
    private SeekPad padForward;
    private Button btnBack;
    private Button btnForward;
    private Button btnPrev;
    private ImageButton btnPlay;
    private Button btnNext;

    private FrameLayout permissionOverlay;
    private TextView permissionBody;
    private Button permissionGrant;
    private TextView permissionHint;

    private TextView settingsRootPath;
    private View settingsRootCard;
    private View settingsAccessBlock;
    private LinearLayout settingsHistory;
    private TextView settingsHistoryLabel;
    private TextView settingsSeekValue;
    private TextView settingsAccessStatus;
    private TextView settingsAccessHint;
    private TextView settingsLibraryInfo;
    private TextView settingsVersion;
    private TextView settingsAbout;
    private Button settingsGrant;
    private SeekBar settingsSeekBar;
    private Switch settingsDeleteSwitch;
    private Switch settingsKeepAwakeSwitch;

    private AudioFileAdapter adapter;
    private FolderBrowser browser;

    private AudioFile current;
    private int queueMode = Prefs.MODE_UNIFIED;
    private List<AudioFile> folderQueue;
    private int folderIndex;
    private int page = PAGE_ALL;
    private boolean pagesReady;
    private int durationMs;
    private boolean prepared;
    private boolean trackingSeek;
    private boolean bindingSettings;
    private int saveTicks;
    private String positionLabel;
    private String totalLabel;
    private String remainingLabel;
    private String historySignature;
    private String lastScanRoot;
    private String defaultRoot;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (current == null || !prepared) {
                return;
            }
            if (!trackingSeek) {
                updateProgressFromEngine();
            }
            if (++saveTicks >= SAVE_EVERY_TICKS) {
                saveTicks = 0;
                saveCurrentPosition(false);
            }
            handler.postDelayed(this, 1000);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        // Resolved once per activity: defaultRoot() stats shared folders and is
        // called from several UI paths.
        defaultRoot = Storage.defaultRoot(this);
        db = App.get().db();
        library = App.get().library();
        engine = App.get().player();

        setContentView(R.layout.activity_main);
        bindViews();
        wireTabs();
        wirePlayer();
        wireSettings();
        wirePermissionCard();

        adapter = new AudioFileAdapter(this, this, true);
        adapter.setPositions(library.positions());
        adapter.setFiles(library.files());
        listAll.setAdapter(adapter);
        listAll.setEmptyView(emptyAll);

        browser = new FolderBrowser(this, library, db, listFolders, folderPath,
                folderUp, emptyFolders, this);
        browser.setHost(this);

        pager.setListener(new SwipePager.Listener() {
            @Override
            public void onSwipe(int direction) {
                showPage(page + direction, true);
            }
        });

        engine.setListener(this);
        library.setListener(this);
        queueMode = prefs.lastMode();
        showPage(TimeFormat.clamp(prefs.lastPage(), PAGE_ALL, PAGE_SETTINGS), false);
        pagesReady = true;

        if (library.result() != null) {
            afterLibraryChanged();
            startTickerIfPlaying();
        } else {
            updateEmptyState();
        }
        if (!hasLibraryAccess()) {
            showPermissionOverlay();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        library.setListener(this);
        engine.setListener(this);
        if (!hasLibraryAccess()) {
            showPermissionOverlay();
            return;
        }
        hidePermissionOverlay();
        if (library.result() == null) {
            startScan(false);
        } else {
            if (!library.rootPath().equals(currentRoot())) {
                startScan(false);
            } else {
                afterLibraryChanged();
            }
        }
        startTickerIfPlaying();
    }

    @Override
    protected void onResume() {
        super.onResume();
        engine.setListener(this);
        if (hasLibraryAccess()) {
            hidePermissionOverlay();
            if (library.result() == null && !library.isScanning()) {
                startScan(false);
            }
        } else if (library.result() == null) {
            showPermissionOverlay();
        }
        refreshSettings();
        applyKeepAwake();
        startTickerIfPlaying();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCurrentPosition(false);
        if (browser != null) {
            browser.saveState();
        }
        prefs.setLastPage(page);
        prefs.setLastMode(queueMode);
    }

    @Override
    protected void onStop() {
        super.onStop();
        library.setListener(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        engine.setListener(null);
        handler.removeCallbacks(ticker);
        if (isFinishing()) {
            saveCurrentPosition(false);
            if (browser != null) {
                browser.saveState();
            }
            engine.stopAndRelease();
        }
    }

    @Override
    public void onBackPressed() {
        if (page == PAGE_SETTINGS) {
            showPage(PAGE_ALL, true);
            return;
        }
        if (page == PAGE_FOLDERS) {
            if (browser.canGoUp()) {
                browser.up();
            } else {
                showPage(PAGE_ALL, true);
            }
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- view setup

    private void bindViews() {
        pager = findViewById(R.id.pages);
        pageAll = findViewById(R.id.page_all);
        pageFolders = findViewById(R.id.page_folders);
        pageSettings = findViewById(R.id.page_settings);
        tabs = new TextView[]{findViewById(R.id.tab_all), findViewById(R.id.tab_folders),
                findViewById(R.id.tab_settings)};

        listAll = findViewById(R.id.list_all);
        emptyAll = findViewById(R.id.empty_all);
        emptyTitle = findViewById(R.id.empty_title);
        emptyBody = findViewById(R.id.empty_body);
        scanSpinner = findViewById(R.id.scan_spinner);
        emptySettings = findViewById(R.id.empty_settings);

        listFolders = findViewById(R.id.list_folders);
        folderUp = findViewById(R.id.folder_up);
        folderPath = findViewById(R.id.folder_path);
        emptyFolders = findViewById(R.id.empty_folders);

        timePosition = findViewById(R.id.time_position);
        timeTotal = findViewById(R.id.time_total);
        timeRemaining = findViewById(R.id.time_remaining);
        seekBar = findViewById(R.id.seek_bar);
        padBack = findViewById(R.id.pad_back);
        padForward = findViewById(R.id.pad_forward);
        btnBack = findViewById(R.id.btn_back);
        btnForward = findViewById(R.id.btn_forward);
        btnPrev = findViewById(R.id.btn_prev);
        btnPlay = findViewById(R.id.btn_play);
        btnNext = findViewById(R.id.btn_next);

        permissionOverlay = findViewById(R.id.permission_overlay);
        permissionBody = findViewById(R.id.permission_body);
        permissionGrant = findViewById(R.id.permission_grant);
        permissionHint = findViewById(R.id.permission_app_folder_hint);

        settingsRootPath = findViewById(R.id.settings_root_path);
        settingsRootCard = findViewById(R.id.settings_root_card);
        settingsAccessBlock = findViewById(R.id.settings_access_block);
        settingsHistory = findViewById(R.id.settings_history);
        settingsHistoryLabel = findViewById(R.id.settings_history_label);
        settingsSeekValue = findViewById(R.id.settings_seek_value);
        settingsAccessStatus = findViewById(R.id.settings_access_status);
        settingsAccessHint = findViewById(R.id.settings_access_hint);
        settingsLibraryInfo = findViewById(R.id.settings_library_info);
        settingsVersion = findViewById(R.id.settings_version);
        settingsAbout = findViewById(R.id.settings_about_text);
        settingsGrant = findViewById(R.id.settings_grant);
        settingsSeekBar = findViewById(R.id.settings_seek_bar);
        settingsDeleteSwitch = findViewById(R.id.settings_delete_switch);
        settingsKeepAwakeSwitch = findViewById(R.id.settings_keep_awake_switch);

        emptySettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(PAGE_SETTINGS, true);
            }
        });
    }

    private void wireTabs() {
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPage(index, true);
                }
            });
        }
    }

    private void showPage(int newPage, boolean animate) {
        int target = TimeFormat.clamp(newPage, PAGE_ALL, PAGE_SETTINGS);
        View[] pages = {pageAll, pageFolders, pageSettings};
        if (pagesReady && target == page) {
            updateTabs();
            return;
        }
        if (animate && pagesReady) {
            View incoming = pages[target];
            incoming.setVisibility(View.VISIBLE);
            incoming.setAlpha(0f);
            float offset = (target > page ? 1f : -1f)
                    * getResources().getDisplayMetrics().widthPixels * 0.05f;
            incoming.setTranslationX(offset);
            incoming.animate().alpha(1f).translationX(0f).setDuration(160).start();
            pages[page].setVisibility(View.GONE);
        } else {
            for (int i = 0; i < pages.length; i++) {
                pages[i].setVisibility(i == target ? View.VISIBLE : View.GONE);
            }
        }
        page = target;
        updateTabs();
        if (target == PAGE_SETTINGS) {
            refreshSettings();
        }
        if (target == PAGE_FOLDERS && !library.isScanning() && library.rootNode() != null
                && browser.currentPath() == null) {
            browser.restore(prefs.lastFolder());
        }
        prefs.setLastPage(target);
    }

    private void updateTabs() {
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setSelected(i == page);
        }
    }

    // ---------------------------------------------------------------- player UI

    private void wirePlayer() {
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPlayPause();
            }
        });
        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playPrevious();
            }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playNext();
            }
        });
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekRelative(-prefs.seekStep() * 1000L);
            }
        });
        btnForward.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekRelative(prefs.seekStep() * 1000L);
            }
        });
        padBack.setListener(new SeekPad.Listener() {
            @Override
            public void onSeek(long deltaMs) {
                seekRelative(deltaMs);
            }
        });
        padForward.setListener(new SeekPad.Listener() {
            @Override
            public void onSeek(long deltaMs) {
                seekRelative(deltaMs);
            }
        });
        btnBack.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                seekRelative(-LONG_PRESS_SEEK_MS);
                return true;
            }
        });
        btnForward.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                seekRelative(LONG_PRESS_SEEK_MS);
                return true;
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    updateTimeLabels(progress, durationMs);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                trackingSeek = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                trackingSeek = false;
                if (current != null) {
                    engine.seekTo(bar.getProgress());
                    updateTimeLabels(bar.getProgress(), durationMs);
                }
            }
        });
        updateStepLabels();
        updateTimeLabels(0, 0);
        updateControls();
    }

    private void updateStepLabels() {
        int step = prefs.seekStep();
        if (btnBack == null) {
            return;
        }
        btnBack.setText(getString(R.string.seek_back_label, step));
        btnForward.setText(getString(R.string.seek_forward_label, step));
        btnBack.setContentDescription(getString(R.string.seek_back, step));
        btnForward.setContentDescription(getString(R.string.seek_forward, step));
    }

    private void onPlayPause() {
        if (current == null) {
            startFirst();
            return;
        }
        if (engine.isPlaying()) {
            engine.pause();
        } else {
            engine.play();
        }
    }

    /** Starts the first item of the visible list (or folder). */
    private void startFirst() {
        if (page == PAGE_FOLDERS || queueMode == Prefs.MODE_FOLDER) {
            FolderNode node = browser.currentNode();
            if (node != null && !node.files.isEmpty()) {
                selectFolderFile(node.files.get(0));
                return;
            }
        }
        List<AudioFile> files = library.files();
        if (files.isEmpty()) {
            return;
        }
        playFile(files.get(0), Prefs.MODE_UNIFIED);
        adapter.notifyDataSetChanged();
    }

    private void seekRelative(long deltaMs) {
        if (current == null || durationMs <= 0) {
            return;
        }
        int target = (int) TimeFormat.clamp(engine.position() + deltaMs, 0, durationMs);
        engine.seekTo(target);
        seekBar.setProgress(target);
        updateTimeLabels(target, durationMs);
    }

    /** Played time on the left, total in the middle, remaining on the right. */
    private void updateTimeLabels(int position, int duration) {
        String newPosition = TimeFormat.clock(position);
        if (!newPosition.equals(positionLabel)) {
            positionLabel = newPosition;
            timePosition.setText(newPosition);
        }
        String newTotal = TimeFormat.clock(duration);
        if (!newTotal.equals(totalLabel)) {
            totalLabel = newTotal;
            timeTotal.setText(newTotal);
        }
        String newRemaining = getString(R.string.time_remaining,
                TimeFormat.clock(Math.max(0, duration - position)));
        if (!newRemaining.equals(remainingLabel)) {
            remainingLabel = newRemaining;
            timeRemaining.setText(newRemaining);
        }
    }

    private void updateProgressFromEngine() {
        if (current == null || durationMs <= 0) {
            updateTimeLabels(0, 0);
            return;
        }
        int position = TimeFormat.clamp(engine.position(), 0, durationMs);
        if (!trackingSeek) {
            if (seekBar.getMax() != durationMs) {
                seekBar.setMax(durationMs);
            }
            seekBar.setProgress(position);
        }
        updateTimeLabels(position, durationMs);
    }

    private void updatePlayPauseButton() {
        boolean playing = engine.isPlaying();
        // The icon is a state-list drawable: selected shows the pause glyph.
        btnPlay.setSelected(playing);
        btnPlay.setContentDescription(getString(playing ? R.string.pause : R.string.play));
    }

    private void updateControls() {
        boolean hasTrack = current != null;
        boolean preparedTrack = hasTrack && prepared;
        boolean hasFiles = !library.files().isEmpty();
        setControlEnabled(btnBack, preparedTrack);
        setControlEnabled(btnForward, preparedTrack);
        setControlEnabled(seekBar, preparedTrack);
        setControlEnabled(padBack, preparedTrack);
        setControlEnabled(padForward, preparedTrack);
        setControlEnabled(btnPlay, hasTrack || hasFiles);
        setControlEnabled(btnPrev, hasTrack || hasFiles);
        setControlEnabled(btnNext, hasTrack || hasFiles);
    }

    private static void setControlEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.4f);
    }

    private void startTickerIfPlaying() {
        if (engine.isPlaying()) {
            handler.removeCallbacks(ticker);
            handler.postDelayed(ticker, 500);
        }
    }

    private void applyKeepAwake() {
        if (prefs.keepScreenOn() && engine.isPlaying()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ---------------------------------------------------------------- playback

    private void playFile(AudioFile file, int mode) {
        if (file == null) {
            return;
        }
        String hash = ensureFingerprint(file);
        Db.Position saved = hash != null
                ? db.findPosition(hash)
                : db.findPositionByPath(file.path);
        if (saved == null && hash != null) {
            // A row exists for this path but with different content: the file was
            // replaced, so the old position must not be reused.
            Db.Position stale = db.findPositionByPath(file.path);
            if (stale != null) {
                db.deletePosition(null, file.path);
                library.removePosition(file.path);
            }
        }
        int startAt = 0;
        if (saved != null) {
            if (saved.durationMs > 0 && saved.positionMs >= saved.durationMs - 1500L) {
                db.deletePosition(hash, file.path);
                library.removePosition(file.path);
            } else if (saved.positionMs > 1000L) {
                startAt = (int) Math.min(saved.positionMs, Integer.MAX_VALUE - 1);
            }
        }

        current = file;
        queueMode = mode;
        if (mode == Prefs.MODE_FOLDER) {
            FolderNode node = browser.currentNode();
            if (node != null) {
                folderQueue = node.files;
                folderIndex = indexOf(node.files, file.path);
            }
            browser.revealFile(file.path);
        }
        // The current file is always the first row of the "All files" page,
        // regardless of the mode playback was started from.
        library.queue().rotateTo(file.path);
        prefs.setLastFile(file.path);
        prefs.setLastMode(mode);

        prepared = false;
        durationMs = 0;
        saveTicks = 0;
        seekBar.setMax(1);
        seekBar.setProgress(0);
        updateTimeLabels(0, 0);
        updatePlayPauseButton();
        updateControls();
        adapter.setPlayingPath(file.path);
        browser.setPlaying(file.path);
        engine.open(file.path, startAt, true);
    }

    private void selectFolderFile(AudioFile file) {
        playFile(file, Prefs.MODE_FOLDER);
        FolderNode node = browser.currentNode();
        if (node != null) {
            db.saveFolderState(node.path, file.path, listFolders.getFirstVisiblePosition());
        }
    }

    private void playNext() {
        if (current == null) {
            startFirst();
            return;
        }
        if (queueMode == Prefs.MODE_FOLDER) {
            if (folderQueue == null || folderQueue.size() < 2) {
                return;
            }
        }
        saveCurrentPosition(false);
        AudioFile next = null;
        if (queueMode == Prefs.MODE_FOLDER && folderQueue != null && !folderQueue.isEmpty()) {
            folderIndex = (folderIndex + 1) % folderQueue.size();
            next = folderQueue.get(folderIndex);
        } else {
            next = library.queue().advanceFrom(current.path);
            adapter.notifyDataSetChanged();
        }
        if (next != null) {
            current = null;
            playFile(next, queueMode == Prefs.MODE_FOLDER ? Prefs.MODE_FOLDER : Prefs.MODE_UNIFIED);
            listAll.setSelection(0);
        }
    }

    private void playPrevious() {
        if (current == null) {
            startFirst();
            return;
        }
        if (queueMode == Prefs.MODE_FOLDER) {
            if (folderQueue == null || folderQueue.size() < 2) {
                return;
            }
        }
        saveCurrentPosition(false);
        AudioFile previous = null;
        if (queueMode == Prefs.MODE_FOLDER && folderQueue != null && !folderQueue.isEmpty()) {
            folderIndex = (folderIndex - 1 + folderQueue.size()) % folderQueue.size();
            previous = folderQueue.get(folderIndex);
        } else {
            previous = library.queue().retreatFrom(current.path);
            adapter.notifyDataSetChanged();
        }
        if (previous != null) {
            current = null;
            playFile(previous, queueMode == Prefs.MODE_FOLDER ? Prefs.MODE_FOLDER : Prefs.MODE_UNIFIED);
            listAll.setSelection(0);
        }
    }

    private void handleTrackEnded() {
        AudioFile finished = current;
        if (finished == null) {
            return;
        }
        saveCurrentPosition(true);
        AudioFile next = null;
        if (queueMode == Prefs.MODE_FOLDER && folderQueue != null && folderQueue.size() > 1) {
            folderIndex = (folderIndex + 1) % folderQueue.size();
            next = folderQueue.get(folderIndex);
        } else if (queueMode == Prefs.MODE_UNIFIED) {
            next = library.queue().advanceFrom(finished.path);
            adapter.notifyDataSetChanged();
        }
        if (next != null) {
            current = null;
            playFile(next, queueMode);
            if (queueMode == Prefs.MODE_UNIFIED) {
                listAll.setSelection(0);
            }
        } else {
            // End of the queue: keep the track loaded (Play restarts it).
            updateControls();
            updatePlayPauseButton();
        }
    }

    private void saveCurrentPosition(boolean finished) {
        AudioFile file = current;
        if (file == null) {
            return;
        }
        int duration = durationMs > 0 ? durationMs : engine.duration();
        int position = engine.position();
        if (duration <= 0) {
            return;
        }
        String hash = ensureFingerprint(file);
        if (hash == null) {
            return;
        }
        if (finished || position >= duration - 1500) {
            db.deletePosition(hash, file.path);
            library.removePosition(file.path);
        } else if (position >= 1000) {
            db.savePosition(hash, file.path, position, duration);
            library.putPosition(file.path, position);
        }
    }

    private String ensureFingerprint(AudioFile file) {
        String hash = file.fingerprint();
        if (hash == null) {
            hash = Fingerprint.ofPath(file.path);
            if (hash != null) {
                file.setFingerprint(hash);
            }
        }
        return hash;
    }

    private static int indexOf(List<AudioFile> files, String path) {
        if (files == null) {
            return -1;
        }
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).path.equals(path)) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- adapters

    @Override
    public void onFileClick(AudioFile file) {
        playFile(file, Prefs.MODE_UNIFIED);
        adapter.notifyDataSetChanged();
        listAll.setSelection(0);
    }

    @Override
    public void onDeleteClick(AudioFile file) {
        confirmDelete(file);
    }

    @Override
    public void onFolderClick(FolderNode folder) {
        browser.show(folder.path);
    }

    @Override
    public void onParentClick() {
        browser.up();
    }

    @Override
    public void onFolderFileClick(AudioFile file) {
        selectFolderFile(file);
    }

    @Override
    public void onFolderFileDelete(AudioFile file) {
        confirmDelete(file);
    }

    @Override
    public void onFolderOpened(String path) {
        prefs.setLastFolder(path);
    }

    // ---------------------------------------------------------------- library

    @Override
    public void onScanStarted() {
        updateEmptyState();
        if (settingsLibraryInfo != null) {
            settingsLibraryInfo.setText(R.string.scanning);
        }
    }

    @Override
    public void onScanProgress(int files) {
        if (library.files().isEmpty() && !isFinishing()) {
            emptyBody.setText(getString(R.string.scanning_count, files));
        }
    }

    @Override
    public void onLibraryChanged() {
        afterLibraryChanged();
    }

    @Override
    public void onScanFinished(int files, String error, boolean userInitiated) {
        updateEmptyState();
        if (!isFinishing() && "unreadable".equals(error)
                && Storage.accessLevel(this) != Storage.ACCESS_FULL) {
            showPermissionOverlay();
        }
        if (userInitiated && !isFinishing()) {
            Toast.makeText(this, error == null
                    ? getString(R.string.scan_done, files)
                    : getString(R.string.scan_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void afterLibraryChanged() {
        adapter.setFiles(library.files());
        adapter.setPositions(library.positions());
        adapter.notifyDataSetChanged();

        if (current != null) {
            AudioFile updated = library.find(current.path);
            if (updated != null) {
                if (current.fingerprint() != null) {
                    updated.setFingerprint(current.fingerprint());
                }
                current = updated;
            } else if (!new File(current.path).exists()) {
                // The file is gone: stop instead of showing a dead track.
                current = null;
                prepared = false;
                durationMs = 0;
                engine.stopAndRelease();
            }
            // Otherwise the file still exists but is outside the scanned folder
            // (the user switched roots): keep playing it.
        }
        if (current == null && engine.hasTrack()) {
            AudioFile updated = library.find(engine.currentPath());
            if (updated != null) {
                current = updated;
            } else if (!new File(engine.currentPath()).exists()) {
                engine.stopAndRelease();
                prepared = false;
                durationMs = 0;
            }
        }
        if (current != null) {
            queueMode = prefs.lastMode();
            durationMs = engine.duration();
            prepared = durationMs > 0;
            if (prepared) {
                seekBar.setMax(durationMs);
            }
            if (queueMode == Prefs.MODE_FOLDER) {
                FolderNode node = library.node(current.parentPath());
                if (node != null) {
                    folderQueue = node.files;
                    folderIndex = indexOf(node.files, current.path);
                }
            }
            adapter.setPlayingPath(current.path);
            browser.setPlaying(current.path);
        } else {
            adapter.setPlayingPath(null);
            browser.setPlaying(null);
        }

        browser.refreshPositions();
        if (browser.currentPath() == null) {
            browser.restore(prefs.lastFolder());
        } else {
            browser.refresh();
        }
        updatePlayPauseButton();
        updateControls();
        updateProgressFromEngine();
        updateEmptyState();
        refreshSettings();
        startTickerIfPlaying();
    }

    private boolean hasLibraryAccess() {
        return Storage.canRead(this, currentRoot());
    }

    private String currentRoot() {
        return prefs.root(defaultRoot);
    }

    private String anchorForScan() {
        if (current != null) {
            return current.path;
        }
        return prefs.lastFile();
    }

    private void startScan(boolean userInitiated) {
        if (!hasLibraryAccess()) {
            showPermissionOverlay();
            return;
        }
        String root = currentRoot();
        if (library.isScanning() && root.equals(lastScanRoot)) {
            return;
        }
        lastScanRoot = root;
        if (root.equals(defaultRoot) && !new File(root).isDirectory()) {
            Storage.ensureFolder(root);
        }
        hidePermissionOverlay();
        library.scan(root, anchorForScan(), userInitiated);
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (scanSpinner == null) {
            return;
        }
        if (library.isScanning()) {
            scanSpinner.setVisibility(View.VISIBLE);
            emptyTitle.setText(R.string.scanning);
            emptyBody.setText(getString(R.string.scanning_count, library.files().size()));
            emptySettings.setVisibility(View.GONE);
            return;
        }
        scanSpinner.setVisibility(View.GONE);
        emptyTitle.setText(R.string.empty_library_title);
        String root = currentRoot();
        String error = library.result() == null ? null : library.result().error;
        if (!hasLibraryAccess() || (error != null && !isMissing(error))) {
            emptyBody.setText(getString(R.string.root_unreadable, root));
        } else if (isMissing(error)) {
            emptyBody.setText(getString(R.string.root_missing, root));
        } else if (library.result() != null && library.files().isEmpty()
                && Storage.accessLevel(this) != Storage.ACCESS_FULL
                && !Storage.isAppFolder(this, root)) {
            // A shared folder can look empty when Android only granted media
            // access: listing it needs "all files access".
            emptyBody.setText(getString(R.string.empty_library_need_access, root));
        } else {
            emptyBody.setText(getString(R.string.empty_library_body, root));
        }
        emptySettings.setVisibility(View.VISIBLE);
    }

    /** A folder that does not exist (yet) reads differently from "no access". */
    private static boolean isMissing(String error) {
        return "missing".equals(error) || "not_a_directory".equals(error);
    }

    // ---------------------------------------------------------------- deletion

    private void confirmDelete(final AudioFile file) {
        if (prefs.deleteWithoutConfirm()) {
            deleteFile(file);
            return;
        }
        final boolean playing = current != null && current.path.equals(file.path);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.delete_title)
                .setMessage(getString(playing ? R.string.delete_message_playing
                        : R.string.delete_message, file.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        deleteFile(file);
                    }
                })
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE)
                        .setTextColor(getColor(R.color.danger));
            }
        });
        dialog.show();
    }

    private void deleteFile(final AudioFile file) {
        boolean playing = current != null && current.path.equals(file.path);
        boolean deleted;
        try {
            deleted = new File(file.path).delete();
        } catch (SecurityException e) {
            deleted = false;
        }
        if (!deleted) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.delete_failed_title)
                    .setMessage(R.string.delete_failed)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.tab_settings, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            showPage(PAGE_SETTINGS, true);
                        }
                    })
                    .show();
            return;
        }

        if (playing) {
            handler.removeCallbacks(ticker);
            engine.stopAndRelease();
            current = null;
            prepared = false;
            durationMs = 0;
            seekBar.setMax(1);
            seekBar.setProgress(0);
            updateTimeLabels(0, 0);
            adapter.setPlayingPath(null);
            browser.setPlaying(null);
            updatePlayPauseButton();
            updateControls();
        }
        if (file.path.equals(prefs.lastFile())) {
            prefs.setLastFile(null);
        }
        db.deletePosition(file.fingerprint(), file.path);
        library.removePosition(file.path);
        library.removeFile(file);
        adapter.notifyDataSetChanged();
        browser.refresh();
        Toast.makeText(this, getString(R.string.deleted, file.name), Toast.LENGTH_SHORT).show();
        startScan(false);
    }

    // ---------------------------------------------------------------- settings

    private void wireSettings() {
        settingsSeekBar.setMax(Prefs.MAX_SEEK_STEP - Prefs.MIN_SEEK_STEP);
        settingsSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                settingsSeekValue.setText(getString(R.string.settings_seek_value,
                        progress + Prefs.MIN_SEEK_STEP));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                prefs.setSeekStep(bar.getProgress() + Prefs.MIN_SEEK_STEP);
                updateStepLabels();
            }
        });
        settingsDeleteSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                        if (!bindingSettings) {
                            prefs.setDeleteWithoutConfirm(checked);
                        }
                    }
                });
        settingsKeepAwakeSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                        if (!bindingSettings) {
                            prefs.setKeepScreenOn(checked);
                            applyKeepAwake();
                        }
                    }
                });
        findViewById(R.id.settings_delete_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingsDeleteSwitch.toggle();
            }
        });
        findViewById(R.id.settings_keep_awake_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingsKeepAwakeSwitch.toggle();
            }
        });
        findViewById(R.id.settings_choose_folder).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, FolderPickerActivity.class);
                intent.putExtra(FolderPickerActivity.EXTRA_START, currentRoot());
                startActivityForResult(intent, RC_PICK_FOLDER);
            }
        });
        findViewById(R.id.settings_quick_music).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setRootFolder(Storage.musicFolder(MainActivity.this));
            }
        });
        findViewById(R.id.settings_quick_books).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setRootFolder(Storage.sharedFolder(MainActivity.this, Storage.BOOKS));
            }
        });
        findViewById(R.id.settings_quick_downloads).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setRootFolder(Storage.sharedFolder(MainActivity.this, "Downloads"));
            }
        });
        // The SD card button only makes sense when a removable volume is mounted.
        View sdButton = findViewById(R.id.settings_quick_sd);
        View sdSpace = findViewById(R.id.settings_sd_space);
        View downloadsButton = findViewById(R.id.settings_quick_downloads);
        final String sdRoot = Storage.removableRoot(this);
        if (sdRoot == null) {
            // No card inserted: let Downloads use the full row instead of
            // leaving a hole where the SD button would be.
            sdButton.setVisibility(View.GONE);
            sdSpace.setVisibility(View.GONE);
            ((LinearLayout.LayoutParams) downloadsButton.getLayoutParams()).weight = 2f;
            downloadsButton.requestLayout();
        } else {
            sdButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setRootFolder(sdRoot);
                }
            });
        }
        settingsGrant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestFullAccess();
            }
        });
        findViewById(R.id.settings_clear_positions).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClearPositions();
            }
        });
        findViewById(R.id.settings_rescan).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan(true);
            }
        });
        String version = versionName();
        settingsAbout.setText(getString(R.string.about_text, version));
        settingsVersion.setText(getString(R.string.settings_version, version));
    }

    private void refreshSettings() {
        if (settingsRootPath == null) {
            return;
        }
        bindingSettings = true;
        settingsRootPath.setText(currentRoot());
        int step = prefs.seekStep();
        settingsSeekBar.setProgress(step - Prefs.MIN_SEEK_STEP);
        settingsSeekValue.setText(getString(R.string.settings_seek_value, step));
        settingsDeleteSwitch.setChecked(prefs.deleteWithoutConfirm());
        settingsKeepAwakeSwitch.setChecked(prefs.keepScreenOn());
        // With full access the whole notification block disappears and the card
        // gets a green outline instead: less text, one glance is enough.
        int access = Storage.accessLevel(this);
        boolean full = access == Storage.ACCESS_FULL;
        settingsAccessBlock.setVisibility(full ? View.GONE : View.VISIBLE);
        settingsRootCard.setBackgroundResource(full ? R.drawable.bg_card_ok : R.drawable.bg_card);
        if (!full) {
            if (access == Storage.ACCESS_READ) {
                settingsAccessStatus.setText(R.string.access_read_only);
                settingsAccessStatus.setTextColor(getColor(R.color.accent));
                settingsAccessHint.setText(R.string.access_limited_hint);
            } else {
                settingsAccessStatus.setText(R.string.access_none);
                settingsAccessStatus.setTextColor(getColor(R.color.danger));
                settingsAccessHint.setText(R.string.access_hint_none);
            }
            settingsGrant.setText(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? R.string.grant_all_files : R.string.grant_access);
            settingsGrant.setVisibility(View.VISIBLE);
        }
        rebuildHistory();
        if (library.result() == null) {
            settingsLibraryInfo.setText(R.string.files_count_unknown);
        } else if (library.isScanning()) {
            settingsLibraryInfo.setText(R.string.scanning);
        } else {
            settingsLibraryInfo.setText(getString(R.string.settings_files_count,
                    library.files().size()));
        }
        bindingSettings = false;
        updateStepLabels();
    }

    private static String requestedReadPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void requestFullAccess() {
        if (Storage.accessLevel(this) == Storage.ACCESS_FULL) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Storage.openAllFilesSettings(this, RC_ALL_FILES);
        } else {
            Storage.requestReadAccess(this, RC_PERMISSION);
        }
    }

    /**
     * Switches the library folder: remembers it (Settings shows the recent
     * paths), recreates standard folders when they are missing, and rescans.
     */
    private void setRootFolder(String path) {
        if (path == null || path.length() == 0) {
            return;
        }
        if (!new File(path).isDirectory() && !Storage.ensureFolder(path)) {
            // Android 11+ without "all files access" cannot create shared
            // folders; keep the current library instead of pointing at nothing.
            Toast.makeText(this, getString(R.string.folder_unavailable, path),
                    Toast.LENGTH_LONG).show();
            return;
        }
        prefs.setRoot(path);
        historySignature = null;
        refreshSettings();
        startScan(true);
    }

    /** Recent folders in Settings: tap to switch, trash to forget. */
    private void rebuildHistory() {
        if (settingsHistory == null) {
            return;
        }
        List<String> all = prefs.rootHistory();
        String root = currentRoot();
        StringBuilder signature = new StringBuilder();
        for (String path : all) {
            if (!path.equals(root)) {
                signature.append(path).append('\n');
            }
        }
        String signatureText = signature.toString();
        if (signatureText.equals(historySignature)) {
            return;
        }
        historySignature = signatureText;
        settingsHistory.removeAllViews();
        int shown = 0;
        for (final String path : all) {
            if (path.equals(root)) {
                continue;
            }
            View row = getLayoutInflater().inflate(R.layout.item_path, settingsHistory, false);
            ((TextView) row.findViewById(R.id.path_text)).setText(path);
            View.OnClickListener choose = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setRootFolder(path);
                }
            };
            row.setOnClickListener(choose);
            row.findViewById(R.id.path_delete).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.forgetRoot(path);
                    historySignature = null;
                    rebuildHistory();
                }
            });
            settingsHistory.addView(row);
            shown++;
        }
        settingsHistoryLabel.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
    }

    private void confirmClearPositions() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_positions_title)
                .setMessage(R.string.clear_positions_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear_positions_confirm,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                db.clearPositions();
                                library.clearPositions();
                                adapter.notifyDataSetChanged();
                                browser.refreshPositions();
                                Toast.makeText(MainActivity.this, R.string.cleared_positions,
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                .show();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- permissions

    private void wirePermissionCard() {
        permissionHint.setText(getString(R.string.permission_app_folder_hint,
                Storage.appFolder(this)));
        findViewById(R.id.permission_grant).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestFullAccess();
            }
        });
        findViewById(R.id.permission_app_folder).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setRootFolder(Storage.appFolder(MainActivity.this));
                hidePermissionOverlay();
                Toast.makeText(MainActivity.this, R.string.app_folder_used,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showPermissionOverlay() {
        if (permissionOverlay == null) {
            return;
        }
        boolean limited = Storage.accessLevel(this) == Storage.ACCESS_READ;
        permissionBody.setText(limited ? R.string.permission_body_limited : R.string.permission_body);
        permissionGrant.setText(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? R.string.permission_grant_all_files : R.string.permission_grant);
        permissionHint.setText(getString(R.string.permission_app_folder_hint,
                Storage.appFolder(this)));
        permissionOverlay.setVisibility(View.VISIBLE);
    }

    private void hidePermissionOverlay() {
        if (permissionOverlay != null) {
            permissionOverlay.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_PICK_FOLDER && resultCode == RESULT_OK && data != null) {
            String path = data.getStringExtra(FolderPickerActivity.EXTRA_RESULT);
            if (path != null && path.length() > 0) {
                setRootFolder(path);
            }
        }
        if (requestCode == RC_ALL_FILES && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && Storage.accessLevel(this) == Storage.ACCESS_NONE
                && !Storage.granted(this, requestedReadPermission())) {
            // "All files access" was refused. Audio-only access still allows
            // playback on most devices, so ask for it once as a fallback.
            Storage.requestReadAccess(this, RC_PERMISSION);
            return;
        }
        if (hasLibraryAccess()) {
            hidePermissionOverlay();
            refreshSettings();
            if (library.result() == null || requestCode == RC_ALL_FILES) {
                startScan(false);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != RC_PERMISSION) {
            return;
        }
        if (Storage.accessLevel(this) != Storage.ACCESS_NONE) {
            hidePermissionOverlay();
            startScan(true);
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
        }
        refreshSettings();
    }

    // ---------------------------------------------------------------- player events

    @Override
    public void onPrepared(int duration) {
        durationMs = Math.max(0, duration);
        prepared = durationMs > 0;
        if (prepared) {
            seekBar.setMax(durationMs);
        }
        updateProgressFromEngine();
        updateControls();
        updatePlayPauseButton();
        startTickerIfPlaying();
    }

    @Override
    public void onPlayStateChanged(boolean playing) {
        updatePlayPauseButton();
        applyKeepAwake();
        if (playing) {
            handler.removeCallbacks(ticker);
            handler.postDelayed(ticker, 500);
        } else {
            handler.removeCallbacks(ticker);
            updateProgressFromEngine();
        }
    }

    @Override
    public void onTrackEnded() {
        handleTrackEnded();
    }

    @Override
    public void onError(int what, int extra) {
        handler.removeCallbacks(ticker);
        prepared = false;
        updateControls();
        updatePlayPauseButton();
        String name = current != null ? current.name : "";
        Toast.makeText(this, getString(R.string.cannot_play) + (name.length() > 0 ? ": " + name : ""),
                Toast.LENGTH_LONG).show();
    }
}

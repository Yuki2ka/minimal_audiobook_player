package io.github.yuki2ka.audiobookplayer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import io.github.yuki2ka.audiobookplayer.ui.PickerAdapter;
import io.github.yuki2ka.audiobookplayer.util.NaturalSort;

/**
 * Plain directory picker. Framework list + a path field, no SAF and no
 * dependencies; returns the chosen absolute path in {@link #EXTRA_RESULT}.
 */
public final class FolderPickerActivity extends Activity {

    public static final String EXTRA_START = "start_path";
    public static final String EXTRA_RESULT = "result_path";

    private static final Comparator<File> FOLDER_ORDER = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            int result = NaturalSort.compare(
                    NaturalSort.normalize(a.getName()), NaturalSort.normalize(b.getName()));
            return result != 0 ? result : a.getName().compareTo(b.getName());
        }
    };

    private PickerAdapter adapter;
    private ListView list;
    private TextView pathView;
    private TextView emptyView;
    private EditText input;
    private ImageButton upButton;
    private File current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_folder_picker);

        list = findViewById(R.id.picker_list);
        pathView = findViewById(R.id.picker_path);
        emptyView = findViewById(R.id.picker_empty);
        input = findViewById(R.id.picker_input);
        upButton = findViewById(R.id.picker_up);

        adapter = new PickerAdapter(this, new PickerAdapter.Callbacks() {
            @Override
            public void onFolderClick(File folder) {
                show(folder);
            }
        });
        list.setAdapter(adapter);
        list.setEmptyView(emptyView);

        File start = null;
        String extra = getIntent().getStringExtra(EXTRA_START);
        if (extra != null && extra.length() > 0) {
            start = new File(extra);
        }
        if (start == null || !start.isDirectory()) {
            start = Environment.getExternalStorageDirectory();
        }
        if (start == null) {
            start = new File("/");
        }
        show(start);

        upButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goUp();
            }
        });
        findViewById(R.id.picker_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.picker_use).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                useCurrent();
            }
        });
        findViewById(R.id.picker_go).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goTyped();
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    goTyped();
                    return true;
                }
                return false;
            }
        });
    }

    private void show(File directory) {
        if (directory == null) {
            return;
        }
        if (!directory.isDirectory()) {
            Toast.makeText(this, R.string.picker_not_readable, Toast.LENGTH_SHORT).show();
            return;
        }
        current = directory;
        pathView.setText(directory.getAbsolutePath());
        boolean hasParent = directory.getParentFile() != null;
        upButton.setEnabled(hasParent);
        upButton.setAlpha(hasParent ? 1f : 0.35f);
        List<File> folders = listFolders(directory);
        adapter.setFolders(folders);
        boolean denied = folders.isEmpty() && !canList(directory);
        emptyView.setText(denied ? R.string.picker_denied : R.string.picker_empty);
        emptyView.setVisibility(adapter.getCount() == 0 ? View.VISIBLE : View.GONE);
        list.setSelection(0);
    }

    /** {@code listFiles()} returning {@code null} means "not allowed", not "empty". */
    private static boolean canList(File directory) {
        try {
            return directory.listFiles() != null;
        } catch (SecurityException e) {
            return false;
        }
    }

    private List<File> listFolders(File directory) {
        List<File> result = new ArrayList<>();
        File[] children;
        try {
            children = directory.listFiles();
        } catch (SecurityException e) {
            children = null;
        }
        if (children != null) {
            for (File child : children) {
                try {
                    if (child.isDirectory()) {
                        result.add(child);
                    }
                } catch (SecurityException ignored) {
                    // Skip entries we may not inspect.
                }
            }
        }
        Collections.sort(result, FOLDER_ORDER);
        return result;
    }

    private void goUp() {
        if (current == null) {
            return;
        }
        File parent = current.getParentFile();
        if (parent != null) {
            show(parent);
        }
    }

    private void goTyped() {
        String text = input.getText().toString().trim();
        if (text.length() == 0) {
            return;
        }
        File target = new File(text);
        if (!target.isAbsolute() && current != null) {
            target = new File(current, text);
        }
        if (target.isDirectory()) {
            show(target);
            input.setText("");
        } else {
            Toast.makeText(this, R.string.picker_not_readable, Toast.LENGTH_SHORT).show();
        }
    }

    private void useCurrent() {
        if (current == null) {
            return;
        }
        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT, current.getAbsolutePath());
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (current != null && current.getParentFile() != null) {
            goUp();
        } else {
            super.onBackPressed();
        }
    }
}

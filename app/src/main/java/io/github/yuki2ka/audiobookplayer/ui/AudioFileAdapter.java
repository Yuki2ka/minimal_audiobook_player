package io.github.yuki2ka.audiobookplayer.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.github.yuki2ka.audiobookplayer.R;
import io.github.yuki2ka.audiobookplayer.model.AudioFile;
import io.github.yuki2ka.audiobookplayer.model.FileListRows;
import io.github.yuki2ka.audiobookplayer.util.TimeFormat;

/**
 * Flat list of audio files (the "All files" page). Folder names are shown once
 * as delimiters, not repeated underneath every file. ListView recycling uses a
 * separate view type for delimiters and plain ViewHolders for both row kinds.
 */
public final class AudioFileAdapter extends BaseAdapter {

    public interface Callbacks {
        void onFileClick(AudioFile file);

        void onDeleteClick(AudioFile file);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final Callbacks callbacks;
    private final boolean showFolderNames;
    private final FileListRows rows = new FileListRows();

    private List<AudioFile> items = new ArrayList<>();
    private Map<String, Long> positions = Collections.emptyMap();
    private String playingPath;
    private boolean discreteMode;

    public AudioFileAdapter(Context context, Callbacks callbacks, boolean showFolderNames) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.callbacks = callbacks;
        this.showFolderNames = showFolderNames;
    }

    /** Keeps a reference to the live queue list (no copy of the files). */
    public void setFiles(List<AudioFile> files) {
        this.items = files == null ? new ArrayList<AudioFile>() : files;
        refreshFileOrder();
    }

    /** Rebuilds delimiters after the live circular queue has been rotated. */
    public final void refreshFileOrder() {
        rows.rebuild(items, showFolderNames);
        notifyDataSetChanged();
    }

    public void setPositions(Map<String, Long> positions) {
        this.positions = positions == null ? Collections.<String, Long>emptyMap() : positions;
        notifyDataSetChanged();
    }

    public void setPlayingPath(String path) {
        this.playingPath = path;
        notifyDataSetChanged();
    }

    /**
     * File rows have the same fixed height in both modes. Folder delimiters
     * expand to that height only in discrete mode, so every displayed row has
     * one exact, predictable extent.
     */
    public void setDiscreteMode(boolean enabled) {
        if (discreteMode == enabled) {
            return;
        }
        discreteMode = enabled;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Object getItem(int position) {
        return rows.fileAt(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.typeAt(position);
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        return rows.typeAt(position) == FileListRows.TYPE_FILE;
    }

    /** Maps an adapter row to its index in the underlying file queue. */
    public int fileIndexAtOrAfter(int row) {
        return rows.fileIndexAtOrAfter(row);
    }

    /** Maps a file queue index back to its adapter row. */
    public int rowForFileIndex(int fileIndex) {
        return rows.rowForFileIndex(fileIndex);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (rows.typeAt(position) == FileListRows.TYPE_FOLDER) {
            return folderView(position, convertView, parent);
        }
        return fileView(position, convertView, parent);
    }

    private View folderView(int position, View convertView, ViewGroup parent) {
        FolderHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_folder_delimiter, parent, false);
            holder = new FolderHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (FolderHolder) convertView.getTag();
        }

        int height = context.getResources().getDimensionPixelSize(discreteMode
                ? R.dimen.row_min_height
                : R.dimen.folder_delimiter_height);
        ViewGroup.LayoutParams params = holder.root.getLayoutParams();
        if (params != null && params.height != height) {
            params.height = height;
            holder.root.setLayoutParams(params);
        }
        String name = rows.folderNameAt(position);
        holder.title.setText(name == null || name.length() == 0
                ? context.getString(R.string.folder_label)
                : name);
        holder.title.setContentDescription(context.getString(R.string.folder_label)
                + ": " + holder.title.getText());
        return convertView;
    }

    private View fileView(int position, View convertView, ViewGroup parent) {
        Holder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_audio, parent, false);
            final Holder created = new Holder(convertView);
            convertView.setTag(created);
            created.root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (created.file != null) {
                        callbacks.onFileClick(created.file);
                    }
                }
            });
            created.delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (created.file != null) {
                        callbacks.onDeleteClick(created.file);
                    }
                }
            });
            holder = created;
        } else {
            holder = (Holder) convertView.getTag();
        }

        AudioFile file = rows.fileAt(position);
        holder.file = file;
        boolean playing = file.path.equals(playingPath);

        holder.title.setText(file.name);
        holder.subtitle.setText(subtitleFor(file));
        holder.title.setTextColor(context.getColor(playing ? R.color.accent : R.color.text_primary));
        holder.icon.setImageResource(playing ? R.drawable.ic_eq : R.drawable.ic_audio);
        holder.icon.setImageTintList(ColorStateList.valueOf(
                context.getColor(playing ? R.color.accent : R.color.text_secondary)));
        holder.root.setBackgroundResource(playing ? R.drawable.bg_row_playing : R.drawable.bg_row);
        holder.groupEnd.setVisibility(rows.isGroupEnd(position) ? View.VISIBLE : View.GONE);
        holder.delete.setContentDescription(
                context.getString(R.string.delete_file) + ": " + file.name);
        return convertView;
    }

    private CharSequence subtitleFor(AudioFile file) {
        // The parent folder is already named by the delimiter above the group.
        String detail = Formatter.formatShortFileSize(context, file.size);
        Long saved = positions.get(file.path);
        if (saved != null && saved > 0) {
            return detail + " \u00B7 "
                    + context.getString(R.string.resume_at, TimeFormat.clock(saved));
        }
        return detail;
    }

    private static final class FolderHolder {
        final View root;
        final TextView title;

        FolderHolder(View view) {
            root = view;
            title = view.findViewById(R.id.folder_delimiter_name);
        }
    }

    private static final class Holder {
        final View root;
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final ImageButton delete;
        final View groupEnd;
        AudioFile file;

        Holder(View view) {
            root = view;
            icon = view.findViewById(R.id.row_icon);
            title = view.findViewById(R.id.row_title);
            subtitle = view.findViewById(R.id.row_subtitle);
            delete = view.findViewById(R.id.row_delete);
            groupEnd = view.findViewById(R.id.row_group_end);
        }
    }
}

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
import io.github.yuki2ka.audiobookplayer.model.QueueEntry;
import io.github.yuki2ka.audiobookplayer.util.TimeFormat;

/**
 * The unified "All files" list: a depth-first folder tree drawn as one list.
 *
 * <p>Every folder is announced by an opening separator (folder name, indented by
 * its nesting depth) and closed by a separator that is just a line — so a folder
 * that wraps around the end of the rotating list is still unambiguous. Files
 * follow their folder's separator, sub-folders before the folder's own files.
 * Files and separators are rows of the same ordered queue, so ListView recycling
 * uses one view type for separators and plain ViewHolders for both row kinds.</p>
 */
public final class AudioFileAdapter extends BaseAdapter {

    public interface Callbacks {
        void onFileClick(AudioFile file);

        void onDeleteClick(AudioFile file);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final Callbacks callbacks;
    private final FileListRows rows = new FileListRows();

    /** The live queue: files and separators, in display order. */
    private List<QueueEntry> items = new ArrayList<>();
    private Map<String, Long> positions = Collections.emptyMap();
    /** Indent applied per folder nesting level, resolved once. */
    private final int depthIndentPx;
    private String playingPath;
    private boolean discreteMode;
    /** Lazily measured, shared by all rows in discrete mode. -1 until needed. */
    private int discreteRowHeight = -1;

    public AudioFileAdapter(Context context, Callbacks callbacks) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.callbacks = callbacks;
        this.depthIndentPx = context.getResources().getDimensionPixelSize(R.dimen.folder_indent);
    }

    /**
     * One exact height for every discrete row: the natural height of a
     * one-line file row, measured with the current font scale and floored at
     * the shared {@code row_min_height}. That is the height a smooth row uses,
     * so discrete mode no longer adds an extra gutter per row (a fixed 64 sp
     * dimension grew with the font scale and made the gaps much larger), while
     * large system text still gets enough room for the size / resume line.
     */
    public int discreteRowHeightPx() {
        if (discreteRowHeight < 0) {
            View probe = inflater.inflate(R.layout.item_audio, null, false);
            TextView title = probe.findViewById(R.id.row_title);
            title.setMaxLines(1);
            title.setText("Chapter 10");
            ((TextView) probe.findViewById(R.id.row_subtitle)).setText("12 MB");
            int width = context.getResources().getDisplayMetrics().widthPixels;
            probe.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int min = context.getResources().getDimensionPixelSize(R.dimen.row_min_height);
            discreteRowHeight = Math.max(min, probe.getMeasuredHeight());
        }
        return discreteRowHeight;
    }

    /** Keeps a reference to the live queue rows (no copy of the files). */
    public void setEntries(List<QueueEntry> entries) {
        this.items = entries == null ? new ArrayList<QueueEntry>() : entries;
        refreshFileOrder();
    }

    /** Re-reads the live queue after it was rotated (rows move, nothing else). */
    public final void refreshFileOrder() {
        rows.rebuild(items);
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
     * In smooth mode file rows grow with their two text lines (minimum 60 dp),
     * so long names and the size / saved-position info are never clipped. In
     * discrete mode every row — file or folder delimiter — gets the same exact
     * measured height (see {@link #discreteRowHeightPx()}) and file names go
     * back to one line, so the viewport still holds an integer number of
     * complete rows with the same gaps as smooth mode.
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

    /** Maps an adapter row to the same index in the underlying queue. */
    public int queueIndexAtOrAfter(int row) {
        return rows.queueIndexAtOrAfter(row);
    }

    /**
     * Row of the first audio file, i.e. the first row below the separators that
     * lead the list (a rotated list can start with the playing file's folder).
     */
    public int firstFileRow() {
        for (int i = 0, n = rows.size(); i < n; i++) {
            if (rows.typeAt(i) == FileListRows.TYPE_FILE) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (rows.typeAt(position) == FileListRows.TYPE_FOLDER) {
            return folderView(position, convertView, parent);
        }
        return fileView(position, convertView, parent);
    }

    /**
     * One separator row. An opening separator names the folder and is indented
     * by its nesting depth; a closing separator is a bare line with no name.
     */
    private View folderView(int position, View convertView, ViewGroup parent) {
        FolderHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_folder_delimiter, parent, false);
            holder = new FolderHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (FolderHolder) convertView.getTag();
        }

        int height = discreteMode
                ? discreteRowHeightPx()
                : context.getResources().getDimensionPixelSize(R.dimen.folder_delimiter_height);
        ViewGroup.LayoutParams params = holder.root.getLayoutParams();
        if (params != null && params.height != height) {
            params.height = height;
            holder.root.setLayoutParams(params);
        }

        // Nesting is shown by indenting the whole separator (icon + name + rule),
        // so a sub-folder's line starts where its parent's name does.
        holder.root.setPaddingRelative(
                holder.basePaddingStart + rows.depthAt(position) * depthIndentPx,
                holder.root.getPaddingTop(),
                holder.basePaddingEnd,
                holder.root.getPaddingBottom());

        boolean closing = rows.isFolderEnd(position);
        String name = rows.folderNameAt(position);
        boolean named = !closing && name != null && name.length() > 0;
        holder.icon.setVisibility(named ? View.VISIBLE : View.INVISIBLE);
        holder.title.setVisibility(named ? View.VISIBLE : View.GONE);
        if (named) {
            holder.title.setText(name);
            holder.title.setContentDescription(context.getString(R.string.folder_label)
                    + ": " + name);
        } else {
            holder.title.setText(null);
            holder.title.setContentDescription(context.getString(R.string.folder_closed));
        }
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

        // Smooth mode restores the pre-discrete geometry: the row wraps its
        // content and can grow past the 60 dp minimum. Discrete mode fixes the
        // root to the measured one-line height so whole-row browsing stays
        // predictable without adding empty space between rows.
        ViewGroup.LayoutParams params = holder.root.getLayoutParams();
        int height = discreteMode ? discreteRowHeightPx() : ViewGroup.LayoutParams.WRAP_CONTENT;
        if (params != null && params.height != height) {
            params.height = height;
            holder.root.setLayoutParams(params);
        }
        // A single-line name is needed only when the row height is fixed.
        holder.title.setMaxLines(discreteMode ? 1 : 2);

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
        final ImageView icon;
        final TextView title;
        /** Un-indented start padding, measured once from the inflated layout. */
        final int basePaddingStart;
        final int basePaddingEnd;

        FolderHolder(View view) {
            root = view;
            icon = view.findViewById(R.id.folder_delimiter_icon);
            title = view.findViewById(R.id.folder_delimiter_name);
            basePaddingStart = view.getPaddingStart();
            basePaddingEnd = view.getPaddingEnd();
        }
    }

    private static final class Holder {
        final View root;
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final ImageButton delete;
        AudioFile file;

        Holder(View view) {
            root = view;
            icon = view.findViewById(R.id.row_icon);
            title = view.findViewById(R.id.row_title);
            subtitle = view.findViewById(R.id.row_subtitle);
            delete = view.findViewById(R.id.row_delete);
        }
    }
}

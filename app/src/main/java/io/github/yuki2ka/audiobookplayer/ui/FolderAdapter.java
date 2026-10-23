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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.github.yuki2ka.audiobookplayer.R;
import io.github.yuki2ka.audiobookplayer.model.AudioFile;
import io.github.yuki2ka.audiobookplayer.model.FolderNode;
import io.github.yuki2ka.audiobookplayer.util.TimeFormat;

/**
 * Folder explorer list: optional ".." row, sub-folders, then audio files.
 * Two view types, both recycled.
 */
public final class FolderAdapter extends BaseAdapter {

    public interface Callbacks {
        void onFolderClick(FolderNode folder);

        void onParentClick();

        void onFolderFileClick(AudioFile file);

        void onFolderFileDelete(AudioFile file);
    }

    private static final int TYPE_FOLDER = 0;
    private static final int TYPE_FILE = 1;

    private final Context context;
    private final LayoutInflater inflater;
    private final Callbacks callbacks;
    private final boolean showDelete;

    private FolderNode node;
    private boolean hasParent;
    private String parentName;
    private String playingPath;
    private Map<String, Long> positions = Collections.emptyMap();

    public FolderAdapter(Context context, Callbacks callbacks, boolean showDelete) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.callbacks = callbacks;
        this.showDelete = showDelete;
    }

    public void setNode(FolderNode node, boolean hasParent, String parentName) {
        this.node = node;
        this.hasParent = hasParent;
        this.parentName = parentName;
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

    public FolderNode node() {
        return node;
    }

    public int positionOfFile(String path) {
        if (node == null || path == null) {
            return -1;
        }
        List<AudioFile> files = node.files;
        for (int i = 0; i < files.size(); i++) {
            if (path.equals(files.get(i).path)) {
                return offset() + i;
            }
        }
        return -1;
    }

    private int offset() {
        return (hasParent ? 1 : 0) + (node == null ? 0 : node.folders.size());
    }

    @Override
    public int getCount() {
        if (node == null) {
            return 0;
        }
        return offset() + node.files.size();
    }

    @Override
    public Object getItem(int position) {
        int foldersEnd = hasParent ? 1 + node.folders.size() : node.folders.size();
        if (hasParent && position == 0) {
            return null;
        }
        if (position < foldersEnd) {
            return node.folders.get(position - (hasParent ? 1 : 0));
        }
        return node.files.get(position - foldersEnd);
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
        int foldersEnd = hasParent ? 1 + node.folders.size() : node.folders.size();
        return position < foldersEnd ? TYPE_FOLDER : TYPE_FILE;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (getItemViewType(position) == TYPE_FOLDER) {
            return folderView(position, convertView, parent);
        }
        return fileView(position, convertView, parent);
    }

    private View folderView(int position, View convertView, ViewGroup parent) {
        FolderHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_folder, parent, false);
            final FolderHolder created = new FolderHolder(convertView);
            convertView.setTag(created);
            created.root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (created.parentRow) {
                        callbacks.onParentClick();
                    } else if (created.folder != null) {
                        callbacks.onFolderClick(created.folder);
                    }
                }
            });
            holder = created;
        } else {
            holder = (FolderHolder) convertView.getTag();
        }

        if (hasParent && position == 0) {
            holder.parentRow = true;
            holder.folder = null;
            holder.title.setText("..");
            holder.subtitle.setText(parentName == null
                    ? context.getString(R.string.parent_folder)
                    : parentName);
            holder.icon.setImageResource(R.drawable.ic_arrow_up);
            holder.icon.setImageTintList(ColorStateList.valueOf(context.getColor(R.color.text_secondary)));
            holder.subtitle.setVisibility(View.VISIBLE);
        } else {
            FolderNode folder = node.folders.get(position - (hasParent ? 1 : 0));
            holder.parentRow = false;
            holder.folder = folder;
            holder.title.setText(folder.name);
            holder.subtitle.setText(context.getResources().getQuantityString(
                    R.plurals.folder_file_count, folder.totalFiles, folder.totalFiles));
            holder.subtitle.setVisibility(View.VISIBLE);
            holder.icon.setImageResource(R.drawable.ic_folder);
            holder.icon.setImageTintList(ColorStateList.valueOf(context.getColor(R.color.accent)));
        }
        return convertView;
    }

    private View fileView(int position, View convertView, ViewGroup parent) {
        FileHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_audio, parent, false);
            final FileHolder created = new FileHolder(convertView);
            convertView.setTag(created);
            created.root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (created.file != null) {
                        callbacks.onFolderFileClick(created.file);
                    }
                }
            });
            created.delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (created.file != null) {
                        callbacks.onFolderFileDelete(created.file);
                    }
                }
            });
            created.delete.setVisibility(showDelete ? View.VISIBLE : View.GONE);
            holder = created;
        } else {
            holder = (FileHolder) convertView.getTag();
        }

        AudioFile file = node.files.get(position - offset());
        holder.file = file;
        boolean playing = file.path.equals(playingPath);

        holder.title.setText(file.name);
        Long saved = positions.get(file.path);
        String detail = Formatter.formatShortFileSize(context, file.size);
        holder.subtitle.setText(saved != null && saved > 0
                ? detail + " \u00B7 " + context.getString(R.string.resume_at, TimeFormat.clock(saved))
                : detail);
        holder.title.setTextColor(context.getColor(playing ? R.color.accent : R.color.text_primary));
        holder.icon.setImageResource(playing ? R.drawable.ic_eq : R.drawable.ic_audio);
        holder.icon.setImageTintList(ColorStateList.valueOf(
                context.getColor(playing ? R.color.accent : R.color.text_secondary)));
        holder.root.setBackgroundResource(playing ? R.drawable.bg_row_playing : R.drawable.bg_row);
        holder.delete.setContentDescription(
                context.getString(R.string.delete_file) + ": " + file.name);
        return convertView;
    }

    private static final class FolderHolder {
        final View root;
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        FolderNode folder;
        boolean parentRow;

        FolderHolder(View view) {
            root = view;
            icon = view.findViewById(R.id.row_icon);
            title = view.findViewById(R.id.row_title);
            subtitle = view.findViewById(R.id.row_subtitle);
        }
    }

    private static final class FileHolder {
        final View root;
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final ImageButton delete;
        AudioFile file;

        FileHolder(View view) {
            root = view;
            icon = view.findViewById(R.id.row_icon);
            title = view.findViewById(R.id.row_title);
            subtitle = view.findViewById(R.id.row_subtitle);
            delete = view.findViewById(R.id.row_delete);
        }
    }
}

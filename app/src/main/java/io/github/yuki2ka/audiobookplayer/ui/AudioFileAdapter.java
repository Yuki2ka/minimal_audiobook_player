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
import io.github.yuki2ka.audiobookplayer.util.TimeFormat;

/**
 * Flat list of audio files (the "All files" page). Uses ListView recycling and
 * a plain ViewHolder; no bitmaps and no allocations beyond the bind itself.
 */
public final class AudioFileAdapter extends BaseAdapter {

    public interface Callbacks {
        void onFileClick(AudioFile file);

        void onDeleteClick(AudioFile file);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final Callbacks callbacks;
    private final boolean showParentName;

    private List<AudioFile> items = new ArrayList<>();
    private Map<String, Long> positions = Collections.emptyMap();
    private String playingPath;

    public AudioFileAdapter(Context context, Callbacks callbacks, boolean showParentName) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.callbacks = callbacks;
        this.showParentName = showParentName;
    }

    /** Keeps a reference to the live queue list (no copy, no extra memory). */
    public void setFiles(List<AudioFile> files) {
        this.items = files == null ? new ArrayList<AudioFile>() : files;
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

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public AudioFile getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
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

        AudioFile file = items.get(position);
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
        String detail;
        if (showParentName && file.parentName != null && file.parentName.length() > 0) {
            detail = file.parentName;
        } else {
            detail = Formatter.formatShortFileSize(context, file.size);
        }
        Long saved = positions.get(file.path);
        if (saved != null && saved > 0) {
            return detail + " \u00B7 "
                    + context.getString(R.string.resume_at, TimeFormat.clock(saved));
        }
        return detail;
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

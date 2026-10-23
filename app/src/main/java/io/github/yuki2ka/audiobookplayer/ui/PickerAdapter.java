package io.github.yuki2ka.audiobookplayer.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.github.yuki2ka.audiobookplayer.R;

/** Directory list of the folder picker. */
public final class PickerAdapter extends BaseAdapter {

    public interface Callbacks {
        void onFolderClick(File folder);
    }

    private final LayoutInflater inflater;
    private final Callbacks callbacks;
    private final List<File> folders = new ArrayList<>();

    public PickerAdapter(Context context, Callbacks callbacks) {
        this.inflater = LayoutInflater.from(context);
        this.callbacks = callbacks;
    }

    public void setFolders(List<File> newFolders) {
        folders.clear();
        if (newFolders != null) {
            folders.addAll(newFolders);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return folders.size();
    }

    @Override
    public File getItem(int position) {
        return folders.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_folder, parent, false);
            holder = new Holder(convertView);
            convertView.setTag(holder);
            holder.root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Holder h = (Holder) v.getTag();
                    if (h.folder != null) {
                        callbacks.onFolderClick(h.folder);
                    }
                }
            });
        } else {
            holder = (Holder) convertView.getTag();
        }
        File folder = folders.get(position);
        holder.folder = folder;
        holder.title.setText(folder.getName());
        holder.subtitle.setVisibility(View.GONE);
        holder.icon.setImageResource(R.drawable.ic_folder);
        holder.icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                holder.icon.getContext().getColor(R.color.accent)));
        holder.chevron.setVisibility(View.VISIBLE);
        return convertView;
    }

    private static final class Holder {
        final View root;
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final ImageView chevron;
        File folder;

        Holder(View view) {
            root = view;
            icon = view.findViewById(R.id.row_icon);
            title = view.findViewById(R.id.row_title);
            subtitle = view.findViewById(R.id.row_subtitle);
            chevron = view.findViewById(R.id.row_chevron);
        }
    }
}

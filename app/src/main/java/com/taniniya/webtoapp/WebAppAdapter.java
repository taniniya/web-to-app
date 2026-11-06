package com.taniniya.webtoapp;

import android.graphics.PorterDuff;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;


import java.util.List;

/** 作成済みWebアプリの一覧を表示するアダプタ。 */
public class WebAppAdapter extends RecyclerView.Adapter<WebAppAdapter.Holder> {

    public interface Listener {
        void onOpen(WebApp app);
        void onPin(WebApp app);
        void onEdit(WebApp app);
        void onDelete(WebApp app);
    }

    private final List<WebApp> apps;
    private final Listener listener;

    public WebAppAdapter(List<WebApp> apps, Listener listener) {
        this.apps = apps;
        this.listener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        WebApp app = apps.get(position);
        h.icon.getBackground().mutate().setColorFilter(app.color, PorterDuff.Mode.SRC_IN);
        h.title.setText(app.name);
        h.subtitle.setText(app.url + " · " + app.packageName);
        h.badge.setText(app.adBlock ? R.string.badge_adblock_on : R.string.badge_adblock_off);
        h.open.setOnClickListener(v -> listener.onOpen(app));
        h.pin.setOnClickListener(v -> listener.onPin(app));
        h.edit.setOnClickListener(v -> listener.onEdit(app));
        h.delete.setOnClickListener(v -> listener.onDelete(app));
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final View icon;
        final TextView title;
        final TextView subtitle;
        final TextView badge;
        final Button open, pin, edit, delete;

        Holder(View v) {
            super(v);
            icon = v.findViewById(R.id.icon);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
            badge = v.findViewById(R.id.badge);
            open = v.findViewById(R.id.btn_open);
            pin = v.findViewById(R.id.btn_pin);
            edit = v.findViewById(R.id.btn_edit);
            delete = v.findViewById(R.id.btn_delete);
        }
    }
}

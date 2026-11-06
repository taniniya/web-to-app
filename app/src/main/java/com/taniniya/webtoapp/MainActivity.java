package com.taniniya.webtoapp;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements WebAppAdapter.Listener {

    private WebAppStorage storage;
    private final List<WebApp> apps = new ArrayList<>();
    private WebAppAdapter adapter;
    private View emptyState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        storage = new WebAppStorage(this);

        RecyclerView recycler = findViewById(R.id.recycler);
        emptyState = findViewById(R.id.empty_state);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new WebAppAdapter(apps, this);
        recycler.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> showEditor(null));

        reload();
    }

    private void reload() {
        apps.clear();
        apps.addAll(storage.load());
        adapter.notifyDataSetChanged();
        emptyState.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onOpen(WebApp app) {
        Intent i = new Intent(this, WebViewerActivity.class);
        i.putExtra(WebViewerActivity.EXTRA_JSON, app.toJsonString());
        startActivity(i);
    }

    @Override
    public void onEdit(WebApp app) {
        showEditor(app);
    }

    @Override
    public void onDelete(WebApp app) {
        new AlertDialog.Builder(this)
                .setTitle(app.name)
                .setMessage(R.string.delete_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    storage.delete(app.id);
                    reload();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public void onPin(WebApp app) {
        ShortcutUtil.requestPin(this, app);
    }

    private void showEditor(final WebApp existing) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_app, null);
        EditText etUrl = view.findViewById(R.id.et_url);
        EditText etName = view.findViewById(R.id.et_name);
        EditText etPackage = view.findViewById(R.id.et_package);
        SwitchMaterial swAd = view.findViewById(R.id.sw_adblock);
        SwitchMaterial swFull = view.findViewById(R.id.sw_fullscreen);
        Spinner spColor = view.findViewById(R.id.sp_color);

        List<String> colorNames = new ArrayList<>();
        for (int id : ColorPalette.LABELS) colorNames.add(getString(id));
        spColor.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, colorNames));

        if (existing != null) {
            etUrl.setText(existing.url);
            etName.setText(existing.name);
            etPackage.setText(existing.packageName);
            swAd.setChecked(existing.adBlock);
            swFull.setChecked(existing.fullscreen);
            spColor.setSelection(ColorPalette.indexOfColor(existing.color));
        } else {
            etPackage.setText("com.taniniya.app");
            swAd.setChecked(true);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.new_app : R.string.edit_app)
                .setView(view)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = etUrl.getText().toString().trim();
            String name = etName.getText().toString().trim();
            String pkg = etPackage.getText().toString().trim().toLowerCase();
            if (url.isEmpty()) {
                etUrl.setError(getString(R.string.error_url_required));
                return;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            Uri uri = Uri.parse(url);
            if (uri.getHost() == null || !uri.getHost().contains(".")) {
                etUrl.setError(getString(R.string.error_url_invalid));
                return;
            }
            if (name.isEmpty()) name = uri.getHost();
            if (!pkg.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) {
                etPackage.setError(getString(R.string.error_package_invalid));
                return;
            }
            WebApp app = existing != null ? existing : new WebApp();
            if (existing == null) app.id = UUID.randomUUID().toString();
            app.url = url;
            app.name = name;
            app.packageName = pkg;
            app.adBlock = swAd.isChecked();
            app.fullscreen = swFull.isChecked();
            app.color = ColorPalette.COLORS[spColor.getSelectedItemPosition()];
            storage.upsert(app);
            reload();
            dialog.dismiss();
        });
    }
}

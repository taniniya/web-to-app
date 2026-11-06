package com.taniniya.webtoapp;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;

/**
 * WebToApp Studio v2.0 — ジェネレーター本体。
 * URL・アプリ名・パッケージ名・アイコン・設定を入れて「ビルド」すると、
 * 単独動作する APK をその場で生成・署名して書き出す。
 */
public class MainActivity extends AppCompatActivity {

    private TextInputEditText etUrl, etName, etPackage;
    private SwitchMaterial swAdblock, swFullscreen;
    private ImageView iconPreview;
    private Uri iconUri;
    private MaterialButton btnBuild;

    private final ActivityResultLauncher<String> iconPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    iconUri = uri;
                    iconPreview.setImageURI(uri);
                }
            });

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // Download 保存はベストエフォート。拒否されてもビルドは続行。
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etUrl = findViewById(R.id.et_url);
        etName = findViewById(R.id.et_name);
        etPackage = findViewById(R.id.et_package);
        swAdblock = findViewById(R.id.sw_adblock);
        swFullscreen = findViewById(R.id.sw_fullscreen);
        iconPreview = findViewById(R.id.icon_preview);
        btnBuild = findViewById(R.id.btn_build);

        swAdblock.setChecked(true);
        swFullscreen.setChecked(true);

        // アプリ名からパッケージ名を自動サジェスト (手動変更も可能)
        etName.addTextChangedListener(new TextWatcher() {
            private boolean selfChange = false;
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (selfChange) return;
                String name = s.toString();
                if (name.isEmpty()) return;
                String current = etPackage.getText() != null ? etPackage.getText().toString() : "";
                String suggested = "com.webtoapp." + toPackageSegment(name);
                if (!current.equals(suggested)) {
                    selfChange = true;
                    etPackage.setText(suggested);
                    selfChange = false;
                }
            }
        });

        findViewById(R.id.btn_paste).setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
                if (text != null) etUrl.setText(text.toString().trim());
            }
        });

        findViewById(R.id.btn_pick_icon).setOnClickListener(v -> iconPicker.launch("image/*"));

        btnBuild.setOnClickListener(v -> validateAndBuild());
    }

    private static String toPackageSegment(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toLowerCase().toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            if (sb.length() >= 20) break;
        }
        if (sb.length() == 0) sb.append("myapp");
        if (Character.isDigit(sb.charAt(0))) sb.insert(0, "a");
        return sb.toString();
    }

    private void validateAndBuild() {
        String url = etUrl.getText() == null ? "" : etUrl.getText().toString().trim();
        String name = etName.getText() == null ? "" : etName.getText().toString().trim();
        String pkg = etPackage.getText() == null ? "" : etPackage.getText().toString().trim();

        if (url.isEmpty()) { etUrl.setError(getString(R.string.error_url_required)); return; }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        if (name.isEmpty()) { etName.setError(getString(R.string.error_name_required)); return; }
        if (!pkg.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){1,}")) {
            etPackage.setError(getString(R.string.error_package_invalid));
            return;
        }

        if (android.os.Build.VERSION.SDK_INT <= 28 &&
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        ApkGenerator.Request req = new ApkGenerator.Request();
        req.url = url;
        req.appName = name;
        req.packageName = pkg;
        req.iconUri = iconUri;
        req.adBlock = swAdblock.isChecked();
        req.fullscreen = swFullscreen.isChecked();
        build(req);
    }

    private void build(ApkGenerator.Request req) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null);
        TextView tvStep = dialogView.findViewById(R.id.tv_step);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.building)
                .setView(dialogView)
                .setCancelable(false)
                .create();
        dialog.show();

        new Thread(() -> {
            try {
                ApkGenerator.Result result = ApkGenerator.generate(this, req,
                        step -> runOnUiThread(() -> tvStep.setText(step)));
                runOnUiThread(() -> {
                    dialog.dismiss();
                    showDone(result);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    dialog.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.build_failed)
                            .setMessage(String.valueOf(e))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            }
        }, "apk-build").start();
    }

    private void showDone(ApkGenerator.Result result) {
        String msg = getString(R.string.build_done_body,
                result.sizeBytes / 1024.0 / 102.0 >= 1.0
                        ? String.format("%.1f MB", result.sizeBytes / 1024.0 / 1024.0)
                        : String.format("%.0f KB", result.sizeBytes / 1024.0))
                + (result.downloadsFile != null
                    ? "\n" + getString(R.string.build_done_path, result.downloadsFile.getPath())
                    : "");
        new AlertDialog.Builder(this)
                .setTitle(R.string.build_done)
                .setMessage(msg)
                .setPositiveButton(R.string.install_now, (d, w) -> install(result.apkFile))
                .setNeutralButton(R.string.share, (d, w) -> share(result.apkFile))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void install(File apk) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.install_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void share(File apk) {
        Intent i = new Intent(Intent.ACTION_SEND);
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
        i.setType("application/vnd.android.package-archive");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, getString(R.string.share)));
    }
}

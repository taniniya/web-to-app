package com.taniniya.webtoapp;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WebToApp Studio v2.1 — ジェネレーター本体。
 *
 * v2.1 の変更点:
 *  - 「🌐 Webサイト (URL)」と「📁 ローカルファイル (HTML / CSS / JS / ZIP)」のモード切り替え
 *  - ローカルファイルは assets/web/ に同梱され、完全オフラインで動く単独APKになる
 *  - 広告ブロックは Beta 扱い・標準オフ
 *  - 詳細設定で minSdkVersion / targetSdkVersion / 画面の向き / 全画面 / 常時点灯 / ズーム
 */
public class MainActivity extends AppCompatActivity {

    private static final int[] MIN_SDK_VALUES = {21, 24, 26, 29, 31, 34};
    private static final int[] TARGET_SDK_VALUES = {34, 33, 31};
    private static final String[] ORIENTATION_VALUES = {"auto", "portrait", "landscape"};

    private TextInputEditText etUrl, etName, etPackage;
    private MaterialSwitch swAdblock, swFullscreen, swKeepScreenOn, swZoom;
    private Spinner spMinSdk, spTargetSdk, spOrientation;
    private TextView tvFiles, tvAdvancedArrow;
    private View groupUrl, groupFile, groupAdvanced;
    private ImageView iconPreview;
    private MaterialButton btnBuild;
    private MaterialButtonToggleGroup tgMode;

    private Uri iconUri;
    private boolean nameEdited = false;
    private boolean settingName = false;

    /** ローカルファイルモードで選ばれたファイル (assets/web/ に入る相対パス → 中身) */
    private Map<String, byte[]> localAssets = null;

    private final ActivityResultLauncher<String> iconPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    iconUri = uri;
                    iconPreview.setImageURI(uri);
                }
            });

    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty()) loadFiles(uris);
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
        swKeepScreenOn = findViewById(R.id.sw_keep_screen_on);
        swZoom = findViewById(R.id.sw_zoom);
        spMinSdk = findViewById(R.id.sp_min_sdk);
        spTargetSdk = findViewById(R.id.sp_target_sdk);
        spOrientation = findViewById(R.id.sp_orientation);
        tvFiles = findViewById(R.id.tv_files);
        tvAdvancedArrow = findViewById(R.id.tv_advanced_arrow);
        groupUrl = findViewById(R.id.group_url);
        groupFile = findViewById(R.id.group_file);
        groupAdvanced = findViewById(R.id.group_advanced);
        iconPreview = findViewById(R.id.icon_preview);
        btnBuild = findViewById(R.id.btn_build);
        tgMode = findViewById(R.id.tg_mode);

        // ---- 初期値: 広告ブロックは標準オフ (Beta) ----
        swAdblock.setChecked(false);
        swFullscreen.setChecked(true);
        swKeepScreenOn.setChecked(true);
        swZoom.setChecked(true);

        setupSpinners();

        tgMode.check(R.id.btn_mode_url);
        tgMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            applyMode(checkedId == R.id.btn_mode_url);
        });

        findViewById(R.id.btn_paste).setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
                if (text != null) etUrl.setText(text.toString().trim());
            }
        });

        findViewById(R.id.btn_pick_icon).setOnClickListener(v -> iconPicker.launch("image/*"));
        findViewById(R.id.btn_pick_files).setOnClickListener(v -> filePicker.launch(new String[]{"*/*"}));

        findViewById(R.id.header_advanced).setOnClickListener(v -> {
            boolean show = groupAdvanced.getVisibility() != View.VISIBLE;
            groupAdvanced.setVisibility(show ? View.VISIBLE : View.GONE);
            tvAdvancedArrow.setText(show ? R.string.collapse : R.string.expand);
        });

        // アプリ名を手で変えたら、URLやファイル名からの自動補完はやめる
        etName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (settingName) return;
                nameEdited = true;
                String name = s.toString();
                if (name.isEmpty()) return;
                String current = textOf(etPackage);
                String suggested = "com.webtoapp." + WebAssets.toPackageSegment(name);
                if (!current.equals(suggested)) etPackage.setText(suggested);
            }
        });

        // URL からアプリ名を推測
        etUrl.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (nameEdited || settingName) return;
                String guess = WebAssets.suggestAppNameFromUrl(s.toString());
                if (!guess.isEmpty()) setAppName(guess);
            }
        });

        btnBuild.setOnClickListener(v -> validateAndBuild());
    }

    private void setupSpinners() {
        setSpinner(spMinSdk, R.array.min_sdk_labels, 0);
        setSpinner(spTargetSdk, R.array.target_sdk_labels, 0);
        setSpinner(spOrientation, R.array.orientation_labels, 0);
    }

    private void setSpinner(Spinner sp, int labelsRes, int selected) {
        ArrayAdapter<CharSequence> a = ArrayAdapter.createFromResource(
                this, labelsRes, android.R.layout.simple_spinner_item);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        sp.setSelection(selected, false);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {}
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void applyMode(boolean urlMode) {
        groupUrl.setVisibility(urlMode ? View.VISIBLE : View.GONE);
        groupFile.setVisibility(urlMode ? View.GONE : View.VISIBLE);
        if (nameEdited) return;
        String guess = urlMode
                ? WebAssets.suggestAppNameFromUrl(textOf(etUrl))
                : (localAssets != null && localAssets.containsKey("index.html")
                        ? WebAssets.suggestAppNameFromHtml(localAssets.get("index.html")) : "");
        if (!guess.isEmpty()) setAppName(guess);
    }

    private void setAppName(String name) {
        settingName = true;
        etName.setText(name);
        etPackage.setText("com.webtoapp." + WebAssets.toPackageSegment(name));
        settingName = false;
    }

    private static String textOf(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    // ======================================================================
    // ローカルファイルの読み込み
    // ======================================================================

    private void loadFiles(List<Uri> uris) {
        tvFiles.setText(R.string.files_loading);
        new Thread(() -> {
            Map<String, byte[]> collected = new LinkedHashMap<>();
            long total = 0;
            String error = null;
            try {
                for (Uri uri : uris) {
                    String name = displayName(uri);
                    if (name == null || name.isEmpty()) name = "file" + (collected.size() + 1);
                    byte[] data = readAll(uri);
                    if (data == null) continue;
                    total += data.length;
                    if (total > WebAssets.MAX_BYTES) {
                        error = "ファイルが大きすぎます（合計64MBまで）";
                        break;
                    }
                    if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                        Map<String, byte[]> entries = WebAssets.unzip(data);
                        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                            if (collected.size() >= WebAssets.MAX_FILES) break;
                            collected.put(e.getKey(), e.getValue());
                        }
                    } else {
                        if (collected.size() >= WebAssets.MAX_FILES) break;
                        collected.put(name, data);
                    }
                }
            } catch (Exception e) {
                error = String.valueOf(e.getMessage() == null ? e : e.getMessage());
            }

            Map<String, byte[]> assets = WebAssets.stripCommonRoot(collected);
            String firstHtml = null;
            for (String key : assets.keySet()) {
                if (WebAssets.isHtml(key)) { firstHtml = key; break; }
            }
            String indexName = WebAssets.ensureIndex(assets);
            String guess = indexName == null ? "" : WebAssets.suggestAppNameFromHtml(assets.get(indexName));
            if (guess.isEmpty() && firstHtml != null) {
                guess = WebAssets.suggestAppNameFromFileName(firstHtml);
            }
            final String titleGuess = guess;
            String summary = WebAssets.summarize(assets);
            final String err = error;
            final int count = assets.size();
            final boolean ok = indexName != null;

            runOnUiThread(() -> {
                if (err != null) {
                    tvFiles.setText("⚠ " + err);
                    return;
                }
                if (!ok) {
                    localAssets = null;
                    tvFiles.setText(R.string.no_files_selected);
                    Toast.makeText(this, R.string.error_files_required, Toast.LENGTH_LONG).show();
                    return;
                }
                localAssets = assets;
                tvFiles.setText(getString(R.string.files_selected, count, summary));
                if (!nameEdited && !titleGuess.isEmpty()) setAppName(titleGuess);
            });
        }, "file-load").start();
    }

    private String displayName(Uri uri) {
        String name = null;
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name == null) {
            String last = uri.getLastPathSegment();
            if (last != null) {
                int slash = last.lastIndexOf('/');
                name = slash >= 0 ? last.substring(slash + 1) : last;
            }
        }
        return name;
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > WebAssets.MAX_BYTES) throw new IllegalStateException("ファイルが大きすぎます");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    // ======================================================================
    // ビルド
    // ======================================================================

    private void validateAndBuild() {
        boolean urlMode = tgMode.getCheckedButtonId() != R.id.btn_mode_file;

        String name = textOf(etName);
        String pkg = textOf(etPackage);

        if (name.isEmpty()) { etName.setError(getString(R.string.error_name_required)); return; }
        if (!pkg.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){1,}")) {
            etPackage.setError(getString(R.string.error_package_invalid));
            return;
        }

        ApkGenerator.Request req = new ApkGenerator.Request();
        req.appName = name;
        req.packageName = pkg;
        req.iconUri = iconUri;
        req.adBlock = swAdblock.isChecked();
        req.fullscreen = swFullscreen.isChecked();
        req.keepScreenOn = swKeepScreenOn.isChecked();
        req.zoomEnabled = swZoom.isChecked();
        req.minSdk = MIN_SDK_VALUES[Math.max(0, spMinSdk.getSelectedItemPosition())];
        req.targetSdk = TARGET_SDK_VALUES[Math.max(0, spTargetSdk.getSelectedItemPosition())];
        req.orientation = ORIENTATION_VALUES[Math.max(0, spOrientation.getSelectedItemPosition())];

        if (urlMode) {
            String url = textOf(etUrl);
            if (url.isEmpty()) { etUrl.setError(getString(R.string.error_url_required)); return; }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            req.url = url;
        } else {
            if (localAssets == null || localAssets.isEmpty()) {
                Toast.makeText(this, R.string.error_files_required, Toast.LENGTH_LONG).show();
                return;
            }
            req.localAssets = localAssets;
        }

        if (android.os.Build.VERSION.SDK_INT <= 28 &&
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

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
            } catch (Throwable e) {
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
        String size = result.sizeBytes >= 1024 * 1024
                ? String.format(Locale.ROOT, "%.1f MB", result.sizeBytes / 1024.0 / 1024.0)
                : String.format(Locale.ROOT, "%.0f KB", result.sizeBytes / 1024.0);
        String msg = getString(R.string.build_done_body, size)
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

package com.taniniya.webtoapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * APK 生成の Android 側ラッパー (v2.1)。
 *
 * 実際の組み立て・署名は Android 非依存の {@link ApkCore} が行う
 * (端末でも PC のテストでもまったく同じコードが動く)。
 * ここでは assets の読み出し、選択画像のデコードとリサイズ、
 * 生成物の保存 (アプリ領域 / Download) を担当する。
 */
public final class ApkGenerator {

    /** ビルドリクエスト */
    public static class Request {
        /** URL モード: https://… ／ ローカルモード: null で localAssets を使用 */
        public String url;
        public String appName;
        public String packageName;
        public Uri iconUri;      // null 可 (デフォルトアイコン)

        /** 広告ブロック (Beta / 既定 OFF) */
        public boolean adBlock = false;
        public boolean fullscreen = true;
        public boolean keepScreenOn = true;
        public boolean zoomEnabled = true;
        /** auto | portrait | landscape */
        public String orientation = "auto";

        public int minSdk = 21;
        public int targetSdk = 34;

        /** ローカルファイルモード: assets/web/ に格納する (相対パス → 中身) */
        public Map<String, byte[]> localAssets;

        public boolean isLocalMode() {
            return localAssets != null && !localAssets.isEmpty();
        }
    }

    /** 結果 */
    public static class Result {
        public File apkFile;         // 署名済み APK (app 外部ファイル領域)
        public File downloadsFile;   // Download/WebToApp/ に保存できた場合 (null 可)
        public long sizeBytes;
    }

    private static final int[] ICON_SIZES = {48, 72, 96, 144, 192};

    private ApkGenerator() {}

    /** APK を生成し、結果を返す。 */
    public static Result generate(Context ctx, Request req, ApkCore.Progress progress) throws Exception {
        if (progress != null) progress.onStep("テンプレートを読み込み中…");

        ApkCore.Spec spec = new ApkCore.Spec();
        spec.templateApk = readAsset(ctx, "template.apk");
        spec.appName = req.appName;
        spec.packageName = req.packageName;
        spec.adBlock = req.adBlock;
        spec.fullscreen = req.fullscreen;
        spec.keepScreenOn = req.keepScreenOn;
        spec.zoomEnabled = req.zoomEnabled;
        spec.orientation = req.orientation;
        spec.minSdk = req.minSdk;
        spec.targetSdk = req.targetSdk;

        // ローカルファイルモード (HTML / CSS / JS / ZIP) は assets/web/ に同梱して
        // file:///android_asset/web/index.html を起点に完全オフラインで動かす
        if (req.isLocalMode()) {
            spec.webAssets = new LinkedHashMap<>(req.localAssets);
            spec.url = "file:///android_asset/web/index.html";
        } else {
            spec.url = req.url;
        }

        if (req.iconUri != null) {
            if (progress != null) progress.onStep("アイコンを準備中…");
            try {
                spec.icons = scaleIcon(ctx, req.iconUri);
            } catch (Exception ignored) {
                // アイコンが読めなくても既定アイコンでビルドを続ける
            }
        }

        spec.keystore = ctx.getAssets().open("debug.keystore");
        spec.keystorePassword = "android".toCharArray();
        spec.keyAlias = "androiddebugkey";
        spec.keyPassword = "android".toCharArray();

        byte[] signed = ApkCore.build(spec, progress);

        if (progress != null) progress.onStep("保存中…");
        File appDir = new File(ctx.getExternalFilesDir(null), "WebToApp");
        if (!appDir.exists()) appDir.mkdirs();
        String safeName = sanitizeFileName(req.appName);
        File outFile = new File(appDir, safeName + ".apk");
        if (outFile.exists()) outFile.delete();
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(signed);
        }

        Result r = new Result();
        r.apkFile = outFile;
        r.sizeBytes = outFile.length();
        r.downloadsFile = saveToDownloads(ctx, outFile, safeName);
        return r;
    }

    // ======================================================================
    // 入力の読み出し
    // ======================================================================

    private static byte[] readAsset(Context ctx, String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** 選択画像を 48〜192px の 5 解像度 PNG に変換する。 */
    static Map<Integer, byte[]> scaleIcon(Context ctx, Uri uri) throws IOException {
        Bitmap src;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            src = BitmapFactory.decodeStream(in);
        }
        if (src == null) throw new IOException("画像を読み込めません");
        Map<Integer, byte[]> out = new LinkedHashMap<>();
        try {
            int w = src.getWidth(), h = src.getHeight();
            int side = Math.min(w, h);
            int sx = (w - side) / 2, sy = (h - side) / 2;
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            for (int px : ICON_SIZES) {
                Bitmap dst = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(dst);
                c.drawBitmap(src, new android.graphics.Rect(sx, sy, sx + side, sy + side),
                        new android.graphics.Rect(0, 0, px, px), p);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                dst.compress(Bitmap.CompressFormat.PNG, 100, bos);
                dst.recycle();
                out.put(px, bos.toByteArray());
            }
        } finally {
            src.recycle();
        }
        return out;
    }

    // ======================================================================
    // 保存
    // ======================================================================

    private static File saveToDownloads(Context ctx, File apk, String appName) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, appName + ".apk");
                values.put(android.provider.MediaStore.Downloads.MIME_TYPE,
                        "application/vnd.android.package-archive");
                values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/WebToApp");
                android.net.Uri uri = ctx.getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return null;
                try (java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                     java.io.FileInputStream in = new java.io.FileInputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                return new File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "WebToApp/" + appName + ".apk");
            } else {
                File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "WebToApp");
                if (!dir.exists()) dir.mkdirs();
                File dst = new File(dir, appName + ".apk");
                copy(apk, dst);
                return dst;
            }
        } catch (Exception e) {
            return null; // Download への保存はベストエフォート
        }
    }

    private static String sanitizeFileName(String name) {
        String s = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (s.isEmpty()) s = "app";
        return s;
    }

    private static void copy(File src, File dst) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}

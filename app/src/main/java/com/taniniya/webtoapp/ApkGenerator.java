package com.taniniya.webtoapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;

import com.android.apksig.ApkSigner;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * APK 生成エンジン。
 *
 * assets/template.apk (単独動作する超軽量 WebView テンプレート) を読み込み、
 *   1. AXML StringPool 書換 (パッケージ名 / アプリ名)
 *   2. アイコン PNG 差し替え (mdpi〜xxxhdpi)
 *   3. assets/app_config.json 注入
 *   4. アライメント維持つき ZIP 再構築 (ApkWriter)
 *   5. apksig による v1 + v2 (+ v3) 署名
 * を行い、独立動作する単独 APK を出力する。
 *
 * aapt2 / aapt / zipalign 等の外部ツールは一切使わない Pure Java 実装。
 * リファレンス実装: template-app/tools/make_template.py, end_to_end_test.py
 */
public final class ApkGenerator {

    /** テンプレート内のプレースホルダ (AXML StringPool で書き換える) */
    static final String TEMPLATE_PACKAGE = "com.webtoapp.standalone.template";
    static final String TEMPLATE_LABEL = "STANDALONE_TEMPLATE_APP_LABEL";

    /** 進捗コールバック */
    public interface Progress {
        void onStep(String message);
    }

    /** ビルドリクエスト */
    public static class Request {
        public String url;
        public String appName;
        public String packageName;
        public Uri iconUri;      // null 可 (デフォルトアイコン)
        public boolean adBlock = true;
        public boolean fullscreen = true;
    }

    /** 結果 */
    public static class Result {
        public File apkFile;         // 署名済み APK (app 外部ファイル領域)
        public File downloadsFile;   // Download/WebToApp/ に保存できた場合 (null 可)
        public long sizeBytes;
    }

    private static final String[][] ICON_DENSITIES = {
            {"mipmap-mdpi", "48"},
            {"mipmap-hdpi", "72"},
            {"mipmap-xhdpi", "96"},
            {"mipmap-xxhdpi", "144"},
            {"mipmap-xxxhdpi", "192"},
    };

    private ApkGenerator() {}

    /** APK を生成し、結果を返す。 */
    public static Result generate(Context ctx, Request req, Progress progress) throws Exception {
        if (progress != null) progress.onStep("テンプレートを読み込み中…");

        // ---- 1. テンプレート読み込み ----
        List<Entry> entries = new ArrayList<>();
        try (InputStream in = ctx.getAssets().open("template.apk");
             ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().startsWith("META-INF/")) continue; // 既存署名は必ず除去
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                entries.add(new Entry(e.getName(), out.toByteArray(), e.getMethod()));
            }
        }

        // ---- 2. AXML StringPool 書換 ----
        if (progress != null) progress.onStep("パッケージ名とアプリ名を書き換え中…");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.name.equals("AndroidManifest.xml")) {
                e.data = rewriteStringPool(e.data,
                        TEMPLATE_PACKAGE, req.packageName,
                        TEMPLATE_LABEL, req.appName);
            }
        }

        // ---- 3. アイコン差し替え ----
        Bitmap src = null;
        if (req.iconUri != null) {
            try {
                src = android.graphics.BitmapFactory.decodeStream(
                        ctx.getContentResolver().openInputStream(req.iconUri));
            } catch (Exception ignored) {}
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            int dpi = iconDensity(e.name);
            if (dpi > 0) {
                byte[] png;
                if (src != null) {
                    png = scaleToPng(src, dpi);
                } else {
                    png = defaultIconPng(dpi); // テンプレート既定アイコンをそのまま使う
                }
                if (png != null) e.data = png;
            }
        }
        if (src != null) src.recycle();

        // ---- 4. app_config.json 注入 ----
        if (progress != null) progress.onStep("設定を注入中…");
        JSONObject cfg = new JSONObject();
        cfg.put("url", req.url);
        cfg.put("appName", req.appName);
        cfg.put("adBlock", req.adBlock);
        cfg.put("fullscreen", req.fullscreen);
        boolean cfgReplaced = false;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name.equals("assets/app_config.json")) {
                entries.get(i).data = cfg.toString().getBytes("UTF-8");
                cfgReplaced = true;
            }
        }
        if (!cfgReplaced) {
            entries.add(new Entry("assets/app_config.json", cfg.toString().getBytes("UTF-8"),
                    ZipEntry.DEFLATED));
        }

        // ---- 5. ZIP 再構築 (resources.arsc は無圧縮 & 4バイトアライメント維持) ----
        if (progress != null) progress.onStep("APK を組み立て中…");
        File cacheDir = ctx.getCacheDir();
        File unsigned = new File(cacheDir, "unsigned.apk");
        byte[] apk = new ApkWriter().write(entries);
        try (FileOutputStream fos = new FileOutputStream(unsigned)) {
            fos.write(apk);
        }

        // ---- 6. 署名 (v1 + v2 + v3) ----
        if (progress != null) progress.onStep("APK に署名中…");
        File signed = new File(cacheDir, "signed.apk");
        if (signed.exists()) signed.delete();
        signApk(ctx, unsigned, signed);
        unsigned.delete();

        // ---- 7. 保存 ----
        if (progress != null) progress.onStep("保存中…");
        File appDir = new File(ctx.getExternalFilesDir(null), "WebToApp");
        if (!appDir.exists()) appDir.mkdirs();
        String safeName = sanitizeFileName(req.appName);
        File outFile = new File(appDir, safeName + ".apk");
        if (outFile.exists()) outFile.delete();
        copy(signed, outFile);
        signed.delete();

        Result r = new Result();
        r.apkFile = outFile;
        r.sizeBytes = outFile.length();
        r.downloadsFile = saveToDownloads(ctx, outFile, safeName);
        return r;
    }

    // ======================================================================
    // 署名 (apksig)
    // ======================================================================

    private static void signApk(Context ctx, File in, File out) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = ctx.getAssets().open("debug.keystore")) {
            ks.load(is, "android".toCharArray());
        }
        String alias = "androiddebugkey";
        PrivateKey privateKey = (PrivateKey) ks.getKey(alias, "android".toCharArray());
        Certificate cert = ks.getCertificate(alias);

        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "webtoapp", privateKey, Collections.singletonList((X509Certificate) cert)).build();
        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setOtherSignersSignaturesPreserved(false)
                .build();
        signer.sign();
    }

    // ======================================================================
    // AXML StringPool 書換
    // ======================================================================

    /**
     * バイナリ XML (AXML) の先頭 StringPool チャンクを走査し、
     * oldPackage→newPackage / oldLabel→newLabel を置換して再エンコードする。
     * チャンクサイズ・文字列オフセットは再計算する。
     */
    static byte[] rewriteStringPool(byte[] axml, String oldPackage, String newPackage,
                                    String oldLabel, String newLabel) {
        ByteBuffer b = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        int fileType = b.getShort(0) & 0xFFFF;
        int headerSize = b.getShort(2) & 0xFFFF;
        int totalSize = b.getInt(4);

        int spOffset = 8; // manifest チャンクヘッダの直後が StringPool
        int spType = b.getShort(spOffset) & 0xFFFF;              // 0x0001 RES_STRING_POOL_TYPE
        int spHeaderSize = b.getShort(spOffset + 2) & 0xFFFF;    // 28
        int spSize = b.getInt(spOffset + 4);
        int strCount = b.getInt(spOffset + 8);
        int styleCount = b.getInt(spOffset + 12);
        int flags = b.getInt(spOffset + 16);
        int strStart = b.getInt(spOffset + 20);
        int styleStart = b.getInt(spOffset + 24);

        boolean isUtf8 = (flags & 0x100) != 0;
        int dataStart = spOffset + strStart;

        // 文字列を読む
        String[] strings = new String[strCount];
        for (int i = 0; i < strCount; i++) {
            int off = b.getInt(spOffset + 28 + 4 * i);
            int s0 = dataStart + off;
            if (isUtf8) {
                int u16len = axml[s0] & 0xFF;
                int u8len = axml[s0 + 1] & 0xFF;
                // u16len が 0x80 以上の拡張エンコードには未対応 (テンプレートでは発生しない)
                strings[i] = new String(axml, s0 + 2, u8len, java.nio.charset.StandardCharsets.UTF_8);
            } else {
                int u16len = b.getShort(s0) & 0xFFFF;
                strings[i] = new String(axml, s0 + 2, u16len * 2,
                        java.nio.charset.StandardCharsets.UTF_16LE);
            }
        }

        // 置換
        for (int i = 0; i < strCount; i++) {
            if (oldPackage != null && oldPackage.equals(strings[i])) strings[i] = newPackage;
            else if (oldLabel != null && oldLabel.equals(strings[i])) strings[i] = newLabel;
        }

        // 再エンコード (常に UTF-16 で出力する)
        ByteArrayOutputStream newStr = new ByteArrayOutputStream();
        int[] offsets = new int[strCount];
        for (int i = 0; i < strCount; i++) {
            offsets[i] = newStr.size();
            byte[] enc = strings[i].getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
            newStr.write(strings[i].length() & 0xFF);
            newStr.write((strings[i].length() >> 8) & 0xFF);
            newStr.write(enc, 0, enc.length);
            newStr.write(0);
            newStr.write(0); // ヌル終端
        }
        byte[] strBytes = newStr.toByteArray();
        int pad = (4 - (strBytes.length % 4)) % 4;

        int newStrStart = 28 + 4 * strCount + 4 * styleCount;
        int newSpSize = newStrStart + strBytes.length + pad;

        ByteBuffer out = ByteBuffer.allocate(8 + newSpSize + (totalSize - (8 + spSize)))
                .order(ByteOrder.LITTLE_ENDIAN);
        // manifest チャンクヘッダ
        out.putShort((short) fileType);
        out.putShort((short) headerSize);
        out.putInt(8 + newSpSize + (totalSize - (8 + spSize)));
        // StringPool ヘッダ
        out.putShort((short) spType);
        out.putShort((short) spHeaderSize);
        out.putInt(newSpSize);
        out.putInt(strCount);
        out.putInt(styleCount);
        out.putInt(flags & ~0x100); // UTF-16 を明示
        out.putInt(newStrStart);
        out.putInt(styleStart);     // styles なしテンプレートでは 0
        for (int i = 0; i < strCount; i++) out.putInt(offsets[i]);
        out.put(strBytes);
        for (int i = 0; i < pad; i++) out.put((byte) 0);
        // 残りのチャンク (resource map + start/end namespace/tag 群)
        out.put(Arrays.copyOfRange(axml, 8 + spSize, totalSize));

        byte[] result = new byte[out.position()];
        System.arraycopy(out.array(), 0, result, 0, result.length);
        return result;
    }

    // ======================================================================
    // アイコン
    // ======================================================================

    /** パスがテンプレートのランチャーアイコンなら密度 px を返す (mdpi=48...)。 */
    private static int iconDensity(String name) {
        if (!name.startsWith("res/mipmap-") || !name.endsWith("ic_launcher.png")) return -1;
        String[] dirs = {"mipmap-xxxhdpi", "mipmap-xxhdpi", "mipmap-xhdpi", "mipmap-hdpi", "mipmap-mdpi"};
        int[] px = {192, 144, 96, 72, 48};
        for (int i = 0; i < dirs.length; i++) {
            if (name.contains(dirs[i])) return px[i];
        }
        return -1;
    }

    private static byte[] scaleToPng(Bitmap src, int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        // 正方形にクロップしてスケール (中心基準)
        int w = src.getWidth(), h = src.getHeight();
        int side = Math.min(w, h);
        c.drawBitmap(src, new android.graphics.Rect((w - side) / 2, (h - side) / 2,
                        (w + side) / 2, (h + side) / 2),
                new android.graphics.Rect(0, 0, px, px), p);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.PNG, 100, bos);
        out.recycle();
        return bos.toByteArray();
    }

    private static byte[] defaultIconPng(int px) { return null; } // null ならテンプレートのまま

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
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
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

    // ======================================================================
    // ApkWriter — アライメント制御つき最小 ZIP ライタ
    // ======================================================================

    /** テンプレートの 1 エントリ */
    static class Entry {
        final String name;
        byte[] data;
        final int method; // ZipEntry.STORED / DEFLATED

        Entry(String name, byte[] data, int method) {
            this.name = name;
            this.data = data;
            this.method = method;
        }
    }

    /**
     * zipalign と同じアライメント (無圧縮エントリのデータ開始を 4 バイト境界に揃える) を
     * 実装した最小 ZIP ライタ。targetSdk>=30 では resources.arsc が
     * 「無圧縮 & 4バイト境界」であることがインストール時に要求されるため必須。
     */
    static class ApkWriter {

        byte[] write(List<Entry> entries) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            List<byte[]> central = new ArrayList<>();
            long offset = 0;

            for (Entry e : entries) {
                int method = (e.method == ZipEntry.STORED) ? ZipEntry.STORED : ZipEntry.DEFLATED;
                byte[] nameB = e.name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                CRC32 crc = new CRC32();
                crc.update(e.data);

                byte[] payload;
                if (method == ZipEntry.DEFLATED) {
                    Deflater def = new Deflater(9, true); // nowrap = raw deflate (ZIP)
                    def.setInput(e.data);
                    def.finish();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    while (!def.finished()) {
                        int n = def.deflate(buf);
                        bos.write(buf, 0, n);
                    }
                    def.end();
                    payload = bos.toByteArray();
                } else {
                    payload = e.data;
                }

                // extra field (パディング)
                byte[] extra = new byte[0];
                if (method == ZipEntry.STORED) {
                    long dataStart = offset + 30 + nameB.length;
                    int pad = (int) ((4 - (dataStart % 4)) % 4);
                    if (pad > 0) {
                        if (pad < 4) pad += 4; // extra ヘッダ 4 バイト分を確保
                        extra = new byte[pad];
                        extra[0] = (byte) 0x35; // id 0xD935 (zipalign パディング)
                        extra[1] = (byte) 0xD9;
                        extra[2] = (byte) ((pad - 4) & 0xFF);
                        extra[3] = (byte) (((pad - 4) >> 8) & 0xFF);
                        // 残りは 0 埋め
                    }
                }

                ByteBuffer lh = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
                lh.putInt(0x04034B50);       // local file header signature
                lh.putShort((short) 20);     // version needed
                lh.putShort((short) 0);      // flags
                lh.putShort((short) method);
                lh.putShort((short) 0);      // mod time
                lh.putShort((short) 0);      // mod date
                lh.putInt((int) crc.getValue());
                lh.putInt(payload.length);   // compressed size
                lh.putInt(e.data.length);    // uncompressed size
                lh.putShort((short) nameB.length);
                lh.putShort((short) extra.length);

                long entryStart = offset;
                body.write(lh.array(), 0, 30);
                body.write(nameB, 0, nameB.length);
                body.write(extra, 0, extra.length);
                body.write(payload, 0, payload.length);
                offset = entryStart + 30 + nameB.length + extra.length + payload.length;

                ByteBuffer cd = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
                cd.putInt(0x02014B50);           // central directory signature
                cd.putShort((short) 20);         // version made by
                cd.putShort((short) 20);         // version needed
                cd.putShort((short) 0);          // flags
                cd.putShort((short) method);
                cd.putShort((short) 0);          // mod time
                cd.putShort((short) 0);          // mod date
                cd.putInt((int) crc.getValue());
                cd.putInt(payload.length);
                cd.putInt(e.data.length);
                cd.putShort((short) nameB.length);
                cd.putShort((short) extra.length);
                cd.putShort((short) 0);          // comment len
                cd.putShort((short) 0);          // disk number start
                cd.putShort((short) 0);          // internal attrs
                cd.putInt(0);                    // external attrs
                cd.putInt((int) entryStart);

                byte[] cdh = new byte[46 + nameB.length + extra.length];
                System.arraycopy(cd.array(), 0, cdh, 0, 46);
                System.arraycopy(nameB, 0, cdh, 46, nameB.length);
                System.arraycopy(extra, 0, cdh, 46 + nameB.length, extra.length);
                central.add(cdh);
            }

            long cdStart = offset;
            for (byte[] cdh : central) {
                body.write(cdh, 0, cdh.length);
                offset += cdh.length;
            }
            long cdSize = offset - cdStart;

            ByteBuffer eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
            eocd.putInt(0x06054B50);
            eocd.putShort((short) 0);
            eocd.putShort((short) 0);
            eocd.putShort((short) central.size());
            eocd.putShort((short) central.size());
            eocd.putInt((int) cdSize);
            eocd.putInt((int) cdStart);
            eocd.putShort((short) 0);

            byte[] result = new byte[(int) (offset + 22)];
            System.arraycopy(body.toByteArray(), 0, result, 0, (int) offset);
            System.arraycopy(eocd.array(), 0, result, (int) offset, 22);
            return result;
        }
    }
}

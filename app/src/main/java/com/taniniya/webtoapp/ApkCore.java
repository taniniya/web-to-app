package com.taniniya.webtoapp;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * APK 生成の中核 (v2.1)。Android API に一切依存しない Pure Java 実装。
 *
 * 入力: テンプレート APK (単独動作する WebView アプリ)
 * 処理:
 *   1. AndroidManifest.xml (AXML) の StringPool 書換 … パッケージ名 / アプリ名
 *   2. AndroidManifest.xml の &lt;uses-sdk&gt; 属性値書換 … minSdkVersion / targetSdkVersion
 *   3. アイコン PNG 差し替え (mdpi〜xxxhdpi)
 *   4. assets/app_config.json 注入 (URL / 全画面 / 常時点灯 / ズーム / 向き / 広告ブロック)
 *   5. assets/web/** 注入 (HTML / CSS / JS を完全オフラインで同梱)
 *   6. アライメント維持つき ZIP 再構築 (ApkWriter)
 *   7. apksig による v1 + v2 + v3 署名
 *
 * Android 側 (ApkGenerator) もデスクトップ側のテストも同じこのクラスを通るため、
 * 端末で動くコードをそのまま PC 上で検証できる。
 */
public final class ApkCore {

    /** テンプレート内のプレースホルダ (AXML StringPool で書き換える) */
    public static final String TEMPLATE_PACKAGE = "com.webtoapp.standalone.template";
    public static final String TEMPLATE_LABEL = "STANDALONE_TEMPLATE_APP_LABEL";

    /** 進捗コールバック */
    public interface Progress {
        void onStep(String message);
    }

    /** 生成パラメータ */
    public static class Spec {
        /** テンプレート APK の中身 */
        public byte[] templateApk;

        /** http(s) URL、または file:///android_asset/web/index.html */
        public String url = "https://example.com/";
        public String appName = "MyApp";
        public String packageName = "com.example.myapp";

        /** 広告ブロック (Beta / 既定 OFF) */
        public boolean adBlock = false;
        public boolean fullscreen = true;
        public boolean keepScreenOn = true;
        public boolean zoomEnabled = true;
        /** auto | portrait | landscape */
        public String orientation = "auto";

        public int minSdk = 21;
        public int targetSdk = 34;

        /** assets/web/ 配下にそのまま格納するファイル (相対パス → 中身) */
        public Map<String, byte[]> webAssets;

        /** アイコン (px → PNG バイト列)。空/null ならテンプレート既定アイコン */
        public Map<Integer, byte[]> icons;

        public InputStream keystore;
        public char[] keystorePassword = "android".toCharArray();
        public String keyAlias = "androiddebugkey";
        public char[] keyPassword = "android".toCharArray();

        /**
         * 署名時に使う一時ファイルの置き場。
         * Android では java.io.tmpdir が書けない端末があるため、呼び出し側が
         * アプリのキャッシュ領域を渡す。null なら java.io.tmpdir。
         */
        public File tempDir;
    }

    private static final String[][] ICON_DIRS = {
            // {ディレクトリ, px}
            {"mipmap-mdpi", "48"},
            {"mipmap-hdpi", "72"},
            {"mipmap-xhdpi", "96"},
            {"mipmap-xxhdpi", "144"},
            {"mipmap-xxxhdpi", "192"},
    };

    private ApkCore() {}

    // ======================================================================
    // 本体
    // ======================================================================

    /** 署名済み APK のバイト列を返す。 */
    public static byte[] build(Spec spec, Progress progress) throws Exception {
        if (spec.templateApk == null || spec.templateApk.length == 0) {
            throw new IllegalArgumentException("テンプレート APK が空です");
        }
        if (spec.minSdk < 21) spec.minSdk = 21;
        if (spec.targetSdk < spec.minSdk) spec.targetSdk = spec.minSdk;
        // ローカルファイルを同梱する場合は必ず assets の index.html を起点にする
        if (spec.webAssets != null && !spec.webAssets.isEmpty()
                && (spec.url == null || !spec.url.startsWith("file:///android_asset/"))) {
            spec.url = "file:///android_asset/web/index.html";
        }

        if (progress != null) progress.onStep("テンプレートを読み込み中…");
        List<Entry> entries = readEntries(spec.templateApk);

        // ---- 1-2. AndroidManifest.xml 書換 ----
        if (progress != null) progress.onStep("マニフェストを書き換え中…");
        boolean manifestFound = false;
        for (Entry e : entries) {
            if (e.name.equals("AndroidManifest.xml")) {
                e.data = patchManifest(e.data, TEMPLATE_PACKAGE, spec.packageName,
                        TEMPLATE_LABEL, spec.appName, spec.minSdk, spec.targetSdk);
                manifestFound = true;
            }
        }
        if (!manifestFound) throw new IllegalStateException("テンプレートに AndroidManifest.xml がありません");

        // ---- 3. アイコン差し替え ----
        if (progress != null) progress.onStep("アイコンを反映中…");
        if (spec.icons != null && !spec.icons.isEmpty()) {
            for (Entry e : entries) {
                int px = iconDensity(e.name);
                if (px > 0) {
                    byte[] png = spec.icons.get(px);
                    if (png != null && png.length > 0) e.data = png;
                }
            }
        }

        // ---- 4. app_config.json ----
        if (progress != null) progress.onStep("設定を注入中…");
        byte[] cfg = buildConfigJson(spec).getBytes(StandardCharsets.UTF_8);
        boolean cfgReplaced = false;
        for (Entry e : entries) {
            if (e.name.equals("assets/app_config.json")) {
                e.data = cfg;
                cfgReplaced = true;
            }
        }
        if (!cfgReplaced) entries.add(new Entry("assets/app_config.json", cfg, ZipEntry.DEFLATED));

        // ---- 5. assets/web/** ----
        if (spec.webAssets != null && !spec.webAssets.isEmpty()) {
            if (progress != null) progress.onStep("HTML / CSS / JS を同梱中…");
            // 既存の assets/web/ は捨ててから入れる (再ビルド時の重複防止)
            java.util.Iterator<Entry> it = entries.iterator();
            while (it.hasNext()) {
                if (it.next().name.startsWith("assets/web/")) it.remove();
            }
            for (Map.Entry<String, byte[]> a : spec.webAssets.entrySet()) {
                String path = sanitizeAssetPath(a.getKey());
                if (path.isEmpty()) continue;
                entries.add(new Entry("assets/web/" + path, a.getValue(), ZipEntry.DEFLATED));
            }
        }

        // ---- 6. ZIP 再構築 ----
        if (progress != null) progress.onStep("APK を組み立て中…");
        byte[] unsigned = new ApkWriter().write(entries);

        // ---- 7. 署名 ----
        if (progress != null) progress.onStep("APK に署名中…");
        return sign(spec, unsigned);
    }

    // ======================================================================
    // app_config.json
    // ======================================================================

    static String buildConfigJson(Spec spec) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        jsonPair(sb, "url", spec.url);                       sb.append(',');
        jsonPair(sb, "appName", spec.appName);               sb.append(',');
        sb.append("\"adBlock\":").append(spec.adBlock).append(',');
        sb.append("\"fullscreen\":").append(spec.fullscreen).append(',');
        sb.append("\"keepScreenOn\":").append(spec.keepScreenOn).append(',');
        sb.append("\"zoomEnabled\":").append(spec.zoomEnabled).append(',');
        String o = spec.orientation == null ? "auto" : spec.orientation.toLowerCase(Locale.ROOT);
        if (!o.equals("portrait") && !o.equals("landscape")) o = "auto";
        jsonPair(sb, "orientation", o);
        sb.append('}');
        return sb.toString();
    }

    private static void jsonPair(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"");
        String v = value == null ? "" : value;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    /** assets/web/ 配下に入れてよいパスに正規化する。 */
    static String sanitizeAssetPath(String raw) {
        if (raw == null) return "";
        String p = raw.replace('\\', '/').trim();
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) return ""; // ディレクトリトラバーサル禁止
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.toString();
    }

    // ======================================================================
    // テンプレート読み書き
    // ======================================================================

    static List<Entry> readEntries(byte[] apk) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(apk))) {
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
        return entries;
    }

    private static byte[] sign(Spec spec, byte[] unsigned) throws Exception {
        if (spec.keystore == null) throw new IllegalStateException("署名用キーストアがありません");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = spec.keystore) {
            ks.load(is, spec.keystorePassword);
        }
        PrivateKey privateKey = (PrivateKey) ks.getKey(spec.keyAlias, spec.keyPassword);
        if (privateKey == null) throw new IllegalStateException("キーストアにエイリアスがありません: " + spec.keyAlias);
        Certificate cert = ks.getCertificate(spec.keyAlias);

        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "webtoapp", privateKey, Collections.singletonList((X509Certificate) cert)).build();
        File in = File.createTempFile("w2a-unsigned", ".apk", spec.tempDir);
        File out = File.createTempFile("w2a-signed", ".apk", spec.tempDir);
        try {
            try (FileOutputStream fos = new FileOutputStream(in)) {
                fos.write(unsigned);
            }
            ApkSigner fileSigner = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .setOtherSignersSignaturesPreserved(false)
                    .setInputApk(in)
                    .setOutputApk(out)
                    .build();
            fileSigner.sign();
            byte[] result = new byte[(int) out.length()];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(out)) {
                int read = 0;
                while (read < result.length) {
                    int n = fis.read(result, read, result.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            return result;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            in.delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    // ======================================================================
    // AndroidManifest.xml (AXML) 書換
    // ======================================================================

    /**
     * マニフェストの StringPool (パッケージ名 / アプリ名) と
     * &lt;uses-sdk&gt; の整数属性 (minSdkVersion / targetSdkVersion) を書き換える。
     */
    static byte[] patchManifest(byte[] axml, String oldPackage, String newPackage,
                                String oldLabel, String newLabel, int minSdk, int targetSdk) {
        Map<String, String> byValue = new LinkedHashMap<>();
        if (oldPackage != null && newPackage != null) byValue.put(oldPackage, newPackage);
        if (oldLabel != null && newLabel != null) byValue.put(oldLabel, newLabel);

        Map<Integer, String> byIndex = new HashMap<>();
        byte[] patched = patchUsesSdk(axml, minSdk, targetSdk, byIndex);
        return rewriteStringPool(patched, byValue, byIndex);
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    private static int i32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
    private static void putI32(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24);
    }

    /** <uses-sdk android:minSdkVersion / android:targetSdkVersion> の値を書き換える。 */
    static byte[] patchUsesSdk(byte[] axml, int minSdk, int targetSdk, Map<Integer, String> stringFallback) {
        byte[] b = axml.clone();
        int total = i32(b, 4);
        int[] resMap = null;

        int off = 8;
        while (off + 8 <= total) {
            int type = u16(b, off);
            int headerSize = u16(b, off + 2);
            int size = i32(b, off + 4);
            if (size <= 0 || off + size > total) break;

            if (type == 0x0180) { // RES_XML_RESOURCE_MAP_TYPE
                int count = (size - headerSize) / 4;
                resMap = new int[count];
                for (int i = 0; i < count; i++) resMap[i] = i32(b, off + headerSize + 4 * i);
            } else if (type == 0x0102) { // RES_XML_START_ELEMENT_TYPE
                int ext = off + headerSize;
                if (ext + 20 > total) break;
                int attrStart = u16(b, ext + 8);
                int attrSize = u16(b, ext + 10);
                int attrCount = u16(b, ext + 12);
                if (attrSize < 20) attrSize = 20;
                for (int a = 0; a < attrCount; a++) {
                    int ao = ext + attrStart + a * attrSize;
                    if (ao + 20 > total) break;
                    int nameIdx = i32(b, ao + 4);
                    int resId = (resMap != null && nameIdx >= 0 && nameIdx < resMap.length) ? resMap[nameIdx] : 0;
                    int dataType = b[ao + 15] & 0xFF;
                    int data = i32(b, ao + 16);

                    int target = -1;
                    if (resId == 0x0101020c) target = minSdk;       // android:minSdkVersion
                    else if (resId == 0x01010270) target = targetSdk; // android:targetSdkVersion
                    if (target < 0) continue;

                    if (dataType == 0x03) { // TYPE_STRING → StringPool 側で差し替える
                        stringFallback.put(data, String.valueOf(target));
                    } else {
                        b[ao + 15] = 0x10;       // TYPE_INT_DEC
                        putI32(b, ao + 16, target);
                        putI32(b, ao + 8, -1);   // rawValue = なし
                    }
                }
            }
            off += size;
        }
        return b;
    }

    /**
     * 先頭 StringPool チャンクを走査し、文字列を置換して再エンコードする (UTF-16 で出力)。
     * チャンクサイズ・文字列オフセットを再計算し、後続チャンクはそのまま複製する。
     */
    static byte[] rewriteStringPool(byte[] axml, Map<String, String> byValue,
                                    Map<Integer, String> byIndex) {
        ByteBuffer buf = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        int fileType = buf.getShort(0) & 0xFFFF;
        int headerSize = buf.getShort(2) & 0xFFFF;
        int totalSize = buf.getInt(4);

        int spOffset = 8;
        int spType = buf.getShort(spOffset) & 0xFFFF;
        int spHeaderSize = buf.getShort(spOffset + 2) & 0xFFFF;
        int spSize = buf.getInt(spOffset + 4);
        int strCount = buf.getInt(spOffset + 8);
        int styleCount = buf.getInt(spOffset + 12);
        int flags = buf.getInt(spOffset + 16);
        int strStart = buf.getInt(spOffset + 20);

        if (spType != 0x0001) throw new IllegalStateException("AXML の StringPool が見つかりません");
        if (styleCount != 0) throw new IllegalStateException("スタイル付き StringPool は未対応です");

        boolean isUtf8 = (flags & 0x100) != 0;
        int dataStart = spOffset + strStart;

        String[] strings = new String[strCount];
        for (int i = 0; i < strCount; i++) {
            int off = buf.getInt(spOffset + spHeaderSize + 4 * i);
            int s0 = dataStart + off;
            if (s0 < 0 || s0 >= axml.length) throw new IllegalStateException("AXML 文字列オフセット不正");
            strings[i] = isUtf8 ? readUtf8(axml, s0) : readUtf16(axml, s0);
        }

        for (int i = 0; i < strCount; i++) {
            String v = byIndex.get(i);
            if (v != null) { strings[i] = v; continue; }
            String r = byValue.get(strings[i]);
            if (r != null) strings[i] = r;
        }

        ByteArrayOutputStream newStr = new ByteArrayOutputStream(axml.length);
        int[] offsets = new int[strCount];
        for (int i = 0; i < strCount; i++) {
            offsets[i] = newStr.size();
            byte[] enc = strings[i].getBytes(StandardCharsets.UTF_16LE);
            int len = strings[i].length();
            newStr.write(len & 0xFF);
            newStr.write((len >> 8) & 0xFF);
            newStr.write(enc, 0, enc.length);
            newStr.write(0);
            newStr.write(0);
        }
        byte[] strBytes = newStr.toByteArray();
        int pad = (4 - (strBytes.length % 4)) % 4;

        int newStrStart = spHeaderSize + 4 * strCount + 4 * styleCount;
        int newSpSize = newStrStart + strBytes.length + pad;

        int tailLen = totalSize - (spOffset + spSize);
        int newTotal = spOffset + newSpSize + tailLen;
        ByteBuffer out = ByteBuffer.allocate(newTotal).order(ByteOrder.LITTLE_ENDIAN);

        out.putShort((short) fileType);
        out.putShort((short) headerSize);
        out.putInt(newTotal);

        out.putShort((short) spType);
        out.putShort((short) spHeaderSize);
        out.putInt(newSpSize);
        out.putInt(strCount);
        out.putInt(styleCount);
        out.putInt(flags & ~0x100);      // UTF-16 で出力
        out.putInt(newStrStart);
        out.putInt(0);                   // styleStart (styles なし)
        for (int i = 0; i < strCount; i++) out.putInt(offsets[i]);
        out.put(strBytes);
        for (int i = 0; i < pad; i++) out.put((byte) 0);
        out.put(axml, spOffset + spSize, tailLen);

        byte[] result = new byte[out.position()];
        System.arraycopy(out.array(), 0, result, 0, result.length);
        return result;
    }

    private static String readUtf8(byte[] b, int s0) {
        int p = s0;
        int len = b[p++] & 0xFF;
        if ((len & 0x80) != 0) len = ((len & 0x7F) << 8) | (b[p++] & 0xFF);
        int u8len = b[p++] & 0xFF;
        if ((u8len & 0x80) != 0) u8len = ((u8len & 0x7F) << 8) | (b[p++] & 0xFF);
        return new String(b, p, u8len, StandardCharsets.UTF_8);
    }

    private static String readUtf16(byte[] b, int s0) {
        int u16len = u16(b, s0);
        if ((u16len & 0x8000) != 0) {
            int len = ((u16len & 0x7FFF) << 16) | u16(b, s0 + 2);
            return new String(b, s0 + 4, len * 2, StandardCharsets.UTF_16LE);
        }
        return new String(b, s0 + 2, u16len * 2, StandardCharsets.UTF_16LE);
    }

    // ======================================================================
    // アイコン
    // ======================================================================

    /** パスがテンプレートのランチャーアイコンなら密度 px を返す (mdpi=48...)。 */
    static int iconDensity(String name) {
        if (!name.startsWith("res/mipmap-") || !name.endsWith("ic_launcher.png")) return -1;
        for (String[] d : ICON_DIRS) {
            if (name.contains(d[0])) return Integer.parseInt(d[1]);
        }
        return -1;
    }

    // ======================================================================
    // ZIP ライタ (アライメント制御)
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
     * 「無圧縮 & 4 バイト境界」であることがインストール時に要求されるため必須。
     */
    static class ApkWriter {

        private static boolean hasNonAscii(byte[] b) {
            for (byte x : b) if ((x & 0x80) != 0) return true;
            return false;
        }

        byte[] write(List<Entry> entries) {            ByteArrayOutputStream body = new ByteArrayOutputStream();
            List<byte[]> central = new ArrayList<>();
            long offset = 0;

            for (Entry e : entries) {
                int method = (e.method == ZipEntry.STORED) ? ZipEntry.STORED : ZipEntry.DEFLATED;
                byte[] nameB = e.name.getBytes(StandardCharsets.UTF_8);
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
                    }
                }

                // 名前のエンコーディング: 非 ASCII を含むなら汎用フラグ bit 11 (UTF-8) を立てる
                int nameFlags = hasNonAscii(nameB) ? 0x0800 : 0;

                ByteBuffer lh = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
                lh.putInt(0x04034B50);
                lh.putShort((short) 20);
                lh.putShort((short) nameFlags);
                lh.putShort((short) method);
                lh.putShort((short) 0);
                lh.putShort((short) 0);
                lh.putInt((int) crc.getValue());
                lh.putInt(payload.length);
                lh.putInt(e.data.length);
                lh.putShort((short) nameB.length);
                lh.putShort((short) extra.length);

                long entryStart = offset;
                body.write(lh.array(), 0, 30);
                body.write(nameB, 0, nameB.length);
                body.write(extra, 0, extra.length);
                body.write(payload, 0, payload.length);
                offset = entryStart + 30 + nameB.length + extra.length + payload.length;

                ByteBuffer cd = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
                cd.putInt(0x02014B50);
                cd.putShort((short) 20);
                cd.putShort((short) 20);
                cd.putShort((short) nameFlags);
                cd.putShort((short) method);
                cd.putShort((short) 0);
                cd.putShort((short) 0);
                cd.putInt((int) crc.getValue());
                cd.putInt(payload.length);
                cd.putInt(e.data.length);
                cd.putShort((short) nameB.length);
                cd.putShort((short) extra.length);
                cd.putShort((short) 0);
                cd.putShort((short) 0);
                cd.putShort((short) 0);
                cd.putInt(0);
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

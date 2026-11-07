package com.taniniya.webtoapp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ローカルファイル (HTML / CSS / JS / ZIP) まわりの純粋なロジック (v2.1)。
 *
 * Android API に依存しないので、PC 上のテストからもそのまま呼べる。
 *  - ZIP の展開
 *  - 共通ルートフォルダの除去
 *  - index.html の決定
 *  - アプリ名の推測 (URL / HTML の title)
 */
public final class WebAssets {

    public static final int MAX_FILES = 400;
    public static final long MAX_BYTES = 64L * 1024 * 1024;

    private WebAssets() {}

    /** ZIP をメモリ上で展開する (ディレクトリと macOS のゴミは除く)。 */
    public static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                if (out.size() >= MAX_FILES) break;
                String name = ApkCore.sanitizeAssetPath(e.getName());
                if (name.isEmpty() || e.isDirectory()) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("__macosx/") || lower.endsWith(".ds_store")) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                out.put(name, bos.toByteArray());
            }
        }
        return out;
    }

    /** すべてのファイルが同じフォルダ1階層に入っている ZIP は、そのフォルダを剥がす。 */
    public static Map<String, byte[]> stripCommonRoot(Map<String, byte[]> in) {
        if (in == null || in.isEmpty()) return in;
        String prefix = null;
        for (String key : in.keySet()) {
            int slash = key.indexOf('/');
            if (slash < 0) return in; // ルート直下にファイルがある → 剥がさない
            String first = key.substring(0, slash);
            if (prefix == null) prefix = first;
            else if (!prefix.equals(first)) return in;
        }
        if (prefix == null) return in;
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : in.entrySet()) {
            out.put(e.getKey().substring(prefix.length() + 1), e.getValue());
        }
        return out;
    }

    /** index.html が無ければ、ルート直下(または最初)の HTML を index.html として扱う。 */
    public static String ensureIndex(Map<String, byte[]> assets) {
        if (assets == null || assets.isEmpty()) return null;
        if (assets.containsKey("index.html")) return "index.html";
        String candidate = null;
        for (String key : assets.keySet()) {
            if (!isHtml(key)) continue;
            if (key.indexOf('/') < 0) { candidate = key; break; }
            if (candidate == null) candidate = key;
        }
        if (candidate == null) return null;
        assets.put("index.html", assets.get(candidate));
        return "index.html";
    }

    public static boolean isHtml(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".html") || n.endsWith(".htm");
    }

    /** 「index.html, style.css, script.js ほか」のような短い一覧。 */
    public static String summarize(Map<String, byte[]> assets) {
        if (assets == null || assets.isEmpty()) return "";
        List<String> names = new ArrayList<>();
        if (assets.containsKey("index.html")) names.add("index.html");
        for (String key : assets.keySet()) {
            if (names.size() >= 3) break;
            if (names.contains(key)) continue;
            names.add(key);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.get(i));
        }
        if (assets.size() > names.size()) sb.append(" ほか");
        return sb.toString();
    }

    private static final Pattern TITLE = Pattern.compile(
            "<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** HTML の &lt;title&gt; からアプリ名を推測する。 */
    public static String suggestAppNameFromHtml(byte[] html) {
        if (html == null || html.length == 0) return "";
        try {
            int len = Math.min(html.length, 65536);
            String text = new String(html, 0, len, StandardCharsets.UTF_8);
            Matcher m = TITLE.matcher(text);
            if (m.find()) {
                String t = m.group(1).replaceAll("<[^>]+>", " ")
                        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&#39;", "'").replace("&quot;", "\"").trim()
                        .replaceAll("\\s+", " ");
                if (t.length() > 40) t = t.substring(0, 40).trim();
                return t;
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** ファイル名からアプリ名を推測する (game.html → Game)。index は推測しない。 */
    public static String suggestAppNameFromFileName(String name) {
        if (name == null) return "";
        String base = name;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replace('_', ' ').replace('-', ' ').trim();
        if (base.isEmpty() || base.equalsIgnoreCase("index")) return "";
        if (base.length() > 40) base = base.substring(0, 40).trim();
        return Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    /** URL のホスト名からアプリ名を推測する (https://www.example.com → Example)。 */
    public static String suggestAppNameFromUrl(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        if (u.isEmpty() || u.contains(" ") || u.contains("\n")) return "";
        try {
            String withScheme = u.contains("://") ? u : "https://" + u;
            URI uri = new URI(withScheme);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return "";
            host = host.replaceFirst("(?i)^www\\.", "");
            String[] parts = host.split("\\.");
            String label = parts.length > 0 ? parts[0] : host;
            if (label.isEmpty() || label.equalsIgnoreCase("localhost")) return "";
            return Character.toUpperCase(label.charAt(0)) + label.substring(1);
        } catch (Exception e) {
            return "";
        }
    }

    /** アプリ名からパッケージ名の末尾セグメントを作る。 */
    public static String toPackageSegment(String name) {
        StringBuilder sb = new StringBuilder();
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            if (sb.length() >= 20) break;
        }
        if (sb.length() == 0) sb.append("myapp");
        if (Character.isDigit(sb.charAt(0))) sb.insert(0, "a");
        return sb.toString();
    }
}

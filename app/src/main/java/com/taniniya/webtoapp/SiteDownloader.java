package com.taniniya.webtoapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * URL モード用「Web サイトをダウンロードしてオフライン化」するエンジン (v2.2)。
 *
 * やること:
 *   1. 指定 URL の HTML を取得
 *   2. HTML 内の CSS / JS / 画像 (同じサイト内のもの) を取得
 *   3. CSS 内の url(...) / @import もたどって取得
 *   4. すべてを assets/web/ に入れられる相対パスへ書き換える
 *      (index.html が起点、資源は r/&lt;host&gt;/&lt;元のパス&gt; に置く)
 *
 * 外部ドメインの資源や、ログインが必要なページ・JS が後から取りに行く資源は
 * 取得できないため、そのまま (絶対 URL のまま) 残す。これが「正常に動作しない
 * 場合があります」の正体で、UI 側にもその注意書きを出している。
 *
 * Android API に依存しない Pure Java なので、PC 上でも同じコードを動かして
 * 検証できる (template/tools/offline_test.py)。
 */
public final class SiteDownloader {

    /** 進捗通知 */
    public interface Progress {
        void onStep(String message);
    }

    /** 取得結果 */
    public static final class Result {
        /** 実際に取得したページ URL (リダイレクト後) */
        public String pageUrl = "";
        /** ページの &lt;title&gt; (アプリ名の初期値に使う) */
        public String title = "";
        /** assets/web/ にそのまま入れられるファイル (index.html 起点) */
        public Map<String, byte[]> assets = new LinkedHashMap<>();
        /** 取得できた資源の数 (index.html を含む) */
        public int fetched = 0;
        /** 取得できなかった資源の数 */
        public int failed = 0;
        /** 別ドメインなので取得しなかった参照の数 */
        public int external = 0;
        /** 取得に失敗した URL (先頭 10 件) */
        public List<String> failedUrls = new ArrayList<>();
    }

    /** 上限 (端末のメモリと APK サイズを守る) */
    public static final int MAX_FILES = 200;
    public static final long MAX_TOTAL_BYTES = 24L * 1024 * 1024;
    public static final int MAX_CSS_DEPTH = 3;
    public static final int CONNECT_TIMEOUT_MS = 12000;
    public static final int READ_TIMEOUT_MS = 20000;

    static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";

    private SiteDownloader() {}

    // ======================================================================
    // 入口
    // ======================================================================

    /** サイトを取得して、オフライン同梱用の assets 一式を返す。 */
    public static Result download(String rawUrl, Progress progress) throws Exception {
        String url = normalizeUrl(rawUrl);
        if (url.isEmpty()) throw new IOException("URLが空です");
        return new Crawler(progress).run(url);
    }

    /** スキームが無ければ https:// を補う。 */
    public static String normalizeUrl(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.isEmpty()) return "";
        if (!u.contains("://")) u = "https://" + u;
        return u;
    }

    /** 取得結果の短い説明 (例: "32ファイル / 3件失敗") */
    public static String summarize(Result r) {
        if (r == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(r.fetched).append("ファイル");
        if (r.failed > 0) sb.append(" / ").append(r.failed).append("件失敗");
        if (r.external > 0) sb.append(" / 外部").append(r.external).append("件");
        return sb.toString();
    }

    // ======================================================================
    // 本体
    // ======================================================================

    private static final class Crawler {

        private final Progress progress;
        private final Result result = new Result();
        private final Map<String, String> urlToPath = new HashMap<>();
        private final Map<String, String> pathToUrl = new HashMap<>();
        private final Set<String> seenFailures = new LinkedHashSet<>();
        private final List<Task> queue = new ArrayList<>();
        private final List<Task> failedTasks = new ArrayList<>();
        private long totalBytes = 0;
        private String originHost = "";

        Crawler(Progress progress) {
            this.progress = progress;
        }

        Result run(String url) throws Exception {
            step("ページを取得中…");
            Resp page = fetch(url);
            if (page.status >= 400 || page.body == null || page.body.length == 0) {
                throw new IOException("ページを取得できませんでした (HTTP " + page.status + ")");
            }
            result.pageUrl = page.finalUrl;
            try {
                URI u = new URI(page.finalUrl);
                originHost = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            } catch (Exception e) {
                originHost = "";
            }

            String charset = detectCharset(page.body, page.contentType);
            String html = new String(page.body, charset);

            // <base href> があれば、相対 URL の基準にする
            String baseUrl = page.finalUrl;
            Matcher bm = BASE_TAG.matcher(html);
            if (bm.find()) {
                String href = attrValue(bm.group(2), "href");
                if (href != null && !href.trim().isEmpty()) {
                    String abs = absolutize(href, page.finalUrl);
                    if (abs != null) baseUrl = abs;
                }
            }

            result.title = extractTitle(html);

            String out = rewriteHtml(html, baseUrl, "index.html", 0);
            out = fixCharset(out, charset);
            result.assets.put("index.html", out.getBytes(charset));
            result.fetched = 1;
            totalBytes = result.assets.get("index.html").length;

            processQueue();
            applyFallbacks();

            step("完了");
            return result;
        }

        // ---------------- 取得キュー ----------------

        private void processQueue() {
            int i = 0;
            while (i < queue.size()) {
                Task t = queue.get(i);
                i++;
                if (result.assets.size() >= MAX_FILES || totalBytes >= MAX_TOTAL_BYTES) {
                    fail(t);
                    continue;
                }
                try {
                    step("ダウンロード中… " + i + " / " + queue.size());
                    Resp r = fetch(t.url);
                    if (r.status >= 400 || r.body == null) {
                        fail(t);
                        continue;
                    }
                    byte[] body = r.body;
                    boolean css = t.css || looksLikeCss(t.url, r.contentType);
                    if (css) {
                        String cs = detectCharset(body, r.contentType);
                        String text = new String(body, cs);
                        String rewritten = rewriteCssText(text, t.url, t.assetPath, t.depth);
                        body = rewritten.getBytes(cs);
                    }
                    if (totalBytes + body.length > MAX_TOTAL_BYTES) {
                        fail(t);
                        continue;
                    }
                    totalBytes += body.length;
                    result.assets.put(t.assetPath, body);
                    result.fetched++;
                } catch (Throwable e) {
                    fail(t);
                }
            }
        }

        private void fail(Task t) {
            result.failed++;
            if (seenFailures.size() < 10) seenFailures.add(t.url);
            result.failedUrls = new ArrayList<>(seenFailures);
            failedTasks.add(t);
        }

        /**
         * 取得できなかった資源への参照を、元の絶対 URL に戻す (フォールバック)。
         * こうしておくと、オフラインでは読めない資源も、オンラインならそのまま表示できる。
         */
        private void applyFallbacks() {
            if (failedTasks.isEmpty() || result.assets.isEmpty()) return;
            List<Map.Entry<String, byte[]>> entries =
                    new ArrayList<>(result.assets.entrySet());
            for (Map.Entry<String, byte[]> e : entries) {
                byte[] data = e.getValue();
                for (Task t : failedTasks) {
                    String rel = relPath(e.getKey(), t.assetPath);
                    if (rel == null || rel.isEmpty()) continue;
                    data = replaceAscii(data,
                            rel.getBytes(StandardCharsets.UTF_8),
                            t.url.getBytes(StandardCharsets.UTF_8));
                }
                if (data != e.getValue()) result.assets.put(e.getKey(), data);
            }
        }

        private void step(String message) {
            if (progress != null) {
                try {
                    progress.onStep(message);
                } catch (Throwable ignored) {}
            }
        }

        /** URL を予約し、asset 内のパスを決めて取得キューに積む。同一サイト外なら null。 */
        private String reserve(String absUrl, boolean css, int depth) {
            String clean = stripFragment(absUrl);
            if (clean == null || clean.isEmpty()) return null;
            String existing = urlToPath.get(clean);
            if (existing != null) return existing;
            if (!isSameSite(clean)) {
                result.external++;
                return null;
            }
            if (pathToUrl.size() >= MAX_FILES) return null;
            if (depth > MAX_CSS_DEPTH) return null;
            String path = assetPathFor(clean);
            if (path == null) return null;
            // パスの衝突 (クエリ違いなど) を避ける
            if (pathToUrl.containsKey(path)) {
                String base = path;
                int dot = base.lastIndexOf('.');
                for (int n = 2; n < 100; n++) {
                    String candidate = dot > 0
                            ? base.substring(0, dot) + "-" + n + base.substring(dot)
                            : base + "-" + n;
                    if (!pathToUrl.containsKey(candidate)) {
                        path = candidate;
                        break;
                    }
                }
            }
            urlToPath.put(clean, path);
            pathToUrl.put(path, clean);
            queue.add(new Task(clean, path, css, depth));
            return path;
        }

        private boolean isSameSite(String absUrl) {
            try {
                URI u = new URI(absUrl);
                String h = u.getHost();
                if (h == null || h.isEmpty()) return false;
                h = h.toLowerCase(Locale.ROOT);
                String o = originHost == null ? "" : originHost;
                if (h.equals(o)) return true;
                String a = h.replaceFirst("^www\\.", "");
                String b = o.replaceFirst("^www\\.", "");
                if (a.equals(b)) return true;
                return a.endsWith("." + b) || b.endsWith("." + a);
            } catch (Exception e) {
                return false;
            }
        }

        // ---------------- HTML ----------------

        private String rewriteHtml(String html, String docUrl, String docPath, int depth) {
            String out = rewriteStyleBlocks(html, docUrl, docPath, depth);
            return rewriteTags(out, docUrl, docPath, depth);
        }

        private String rewriteStyleBlocks(String html, String docUrl, String docPath, int depth) {
            Matcher m = STYLE_BLOCK.matcher(html);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String css = rewriteCssText(m.group(2), docUrl, docPath, depth);
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + css + m.group(3)));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private String rewriteTags(String html, String docUrl, String docPath, int depth) {
            Matcher m = TAG.matcher(html);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String name = m.group(1).toLowerCase(Locale.ROOT);
                String rebuilt;
                if (name.equals("base")) {
                    // <base> は基準 URL を変えてしまうので取り除く (相対参照は書き換え済み)
                    rebuilt = "<!-- base removed -->";
                } else {
                    rebuilt = "<" + m.group(1)
                            + rewriteAttrs(name, m.group(2), docUrl, docPath, depth) + ">";
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(rebuilt));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private String rewriteAttrs(String tag, String attrs, String docUrl,
                                    String docPath, int depth) {
            List<Attr> list = parseAttrs(attrs);
            String rel = "";
            for (Attr a : list) {
                if (a.name != null && a.name.equalsIgnoreCase("rel") && a.value != null) {
                    rel = a.value.toLowerCase(Locale.ROOT);
                }
            }
            StringBuilder sb = new StringBuilder(attrs.length() + 32);
            for (Attr a : list) {
                sb.append(' ').append(a.name);
                if (a.value == null) continue;
                String v = mapAttrValue(tag, a.name == null ? "" : a.name.toLowerCase(Locale.ROOT),
                        a.value, rel, docUrl, docPath, depth);
                sb.append("=\"").append(v.replace("\"", "&quot;")).append('"');
            }
            return sb.toString();
        }

        private String mapAttrValue(String tag, String attr, String value, String rel,
                                    String docUrl, String docPath, int depth) {
            String v = value.trim();
            if (v.isEmpty() || isOpaque(v)) return value;
            if (attr.equals("srcset")) return rewriteSrcset(v, docUrl, docPath, depth);
            if (attr.equals("style")) return rewriteCssText(value, docUrl, docPath, depth);
            if (attr.equals("href")) {
                if (tag.equals("link")) {
                    if (!isDownloadableLink(rel)) return safeAbs(v, docUrl, value);
                    return resourceRef(v, docUrl, docPath, depth, rel.contains("stylesheet"));
                }
                // ページ遷移リンクは絶対 URL のまま (オフラインでは開けないが、元の行き先を残す)
                return safeAbs(v, docUrl, value);
            }
            if (attr.equals("src")) {
                if (tag.equals("script")) return resourceRef(v, docUrl, docPath, depth, false);
                if (tag.equals("iframe") || tag.equals("frame")) return safeAbs(v, docUrl, value);
                return resourceRef(v, docUrl, docPath, depth, false);
            }
            if (attr.equals("poster") || attr.equals("data")) {
                return resourceRef(v, docUrl, docPath, depth, false);
            }
            if (attr.equals("action")) return safeAbs(v, docUrl, value);
            return value;
        }

        private String rewriteSrcset(String value, String docUrl, String docPath, int depth) {
            StringBuilder sb = new StringBuilder();
            String[] parts = value.split(",");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();
                if (part.isEmpty()) continue;
                String[] bits = part.split("\\s+");
                String u = bits[0];
                String ref = isOpaque(u) ? u : resourceRef(u, docUrl, docPath, depth, false);
                if (i > 0) sb.append(", ");
                sb.append(ref);
                for (int k = 1; k < bits.length; k++) sb.append(' ').append(bits[k]);
            }
            return sb.toString();
        }

        private String resourceRef(String value, String docUrl, String docPath,
                                   int depth, boolean css) {
            String abs = absolutize(value, docUrl);
            if (abs == null) return value;
            String path = reserve(abs, css || looksLikeCssUrl(abs), depth);
            if (path == null) return abs; // 外部ドメイン等は絶対 URL のまま残す
            return relPath(docPath, path);
        }

        private String safeAbs(String value, String docUrl, String fallback) {
            String abs = absolutize(value, docUrl);
            return abs == null ? fallback : abs;
        }

        // ---------------- CSS ----------------

        private String rewriteCssText(String css, String cssUrl, String cssPath, int depth) {
            if (css == null || css.isEmpty()) return css;
            String out = replaceMatcher(CSS_URL, css, m -> {
                String quote = m.group(1) == null ? "" : m.group(1);
                String rawUrl = m.group(2) == null ? "" : m.group(2).trim();
                if (rawUrl.isEmpty() || isOpaque(rawUrl)) return null;
                return cssRef(rawUrl, cssUrl, cssPath, depth);
            });
            out = replaceMatcher(CSS_IMPORT, out, m -> {
                String rawUrl = m.group(2) == null ? "" : m.group(2).trim();
                if (rawUrl.isEmpty() || isOpaque(rawUrl)) return null;
                String abs = absolutize(rawUrl, cssUrl);
                if (abs == null) return null;
                String path = reserve(abs, true, depth + 1);
                if (path == null) return abs;
                return "@import url(\"" + relPath(cssPath, path) + "\")";
            });
            return out;
        }

        /** CSS の url(...) 1 つを書き換える。失敗時は null (= 元のまま)。 */
        private String cssRef(String rawUrl, String cssUrl, String cssPath, int depth) {
            String abs = absolutize(rawUrl, cssUrl);
            if (abs == null) return null;
            String path = reserve(abs, looksLikeCssUrl(abs), depth + 1);
            if (path == null) return abs;
            return "url(\"" + relPath(cssPath, path) + "\")";
        }

        // ---------------- ユーティリティ ----------------

        private static String extractTitle(String html) {
            Matcher m = TITLE.matcher(html);
            if (!m.find()) return "";
            String t = m.group(1).replaceAll("(?s)<[^>]+>", " ")
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&#39;", "'").replace("&quot;", "\"").trim()
                    .replaceAll("\\s+", " ");
            if (t.length() > 40) t = t.substring(0, 40).trim();
            return t;
        }

        private static boolean isOpaque(String v) {
            String s = v.toLowerCase(Locale.ROOT);
            return s.startsWith("#") || s.startsWith("data:") || s.startsWith("javascript:")
                    || s.startsWith("mailto:") || s.startsWith("tel:") || s.startsWith("about:")
                    || s.startsWith("blob:") || s.startsWith("chrome:") || s.startsWith("intent:");
        }

        private static boolean isDownloadableLink(String rel) {
            if (rel == null || rel.isEmpty()) return false;
            return rel.contains("stylesheet") || rel.contains("icon") || rel.contains("preload")
                    || rel.contains("manifest") || rel.contains("mask-icon");
        }

        private static boolean looksLikeCssUrl(String url) {
            String p = url;
            int q = p.indexOf('?');
            if (q >= 0) p = p.substring(0, q);
            int h = p.indexOf('#');
            if (h >= 0) p = p.substring(0, h);
            return p.toLowerCase(Locale.ROOT).endsWith(".css");
        }

        private static boolean looksLikeCss(String url, String contentType) {
            if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/css")) return true;
            return looksLikeCssUrl(url);
        }
    }

    /** URL からフラグメント (#...) を落とす。 */
    public static String stripFragment(String url) {
        if (url == null) return null;
        int h = url.indexOf('#');
        return h >= 0 ? url.substring(0, h) : url;
    }

    // ======================================================================
    // URL / パス
    // ======================================================================

    /** 相対 URL を絶対 URL にする (失敗時 null)。 */
    public static String absolutize(String value, String baseUrl) {
        if (value == null) return null;
        String v = value.trim().replace("&amp;", "&");
        if (v.isEmpty()) return null;
        if (v.startsWith("//")) {
            String scheme = "https";
            try {
                scheme = new URI(baseUrl).getScheme();
                if (scheme == null) scheme = "https";
            } catch (Exception ignored) {}
            return scheme + ":" + v;
        }
        try {
            URI b = new URI(baseUrl);
            URI r = b.resolve(v);
            String s = r.toString();
            if (s.startsWith("http://") || s.startsWith("https://")) return s;
            return null;
        } catch (Exception e) {
            try {
                URI r = new URI(baseUrl).resolve(new URI(null, null, v, null));
                String s = r.toString();
                return (s.startsWith("http://") || s.startsWith("https://")) ? s : null;
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /** URL → assets/web/ 内のパス (r/&lt;host&gt;/&lt;元パス&gt;)。 */
    public static String assetPathFor(String absUrl) {
        String clean = stripFragment(absUrl);
        if (clean == null) return null;
        URI u;
        try {
            u = new URI(clean);
        } catch (Exception e) {
            return null;
        }
        String host = u.getHost();
        if (host == null || host.isEmpty()) return null;
        host = host.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]", "_");
        String path = u.getRawPath();
        if (path == null || path.isEmpty()) path = "/index.html";
        if (path.endsWith("/")) path = path + "index.html";

        String query = u.getRawQuery();
        if (query != null && !query.isEmpty()) {
            String hash = crc8(query);
            int slash = path.lastIndexOf('/');
            String dir = slash >= 0 ? path.substring(0, slash + 1) : "";
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot) + "-" + hash + name.substring(dot);
            } else {
                name = name + "-" + hash;
            }
            path = dir + name;
        }

        String full = "r/" + host + path;
        String safe = ApkCore.sanitizeAssetPath(full);
        if (safe.isEmpty()) return null;
        if (safe.length() > 180) {
            String hash = crc8(safe);
            safe = safe.substring(0, 160) + "-" + hash;
        }
        return safe;
    }

    /** 2 つの asset パス間の相対参照を作る。 */
    public static String relPath(String fromPath, String toPath) {
        if (fromPath == null || toPath == null) return toPath;
        String[] a = fromPath.split("/");
        String[] b = toPath.split("/");
        int max = Math.min(a.length, b.length) - 1;
        int i = 0;
        while (i < max && a[i].equals(b[i])) i++;
        StringBuilder sb = new StringBuilder();
        for (int k = i; k < a.length - 1; k++) sb.append("../");
        for (int k = i; k < b.length; k++) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '/') sb.append('/');
            sb.append(b[k]);
        }
        return sb.toString();
    }

    /** ASCII 文字列のバイト置換 (文字コードを問わず安全に使える)。 */
    static byte[] replaceAscii(byte[] data, byte[] needle, byte[] replacement) {
        if (data == null || needle == null || needle.length == 0) return data;
        int idx = indexOf(data, needle, 0);
        if (idx < 0) return data;
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 32);
        int pos = 0;
        while (idx >= 0) {
            out.write(data, pos, idx - pos);
            out.write(replacement, 0, replacement.length);
            pos = idx + needle.length;
            idx = indexOf(data, needle, pos);
        }
        out.write(data, pos, data.length - pos);
        return out.toByteArray();
    }

    private static int indexOf(byte[] data, byte[] needle, int from) {
        int last = data.length - needle.length;
        for (int i = Math.max(0, from); i <= last; i++) {
            boolean hit = true;
            for (int k = 0; k < needle.length; k++) {
                if (data[i + k] != needle[k]) {
                    hit = false;
                    break;
                }
            }
            if (hit) return i;
        }
        return -1;
    }

    private static String crc8(String s) {
        CRC32 crc = new CRC32();
        crc.update(s.getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue() & 0xFFFFFFFFL);
    }

    // ======================================================================
    // 取得
    // ======================================================================

    static final class Resp {
        byte[] body = new byte[0];
        String contentType = "";
        String finalUrl = "";
        int status = 0;
    }

    static Resp fetch(String url) throws IOException {
        Resp r = new Resp();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Accept-Language", "ja,en;q=0.8");
            r.status = conn.getResponseCode();
            r.contentType = conn.getContentType() == null ? "" : conn.getContentType();
            String finalUrl = conn.getURL() == null ? url : conn.getURL().toString();
            r.finalUrl = finalUrl;
            InputStream in = r.status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return r;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_TOTAL_BYTES) throw new IOException("ファイルが大きすぎます");
                    out.write(buf, 0, n);
                }
                r.body = out.toByteArray();
            } finally {
                in.close();
            }
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {}
            }
        }
        return r;
    }

    /** Content-Type → meta charset → UTF-8 の順で文字コードを決める。 */
    public static String detectCharset(byte[] body, String contentType) {
        String name = null;
        if (contentType != null) {
            Matcher m = Pattern.compile("(?i)charset\\s*=\\s*\"?([a-zA-Z0-9_\\-]+)").matcher(contentType);
            if (m.find()) name = m.group(1);
        }
        if (name == null && body != null) {
            int len = Math.min(body.length, 4096);
            String head = new String(body, 0, len, StandardCharsets.ISO_8859_1);
            Matcher m = Pattern.compile("(?i)<meta[^>]+charset\\s*=\\s*\"?([a-zA-Z0-9_\\-]+)").matcher(head);
            if (m.find()) name = m.group(1);
        }
        if (name != null) {
            try {
                if (Charset.isSupported(name)) return Charset.forName(name).name();
            } catch (Exception ignored) {}
        }
        return "UTF-8";
    }

    /** <meta charset> を実際に使った文字コードへ揃える。 */
    public static String fixCharset(String html, String charsetName) {
        String clean = html.replaceAll("(?is)<meta[^>]*charset\\s*=[^>]*>", "");
        String safe = charsetName == null ? "UTF-8" : charsetName.replaceAll("[^A-Za-z0-9_\\-]", "");
        if (safe.isEmpty()) safe = "UTF-8";
        String tag = "<meta charset=\"" + safe + "\">";
        Matcher m = Pattern.compile("(?is)<head[^>]*>").matcher(clean);
        if (m.find()) {
            return clean.substring(0, m.end()) + tag + clean.substring(m.end());
        }
        return tag + clean;
    }

    // ======================================================================
    // パターン
    // ======================================================================

    private static final Pattern TAG = Pattern.compile(
            "(?is)<([a-zA-Z][a-zA-Z0-9:-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>");
    private static final Pattern STYLE_BLOCK = Pattern.compile(
            "(?is)(<style[^>]*>)(.*?)(</style>)");
    private static final Pattern BASE_TAG = Pattern.compile(
            "(?is)<base((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>");
    private static final Pattern TITLE = Pattern.compile(
            "(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern ATTR = Pattern.compile(
            "([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+)))?");
    private static final Pattern CSS_URL = Pattern.compile(
            "(?is)url\\(\\s*(['\"]?)([^'\")]*?)\\1\\s*\\)");
    private static final Pattern CSS_IMPORT = Pattern.compile(
            "(?is)@import\\s+(['\"])([^'\"]+)\\1");

    /** 属性文字列を壊さずに分解する。 */
    private static List<Attr> parseAttrs(String attrs) {
        List<Attr> out = new ArrayList<>();
        if (attrs == null) return out;
        Matcher m = ATTR.matcher(attrs);
        while (m.find()) {
            Attr a = new Attr();
            a.name = m.group(1);
            if (m.group(2) != null) {
                a.value = m.group(3) != null ? m.group(3)
                        : (m.group(4) != null ? m.group(4) : m.group(5));
            }
            out.add(a);
        }
        return out;
    }

    private static String attrValue(String attrs, String name) {
        List<Attr> list = parseAttrs(attrs);
        for (Attr a : list) {
            if (a.name != null && a.name.equalsIgnoreCase(name)) return a.value;
        }
        return null;
    }

    private interface Replacer {
        /** 置換文字列。null なら元のまま。 */
        String apply(Matcher m);
    }

    private static String replaceMatcher(Pattern p, String text, Replacer r) {
        Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rep = null;
            try {
                rep = r.apply(m);
            } catch (Throwable ignored) {}
            if (rep == null) rep = m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static final class Attr {
        String name;
        String value;
    }

    private static final class Task {
        final String url;
        final String assetPath;
        final boolean css;
        final int depth;

        Task(String url, String assetPath, boolean css, int depth) {
            this.url = url;
            this.assetPath = assetPath;
            this.css = css;
            this.depth = depth;
        }
    }
}

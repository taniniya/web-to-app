import com.taniniya.webtoapp.SiteDownloader;
import com.taniniya.webtoapp.WebAssets;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * WebAssets (ローカルファイル処理) のロジックテスト。
 * usage: java -cp <classes> CoreTest
 */
public class CoreTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // --- ZIP: 単一ルートフォルダの除去 ---
        Map<String, byte[]> z = WebAssets.unzip(zipOf(new String[][]{
                {"site/index.html", "<html><title>サイト</title></html>"},
                {"site/css/style.css", "body{}"},
                {"site/js/app.js", "console.log(1)"},
                {"__MACOSX/._index.html", "junk"},
        }));
        check("zip entries (macOS ゴミ除外)", z.size() == 3, z.keySet().toString());
        Map<String, byte[]> flat = WebAssets.stripCommonRoot(z);
        check("zip ルートフォルダ除去", flat.containsKey("index.html")
                && flat.containsKey("css/style.css") && flat.containsKey("js/app.js"),
                flat.keySet().toString());
        check("index 検出", "index.html".equals(WebAssets.ensureIndex(flat)));

        // --- 単一 HTML (index.html 以外) ---
        Map<String, byte[]> single = new LinkedHashMap<>();
        single.put("game.html", "<html><head><title>Game</title></head></html>".getBytes(StandardCharsets.UTF_8));
        check("単発HTMLは剥がさない", WebAssets.stripCommonRoot(single).containsKey("game.html"));
        String idx = WebAssets.ensureIndex(single);
        check("単発HTMLを index.html として扱う", "index.html".equals(idx)
                && single.containsKey("game.html") && single.containsKey("index.html"));

        // --- index が無い場合 ---
        Map<String, byte[]> none = new LinkedHashMap<>();
        none.put("a.txt", new byte[]{1});
        check("HTML が無ければ index なし", WebAssets.ensureIndex(none) == null);

        // --- title からのアプリ名推測 ---
        String t = WebAssets.suggestAppNameFromHtml(
                "<!doctype html><html><head><TITLE> オフライン 計算機 </TITLE></head></html>"
                        .getBytes(StandardCharsets.UTF_8));
        check("title からアプリ名", "オフライン 計算機".equals(t), t);
        check("title なし", "".equals(WebAssets.suggestAppNameFromHtml("<html></html>"
                .getBytes(StandardCharsets.UTF_8))));

        // --- ファイル名からのアプリ名推測 ---
        check("ファイル名 game.html", "Game".equals(WebAssets.suggestAppNameFromFileName("game.html")));
        check("ファイル名 my_page.html", "My page".equals(
                WebAssets.suggestAppNameFromFileName("my_page.html")));
        check("ファイル名 index は推測しない", "".equals(
                WebAssets.suggestAppNameFromFileName("index.html")));

        // --- URL からのアプリ名推測 ---
        check("example.com", "Example".equals(WebAssets.suggestAppNameFromUrl("example.com")));
        check("www 除去", "Taniniya".equals(
                WebAssets.suggestAppNameFromUrl("https://www.taniniya.github.io/tanishi-survivor/")));
        check("スキームなしパス付き", "Github".equals(
                WebAssets.suggestAppNameFromUrl("github.com/taniniya")));
        check("空URL", "".equals(WebAssets.suggestAppNameFromUrl("  ")));
        check("URLでない文字列", "".equals(WebAssets.suggestAppNameFromUrl("これはURLではない")));

        // --- パッケージ名 ---
        check("パッケージ名生成(日本語→myapp)", "myapp".equals(
                WebAssets.toPackageSegment("タニシ・サバイバー")));
        check("パッケージ名生成(英数)", "mygame2".equals(WebAssets.toPackageSegment("My Game 2")));
        check("パッケージ名(先頭数字)", "a123".equals(WebAssets.toPackageSegment("123")));

        // --- APK 保存先フォルダ名 (v2.2) ---
        check("保存先 既定", "WebToApp".equals(WebAssets.normalizeFolder("WebToApp")));
        check("保存先 空欄→Download 直下", "".equals(WebAssets.normalizeFolder("   ")));
        check("保存先 前後のスラッシュ", "MyApps".equals(WebAssets.normalizeFolder("/MyApps/")));
        check("保存先 2階層", "MyApps/Games".equals(WebAssets.normalizeFolder("MyApps/Games")));
        check("保存先 .. は無視", "etc".equals(WebAssets.normalizeFolder("../etc")));
        check("保存先 禁止文字", "My Apps_ 2026".equals(WebAssets.normalizeFolder("My Apps: 2026")));
        check("保存先 3階層まで", "a/b/c".equals(WebAssets.normalizeFolder("a/b/c/d")));
        check("保存先 日本語も可", "アプリ".equals(WebAssets.normalizeFolder("アプリ")));

        // --- オフライン化 (SiteDownloader) の純ロジック ---
        check("URL スキーム補完", "https://example.com".equals(
                SiteDownloader.normalizeUrl("example.com")));
        check("相対URLの解決", "https://example.com/a/b.css".equals(
                SiteDownloader.absolutize("b.css", "https://example.com/a/index.html")));
        check("ルート相対URLの解決", "https://example.com/x/y.png".equals(
                SiteDownloader.absolutize("/x/y.png", "https://example.com/a/index.html")));
        check("プロトコル相対URL", "https://cdn.example.com/a.js".equals(
                SiteDownloader.absolutize("//cdn.example.com/a.js", "https://example.com/")));
        check("javascript: は解決しない",
                SiteDownloader.absolutize("javascript:void(0)", "https://example.com/") == null);
        check("asset パスは構造を保つ", "r/example.com/css/style.css".equals(
                SiteDownloader.assetPathFor("https://example.com/css/style.css")));
        check("ディレクトリURLは index.html", "r/example.com/a/index.html".equals(
                SiteDownloader.assetPathFor("https://example.com/a/")));
        check("クエリ付きはハッシュを付ける",
                SiteDownloader.assetPathFor("https://example.com/a.css?v=1").startsWith("r/example.com/a-"));
        check("relPath 兄弟フォルダ", "../img/bg.png".equals(
                SiteDownloader.relPath("r/h/css/style.css", "r/h/img/bg.png")));
        check("relPath ルートから", "r/h/css/style.css".equals(
                SiteDownloader.relPath("index.html", "r/h/css/style.css")));
        check("charset 検出 (header)", "EUC-JP".equals(
                SiteDownloader.detectCharset(new byte[]{1, 2, 3}, "text/html; charset=EUC-JP")));
        check("charset 検出 (meta)", "Shift_JIS".equals(SiteDownloader.detectCharset(
                "<html><head><meta charset=\"Shift_JIS\"></head></html>".getBytes(StandardCharsets.UTF_8),
                "text/html")));
        check("charset 既定は UTF-8", "UTF-8".equals(
                SiteDownloader.detectCharset(new byte[]{1}, "text/html")));

        System.out.println("\nresult: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static byte[] zipOf(String[][] entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (String[] e : entries) {
                zos.putNextEntry(new ZipEntry(e[0]));
                zos.write(e[1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static void check(String label, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("PASS " + label); }
        else { failed++; System.out.println("FAIL " + label + " :: " + detail); }
    }

    private static void check(String label, boolean ok) { check(label, ok, ""); }
}

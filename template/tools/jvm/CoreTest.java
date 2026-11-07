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

import com.taniniya.webtoapp.SiteDownloader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * SiteDownloader (URL からのオフライン化) をデスクトップ JVM で動かすハーネス。
 *
 * usage: java -cp <classes>:<apksig.jar> SiteDownloadHarness <url> <outDir>
 *
 * outDir に取得した assets をそのまま書き出し、最後に 1 行の MANIFEST を出す。
 */
public class SiteDownloadHarness {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: SiteDownloadHarness <url> <outDir>");
            System.exit(2);
        }
        String url = args[0];
        Path outDir = Paths.get(args[1]);

        SiteDownloader.Result r = SiteDownloader.download(url,
                step -> System.out.println("  [step] " + step));

        if (outDir.toFile().exists()) {
            deleteRecursively(outDir.toFile());
        }
        Files.createDirectories(outDir);

        for (Map.Entry<String, byte[]> e : r.assets.entrySet()) {
            Path p = outDir.resolve(e.getKey());
            Files.createDirectories(p.getParent() == null ? outDir : p.getParent());
            Files.write(p, e.getValue());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("MANIFEST {\"pageUrl\":\"").append(json(r.pageUrl))
                .append("\",\"title\":\"").append(json(r.title))
                .append("\",\"fetched\":").append(r.fetched)
                .append(",\"failed\":").append(r.failed)
                .append(",\"external\":").append(r.external)
                .append(",\"assets\":[");
        boolean first = true;
        for (Map.Entry<String, byte[]> e : r.assets.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"").append(json(e.getKey())).append("\"");
        }
        sb.append("],\"failedUrls\":[");
        first = true;
        for (String u : r.failedUrls) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"").append(json(u)).append("\"");
        }
        sb.append("]}");
        System.out.println(sb.toString());
        System.out.println("SUMMARY " + SiteDownloader.summarize(r));
    }

    private static String json(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    private static void deleteRecursively(java.io.File f) {
        java.io.File[] children = f.listFiles();
        if (children != null) for (java.io.File c : children) deleteRecursively(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}

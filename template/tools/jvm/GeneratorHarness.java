import com.taniniya.webtoapp.ApkCore;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ApkCore をデスクトップ JVM で直接動かすテストハーネス。
 *
 * アプリ内の ApkGenerator と同じ ApkCore.build() を通るため、
 * 「端末で生成される APK と同一のもの」を PC 上で再現して検証できる。
 *
 * usage: java -cp <classes>:<apksig.jar> GeneratorHarness \
 *            <template.apk> <outDir> <debug.keystore> [webDir]
 */
public class GeneratorHarness {

    public static void main(String[] args) throws Exception {
        Path templatePath = Paths.get(args[0]);
        Path outDir = Paths.get(args[1]);
        Path keystore = Paths.get(args[2]);
        Path webDir = args.length > 3 ? Paths.get(args[3]) : null;
        Files.createDirectories(outDir);
        byte[] template = Files.readAllBytes(templatePath);
        System.out.println("template: " + template.length + " bytes");
        Path offlineSite = args.length > 4 ? Paths.get(args[4]) : null;

        // ---------- ケース A: URL モード / minSdk 21 (Android 5.0) ----------
        ApkCore.Spec a = new ApkCore.Spec();
        a.templateApk = template;
        a.url = "https://taniniya.github.io/tanishi-survivor/";
        a.appName = "タニシ・サバイバー";
        a.packageName = "com.webtoapp.tanishisurvivor";
        a.adBlock = false;
        a.fullscreen = true;
        a.keepScreenOn = false;   // v2.2: 常時点灯は既定 OFF
        a.pullToRefresh = true;
        a.progressBar = true;
        a.desktopMode = false;
        a.darkMode = "auto";
        a.zoomEnabled = true;
        a.orientation = "auto";
        a.minSdk = 21;
        a.targetSdk = 34;
        try (InputStream ks = new FileInputStream(keystore.toFile())) {
            a.keystore = ks;
            byte[] apk = ApkCore.build(a, step -> System.out.println("  [A] " + step));
            Files.write(outDir.resolve("url-mode-min21.apk"), apk);
            System.out.println("A: url-mode-min21.apk -> " + apk.length + " bytes");
        }

        // ---------- ケース B: ローカル HTML / CSS / JS モード ----------
        Map<String, byte[]> web = new LinkedHashMap<>();
        if (webDir != null && Files.isDirectory(webDir)) {
            Files.walk(webDir).filter(Files::isRegularFile).forEach(p -> {
                String rel = webDir.relativize(p).toString().replace('\\', '/');
                try {
                    web.put(rel, Files.readAllBytes(p));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            web.put("index.html", "<html><head><title>テスト</title></head><body>hi</body></html>"
                    .getBytes(StandardCharsets.UTF_8));
        }

        ApkCore.Spec b = new ApkCore.Spec();
        b.templateApk = template;
        b.appName = "オフライン計算機";
        b.packageName = "com.webtoapp.offlinecalc";
        b.adBlock = false;
        b.fullscreen = true;
        b.keepScreenOn = true;
        b.zoomEnabled = false;
        b.orientation = "portrait";
        b.minSdk = 26;
        b.targetSdk = 33;
        b.webAssets = web;
        try (InputStream ks = new FileInputStream(keystore.toFile())) {
            b.keystore = ks;
            byte[] apk = ApkCore.build(b, step -> System.out.println("  [B] " + step));
            Files.write(outDir.resolve("local-html-min26.apk"), apk);
            System.out.println("B: local-html-min26.apk -> " + apk.length + " bytes");
        }

        // ---------- ケース C: 広告ブロック ON / 横画面 / Android 14 下限 ----------
        ApkCore.Spec c = new ApkCore.Spec();
        c.templateApk = template;
        c.url = "taniniya.github.io/bananahome/";
        c.appName = "バナナサーバー";
        c.packageName = "com.webtoapp.banana";
        c.adBlock = true;
        c.fullscreen = false;
        c.keepScreenOn = false;
        c.zoomEnabled = true;
        c.orientation = "landscape";
        c.pullToRefresh = false;
        c.progressBar = false;
        c.desktopMode = true;
        c.darkMode = "on";
        c.minSdk = 34;
        c.targetSdk = 31; // 下限より小さいので 34 に補正されるはず
        try (InputStream ks = new FileInputStream(keystore.toFile())) {
            c.keystore = ks;
            byte[] apk = ApkCore.build(c, step -> System.out.println("  [C] " + step));
            Files.write(outDir.resolve("edge-min34.apk"), apk);
            System.out.println("C: edge-min34.apk -> " + apk.length + " bytes");
        }

        // ---------- ケース D: アイコン差し替え ----------
        Map<Integer, byte[]> icons = new LinkedHashMap<>();
        Path iconDir = outDir.resolve("icons");
        Files.createDirectories(iconDir);
        int[] sizes = {48, 72, 96, 144, 192};
        for (int px : sizes) {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    px, px, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(new java.awt.Color(220, 60, 40));
            g.fillRect(0, 0, px, px);
            g.setColor(java.awt.Color.WHITE);
            g.fillOval(px / 4, px / 4, px / 2, px / 2);
            g.dispose();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", bos);
            byte[] png = bos.toByteArray();
            icons.put(px, png);
            Files.write(iconDir.resolve(px + ".png"), png);
        }

        ApkCore.Spec d = new ApkCore.Spec();
        d.templateApk = template;
        d.url = "https://example.com/";
        d.appName = "アイコン付き";
        d.packageName = "com.webtoapp.withicon";
        d.icons = icons;
        d.keepScreenOn = false;
        d.minSdk = 21;
        d.targetSdk = 34;
        try (InputStream ks = new FileInputStream(keystore.toFile())) {
            d.keystore = ks;
            byte[] apk = ApkCore.build(d, step -> System.out.println("  [D] " + step));
            Files.write(outDir.resolve("icon.apk"), apk);
            System.out.println("D: icon.apk -> " + apk.length + " bytes");
        }

        // ---------- ケース E: URL からダウンロードしたサイトのオフライン APK ----------
        if (offlineSite != null && Files.isDirectory(offlineSite)) {
            caseOfflineSite(template, keystore, outDir, offlineSite);
        }

        System.out.println("done");
    }

    /** URL からダウンロードしたサイト (SiteDownloader の出力) をオフライン APK にする。 */
    private static void caseOfflineSite(byte[] template, Path keystore, Path outDir, Path siteDir)
            throws Exception {
        Map<String, byte[]> assets = new LinkedHashMap<>();
        Files.walk(siteDir).filter(Files::isRegularFile).forEach(p -> {
            String rel = siteDir.relativize(p).toString().replace('\\', '/');
            try {
                assets.put(rel, Files.readAllBytes(p));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.out.println("offline site assets: " + assets.size() + " files");

        ApkCore.Spec e = new ApkCore.Spec();
        e.templateApk = template;
        e.appName = "オフラインサイト";
        e.packageName = "com.webtoapp.offlinesite";
        e.webAssets = assets;
        e.keepScreenOn = false;      // v2.2 の既定
        e.pullToRefresh = true;
        e.progressBar = false;
        e.desktopMode = true;
        e.darkMode = "on";
        e.orientation = "auto";
        e.minSdk = 21;
        e.targetSdk = 34;
        try (InputStream ks = new FileInputStream(keystore.toFile())) {
            e.keystore = ks;
            byte[] apk = ApkCore.build(e, step -> System.out.println("  [E] " + step));
            Files.write(outDir.resolve("offline-site.apk"), apk);
            System.out.println("E: offline-site.apk -> " + apk.length + " bytes");
        }
    }
}

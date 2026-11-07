package com.webtoapp.standalone;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 単独動作する超軽量 WebView アプリ (テンプレート / v2.2)。
 *
 * assets/app_config.json の内容に従って動作する:
 *   url           表示する URL (ローカルアプリでは file:///android_asset/web/index.html)
 *   appName       アプリ名 (タイトル)
 *   adBlock       広告ブロック (Beta / 既定 false)
 *   fullscreen    全画面表示 (既定 true)
 *   keepScreenOn  画面の常時点灯 (既定 false ← v2.2 で変更)
 *   zoomEnabled   ピンチズーム許可 (既定 true)
 *   orientation   auto | portrait | landscape
 *   pullToRefresh 引っ張って更新 (既定 true)
 *   progressBar   ページ読み込み進捗バー (既定 true)
 *   desktopMode   PC 版 User-Agent で表示 (既定 false)
 *   darkMode      auto | on | off (既定 auto)
 *
 * 起動時にクラッシュしないことを最優先に、システムバーの制御は
 * setContentView() 後・onWindowFocusChanged() で行い、全て null チェックと
 * try-catch で保護している。
 */
public class MainActivity extends Activity {

    private WebView webView;
    private PullToRefreshLayout pullLayout;
    private ProgressBar progressBar;

    private boolean adBlock = false;
    private boolean fullscreen = true;
    private boolean keepScreenOn = false;
    private boolean pullToRefresh = true;
    private boolean showProgress = true;
    private boolean desktopMode = false;
    private String darkMode = "auto";
    private String homeUrl = "about:blank";

    private static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            init(savedInstanceState);
        } catch (Throwable t) {
            // 起動時の想定外エラーでもアプリを落とさず、内容を画面に出す
            showErrorScreen(t);
        }
    }

    private void init(Bundle savedInstanceState) {
        JSONObject config = loadConfig();
        String url = config.optString("url", "about:blank").trim();
        if (url.isEmpty()) url = "about:blank";
        homeUrl = url;
        adBlock = config.optBoolean("adBlock", false);
        fullscreen = config.optBoolean("fullscreen", true);
        keepScreenOn = config.optBoolean("keepScreenOn", false);
        boolean zoomEnabled = config.optBoolean("zoomEnabled", true);
        pullToRefresh = config.optBoolean("pullToRefresh", true);
        showProgress = config.optBoolean("progressBar", true);
        desktopMode = config.optBoolean("desktopMode", false);
        darkMode = normalizeDarkMode(config.optString("darkMode", "auto"));
        String orientation = config.optString("orientation", "auto");
        String appName = config.optString("appName", "");
        if (!appName.isEmpty()) setTitle(appName);

        applyOrientation(orientation);

        try {
            buildWebView(url, zoomEnabled, savedInstanceState);
        } catch (Throwable t) {
            // WebView の生成自体に失敗してもアプリが落ちないようにする
            showErrorScreen(t);
        }

        // システムバーの制御はビューが付いた後に行う (DecorView 未生成時の NPE 対策)
        applyFullscreen();
        applyKeepScreenOn();
        applyDarkMode();
    }

    private static String normalizeDarkMode(String value) {
        if (value == null) return "auto";
        String v = value.trim().toLowerCase();
        if (v.equals("on") || v.equals("off") || v.equals("auto")) return v;
        return "auto";
    }

    private void applyOrientation(String orientation) {
        try {
            if ("portrait".equalsIgnoreCase(orientation)) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            } else if ("landscape".equalsIgnoreCase(orientation)) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        } catch (Throwable ignored) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void buildWebView(String url, boolean zoomEnabled, Bundle savedInstanceState) {
        FrameLayout root = new FrameLayout(this);

        pullLayout = new PullToRefreshLayout(this);
        root.addView(pullLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        webView = new WebView(this);
        pullLayout.setContent(webView);

        if (pullToRefresh) {
            ProgressBar spinner = new ProgressBar(this);
            pullLayout.setIndicator(spinner);
            pullLayout.setPullEnabled(true);
            pullLayout.setOnRefreshListener(() -> {
                try {
                    if (webView != null) webView.reload();
                } catch (Throwable ignored) {}
            });
        } else {
            pullLayout.setPullEnabled(false);
        }

        // ページ読み込みの進捗バー (画面上端の細いバー)
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setIndeterminate(false);
        progressBar.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(2, dp(3)), Gravity.TOP);
        root.addView(progressBar, plp);

        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(zoomEnabled);
        s.setBuiltInZoomControls(zoomEnabled);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportMultipleWindows(false);
        try {
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
        } catch (Throwable ignored) {}

        // PC 表示 (デスクトップ) モード
        if (desktopMode) {
            try {
                s.setUserAgentString(UA_DESKTOP);
                s.setLoadWithOverviewMode(true);
                s.setUseWideViewPort(true);
            } catch (Throwable ignored) {}
        }

        // ローカル (HTML / CSS / JS) アプリを完全オフラインで動かすための設定
        try {
            s.setAllowFileAccess(true);
            s.setAllowContentAccess(true);
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
        } catch (Throwable ignored) {}

        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(webView, true);
            }
        } catch (Throwable ignored) {}

        webView.setBackgroundColor(Color.WHITE);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return handleUrl(uri);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String urlString) {
                return handleUrl(Uri.parse(urlString));
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (adBlock) {
                    try {
                        String u = request.getUrl().toString();
                        if (AdBlocker.isAd(u)) return AdBlocker.emptyResponse();
                    } catch (Throwable ignored) {}
                }
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                try {
                    if (showProgress && progressBar != null) {
                        progressBar.setProgress(0);
                        progressBar.setVisibility(View.VISIBLE);
                    }
                } catch (Throwable ignored) {}
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                try {
                    if (progressBar != null) {
                        progressBar.setProgress(100);
                        progressBar.setVisibility(View.GONE);
                    }
                    if (pullLayout != null) pullLayout.finishRefresh();
                } catch (Throwable ignored) {}
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                try {
                    if (Build.VERSION.SDK_INT >= 23 && request != null && request.isForMainFrame()) {
                        showLoadError(request.getUrl() == null ? "" : request.getUrl().toString(),
                                String.valueOf(error == null ? "" : error.getDescription()));
                    }
                } catch (Throwable ignored) {}
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // API 22 以下はこちらが呼ばれる
                if (Build.VERSION.SDK_INT < 23) showLoadError(failingUrl, description);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (!showProgress || progressBar == null) return;
                try {
                    if (newProgress >= 100) {
                        progressBar.setProgress(100);
                        progressBar.setVisibility(View.GONE);
                    } else {
                        progressBar.setVisibility(View.VISIBLE);
                        progressBar.setProgress(newProgress);
                    }
                } catch (Throwable ignored) {}
            }
        });

        // ダウンロードリンクは端末のブラウザに任せる
        webView.setDownloadListener((downloadUrl, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)));
            } catch (Throwable ignored) {}
        });

        // 再生成時は履歴を復元する (ローカルファイルアプリでは assets を確実に読み直す)
        if (savedInstanceState != null && !url.startsWith("file:///android_asset/")) {
            try {
                if (webView.restoreState(savedInstanceState) != null) return;
            } catch (Throwable ignored) {}
        }
        webView.loadUrl(url);
    }

    /**
     * WebView 内で開いてよい URL なら false (= 遷移を WebView に任せる)、
     * 外部アプリに渡すなら true を返す。
     */
    private boolean handleUrl(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        // WebView 内で処理するスキーム (file: / about: を含めるのが v2.1 の修正点)
        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("file")
                || scheme.equals("about") || scheme.equals("data")
                || scheme.equals("javascript") || scheme.equals("blob")
                || scheme.isEmpty()) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {}
        return true;
    }

    private JSONObject loadConfig() {
        try {
            InputStream in = getAssets().open("app_config.json");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new JSONObject(new String(out.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void applyFullscreen() {
        if (!fullscreen) return;
        try {
            Window w = getWindow();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                w.setDecorFitsSystemWindows(false);
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                w.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN);
                View decor = w.getDecorView();
                if (decor != null) {
                    decor.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                }
            }
        } catch (Throwable ignored) {
            // システムバーを隠せない端末でもアプリは動かす
        }
    }

    private void applyKeepScreenOn() {
        try {
            if (keepScreenOn) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        } catch (Throwable ignored) {}
    }

    /** ダークモード (auto / on / off) を反映する。 */
    private void applyDarkMode() {
        boolean dark;
        if ("on".equals(darkMode)) {
            dark = true;
        } else if ("off".equals(darkMode)) {
            dark = false;
        } else {
            dark = (getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        }
        int bg = dark ? 0xFF121212 : Color.WHITE;
        try {
            Window w = getWindow();
            if (w != null) {
                View decor = w.getDecorView();
                if (decor != null) decor.setBackgroundColor(bg);
            }
        } catch (Throwable ignored) {}
        if (webView == null) return;
        try {
            webView.setBackgroundColor(bg);
            WebSettings s = webView.getSettings();
            if (Build.VERSION.SDK_INT >= 33) {
                s.setAlgorithmicDarkeningAllowed(dark);
            } else if (Build.VERSION.SDK_INT >= 29) {
                s.setForceDark(dark ? WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_OFF);
            }
        } catch (Throwable ignored) {}
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** 読み込み失敗時に、原因と再試行リンクを出す。 */
    private void showLoadError(String failingUrl, String description) {
        if (failingUrl == null || !failingUrl.startsWith("http")) return;
        if (webView == null) return;
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>body{font-family:sans-serif;padding:28px;color:#333;background:#fafafa}"
                + "h2{font-size:18px}p{font-size:14px;line-height:1.6;color:#666}"
                + "a{display:inline-block;margin-top:18px;padding:12px 22px;background:#3f51b5;"
                + "color:#fff;text-decoration:none;border-radius:8px;font-size:15px}</style></head><body>"
                + "<h2>ページを読み込めませんでした</h2>"
                + "<p>ネットワーク接続を確認してください。<br>"
                + escapeHtml(description) + "</p>"
                + "<p style=\"word-break:break-all\">" + escapeHtml(failingUrl) + "</p>"
                + "<a href=\"" + escapeHtml(failingUrl) + "\">再試行</a>"
                + "</body></html>";
        try {
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        } catch (Throwable ignored) {}
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void showErrorScreen(Throwable t) {
        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(48, 48, 48, 48);
            TextView tv = new TextView(this);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setText("このアプリを表示できませんでした。\n\n"
                    + "端末の「Android System WebView」が有効か確認してください。\n\n"
                    + String.valueOf(t));
            root.addView(tv);

            // 再試行ボタン
            TextView retry = new TextView(this);
            retry.setText("\n▶ 再試行");
            retry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            retry.setPadding(0, 24, 0, 24);
            retry.setOnClickListener(v -> recreate());
            root.addView(retry);

            setContentView(root);
        } catch (Throwable ignored) {
            // ここまで失敗する端末では何もできない
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // uiMode の変更 (ライト / ダーク切り替え) にも追従する
        applyDarkMode();
        applyFullscreen();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            if (webView != null) webView.saveState(outState);
        } catch (Throwable ignored) {}
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        applyFullscreen();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.destroy();
            } catch (Throwable ignored) {}
            webView = null;
        }
        super.onDestroy();
    }
}

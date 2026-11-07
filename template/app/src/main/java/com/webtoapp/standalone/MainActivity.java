package com.webtoapp.standalone;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 単独動作する超軽量 WebView アプリ (テンプレート / v2.1)。
 *
 * assets/app_config.json の内容に従って動作する:
 *   url          表示する URL (ローカルアプリでは file:///android_asset/web/index.html)
 *   appName      アプリ名 (タイトル)
 *   adBlock      広告ブロック (Beta / 既定 false)
 *   fullscreen   全画面表示 (既定 true)
 *   keepScreenOn 画面の常時点灯 (既定 true)
 *   zoomEnabled  ピンチズーム許可 (既定 true)
 *   orientation  auto | portrait | landscape
 *
 * 起動時にクラッシュしないことを最優先に、システムバーの制御は
 * setContentView() 後・onWindowFocusChanged() で行い、全て null チェックと
 * try-catch で保護している。
 */
public class MainActivity extends Activity {

    private WebView webView;
    private boolean adBlock = false;
    private boolean fullscreen = true;
    private boolean keepScreenOn = true;

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
        adBlock = config.optBoolean("adBlock", false);
        fullscreen = config.optBoolean("fullscreen", true);
        keepScreenOn = config.optBoolean("keepScreenOn", true);
        boolean zoomEnabled = config.optBoolean("zoomEnabled", true);
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
        webView = new WebView(this);
        setContentView(webView, new ViewGroup.LayoutParams(
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
        });

        webView.setWebChromeClient(new WebChromeClient());

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

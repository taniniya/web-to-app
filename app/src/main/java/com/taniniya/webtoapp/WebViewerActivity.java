package com.taniniya.webtoapp;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/** アドレスバーなしの独立アプリ画面としてWebサイトを表示する。 */
public class WebViewerActivity extends AppCompatActivity {

    public static final String EXTRA_JSON = "app_json";

    private WebView webView;
    private SwipeRefreshLayout refresh;
    private ProgressBar progress;
    private WebApp app;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        app = WebApp.fromJsonString(getIntent().getStringExtra(EXTRA_JSON));
        if (app == null) {
            Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setTitle(app.name);

        setContentView(R.layout.activity_web_viewer);
        webView = findViewById(R.id.webview);
        refresh = findViewById(R.id.refresh);
        progress = findViewById(R.id.progress);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (app.adBlock && AdBlocker.isAd(request.getUrl().toString())) {
                    return AdBlocker.emptyResponse();
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                return !"http".equals(scheme) && !"https".equals(scheme);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                refresh.setRefreshing(false);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }
        });

        refresh.setOnRefreshListener(() -> webView.reload());
        webView.getViewTreeObserver().addOnScrollChangedListener(() ->
                refresh.setEnabled(webView.getScrollY() == 0));

        if (app.fullscreen) immersive();

        webView.loadUrl(app.url);
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && app != null && app.fullscreen) immersive();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(app.name)
                .setMessage(R.string.exit_confirm)
                .setPositiveButton(R.string.exit, (d, w) -> finish())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}

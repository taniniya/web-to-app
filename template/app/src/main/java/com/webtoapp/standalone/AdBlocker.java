package com.webtoapp.standalone;

import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 軽量アドブロッカー: 主要な広告配信ドメインへのリクエストを遮断する。 */
public final class AdBlocker {

    private static final Set<String> AD_DOMAINS = new HashSet<>(Arrays.asList(
            // Google 系
            "doubleclick.net",
            "googleads.g.doubleclick.net",
            "pagead2.googlesyndication.com",
            "googlesyndication.com",
            "adservice.google.com",
            "adservice.google.co.jp",
            "googleadservices.com",
            "admob.com",
            "2mdn.net",
            "google-analytics.com",
            "googletagmanager.com",
            // 大手アドネットワーク
            "adnxs.com",
            "adsrvr.org",
            "rubiconproject.com",
            "pubmatic.com",
            "openx.net",
            "criteo.com",
            "criteo.net",
            "casalemedia.com",
            "smartadserver.com",
            "adform.net",
            "teads.tv",
            "sharethrough.com",
            "indexww.com",
            "media.net",
            "amazon-adsystem.com",
            "bidswitch.net",
            "adsco.re",
            "yieldmo.com",
            "moatads.com",
            "moatpixel.com",
            // 計測・トラッカー
            "scorecardresearch.com",
            "quantserve.com",
            "imrworldwide.com",
            "crwdcntrl.net",
            "exelator.com",
            "agkn.com",
            "bluekai.com",
            "demdex.net",
            "omtrdc.net",
            "omtrdc.net",
            "everesttech.net",
            // ネイティブ広告
            "outbrain.com",
            "taboola.com",
            "zergnet.com",
            "mgid.com",
            // ポップアップ系
            "popads.net",
            "popcash.net",
            "popmyads.com",
            "adcash.com",
            "propellerads.com",
            "propellerclick.com",
            "adsterra.com",
            "adcashrevenue.com",
            // モバイル広告
            "adcolony.com",
            "applovin.com",
            "chartboost.com",
            "inmobi.com",
            "mopub.com",
            "unityads.unity3d.com",
            "vungle.com",
            "tapjoy.com",
            "ironsrc.com",
            // 海外検索・その他
            "an.yandex.ru",
            "ad.mail.ru",
            "adf.ly",
            "onclickads.net",
            "adskeeper.co.uk",
            "revcontent.com"
    ));

    private AdBlocker() {}

    /** URL が広告ドメインなら true。 */
    public static boolean isAd(String url) {
        if (url == null) return false;
        String host;
        try {
            host = Uri.parse(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String ad : AD_DOMAINS) {
            if (host.equals(ad) || host.endsWith("." + ad)) return true;
        }
        return false;
    }

    /** 空のレスポンスを返してリクエストを無効化する。 */
    public static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream(new byte[0]));
    }
}

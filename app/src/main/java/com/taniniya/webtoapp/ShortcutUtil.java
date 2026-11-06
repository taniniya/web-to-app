package com.taniniya.webtoapp;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.widget.Toast;

/** ホーム画面へのショートカット（ピン留め）を追加するユーティリティ。 */
public final class ShortcutUtil {

    private ShortcutUtil() {}

    public static void requestPin(Context context, WebApp app) {
        ShortcutManager sm = context.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(context, R.string.pin_unsupported, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClassName(context, WebViewerActivity.class.getName());
        intent.putExtra(WebViewerActivity.EXTRA_JSON, app.toJsonString());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ShortcutInfo info = new ShortcutInfo.Builder(context, "pin-" + app.id)
                .setShortLabel(app.name)
                .setIcon(Icon.createWithBitmap(icon(app)))
                .setIntent(intent)
                .build();
        boolean ok = sm.requestPinShortcut(info, null);
        if (!ok) {
            Toast.makeText(context, R.string.pin_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** アプリ色の円に頭文字を描いたアイコン画像を作る。 */
    public static Bitmap icon(WebApp app) {
        int size = 192;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(app.color);
        c.drawCircle(size / 2f, size / 2f, size / 2f - 8f, p);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(size * 0.45f);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = size / 2f - (fm.ascent + fm.descent) / 2f;
        String label = (app.name == null || app.name.isEmpty())
                ? "W" : app.name.substring(0, 1).toUpperCase();
        c.drawText(label, size / 2f, baseline, p);
        return bmp;
    }
}

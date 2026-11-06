package com.taniniya.webtoapp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** SharedPreferences に JSON 形式でWebアプリ一覧を永続化する。 */
public class WebAppStorage {
    private static final String KEY = "webapps";
    private static final String KEY_PRESET = "preset_added";
    private static final String PRESET_ID = "preset-tanishi-survivor";

    private final SharedPreferences prefs;

    public WebAppStorage(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences("webtoapp", Context.MODE_PRIVATE);
    }

    public List<WebApp> load() {
        List<WebApp> out = new ArrayList<>();
        String raw = prefs.getString(KEY, null);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    WebApp a = WebApp.fromJson(arr.getJSONObject(i));
                    if (a != null && a.url != null && !a.url.isEmpty()) out.add(a);
                }
            } catch (JSONException ignored) {
            }
        }
        if (!prefs.getBoolean(KEY_PRESET, false)) {
            out.add(0, preset());
            save(out);
            prefs.edit().putBoolean(KEY_PRESET, true).apply();
        }
        return out;
    }

    private static WebApp preset() {
        WebApp a = new WebApp();
        a.id = PRESET_ID;
        a.name = "タニシ・サバイバー";
        a.url = "https://taniniya.github.io/tanishi-survivor/";
        a.packageName = "com.taniniya.tanishisurvivor";
        a.adBlock = true;
        a.fullscreen = false;
        a.color = ColorPalette.COLORS[0];
        return a;
    }

    public void save(List<WebApp> apps) {
        JSONArray arr = new JSONArray();
        for (WebApp a : apps) arr.put(a.toJsonObject());
        prefs.edit().putString(KEY, arr.toString()).apply();
    }

    public void upsert(WebApp app) {
        List<WebApp> apps = load();
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).id.equals(app.id)) {
                apps.set(i, app);
                save(apps);
                return;
            }
        }
        apps.add(app);
        save(apps);
    }

    public void delete(String id) {
        List<WebApp> apps = load();
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).id.equals(id)) {
                apps.remove(i);
                save(apps);
                return;
            }
        }
    }
}

package com.taniniya.webtoapp;

import org.json.JSONException;
import org.json.JSONObject;

/** 1つのWebアプリ設定。 */
public class WebApp {
    public String id;
    public String name;
    public String url;
    public String packageName;
    public boolean adBlock;
    public boolean fullscreen;
    public int color;

    public WebApp() {}

    public JSONObject toJsonObject() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("url", url);
            o.put("packageName", packageName);
            o.put("adBlock", adBlock);
            o.put("fullscreen", fullscreen);
            o.put("color", color);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public String toJsonString() {
        return toJsonObject().toString();
    }

    public static WebApp fromJsonString(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return fromJson(new JSONObject(raw));
        } catch (JSONException e) {
            return null;
        }
    }

    public static WebApp fromJson(JSONObject o) {
        WebApp a = new WebApp();
        a.id = o.optString("id", String.valueOf(System.currentTimeMillis()));
        a.name = o.optString("name", "");
        a.url = o.optString("url", "");
        a.packageName = o.optString("packageName", "com.taniniya.app");
        a.adBlock = o.optBoolean("adBlock", true);
        a.fullscreen = o.optBoolean("fullscreen", false);
        a.color = o.optInt("color", 0xFF3F51B5);
        return a;
    }
}

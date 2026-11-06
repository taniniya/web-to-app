package com.taniniya.webtoapp;

/** アイコン色のパレット。 */
public final class ColorPalette {
    public static final int[] COLORS = {
            0xFF3F51B5, // 青
            0xFF00897B, // 青緑
            0xFFEF6C00, // オレンジ
            0xFF7B1FA2, // 紫
            0xFFC62828, // 赤
            0xFF0288D1  // 水色
    };

    public static final int[] LABELS = {
            R.string.color_blue,
            R.string.color_teal,
            R.string.color_orange,
            R.string.color_purple,
            R.string.color_red,
            R.string.color_cyan
    };

    private ColorPalette() {}

    public static int indexOfColor(int color) {
        for (int i = 0; i < COLORS.length; i++) {
            if (COLORS[i] == color) return i;
        }
        return 0;
    }
}

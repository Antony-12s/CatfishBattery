package com.catfish.battery;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Colours, sizes and drawables of the native Status page. Day is the approved WebView daylight look; night is Midnight
 * Navy Cyan (BatteryWindowStyle champagneNight(), name kept). Base colours come from {@link UiPalette}; sizes are the window CSS
 * px of BatteryWindowStyle css(), which are dp here (the car runs at density 160, so 1 dp = 1 px).
 */
final class StatusStyle {
    final boolean night;
    final UiPalette palette;
    final int accent, onAccent, text, muted, muted2, line, lineSoft, cardTop, cardBottom, cardBorder;
    final int good, warn, danger, bgDeep, surface, surface2, panelTop, panelBottom, panelBorder;
    private final float density;

    StatusStyle(Context context) {
        palette = UiPalette.of(context);
        night = palette.night;
        density = context.getResources().getDisplayMetrics().density;
        UiPalette p = palette;
        bgDeep = p.bgDeep;
        surface = p.surface;
        surface2 = p.surface2;
        danger = p.danger;
        if (night) {
            accent = 0xFF4CC9F0;
            onAccent = 0xFF04202B;
            text = 0xFFE3EEF7;
            muted = 0xFF86A0B5;
            muted2 = 0xFF6E879B;
            line = 0x1F4CC9F0;
            lineSoft = 0x124CC9F0;
            cardTop = 0xFF0D1A26;
            cardBottom = 0xFF0A1520;
            cardBorder = 0x214CC9F0;
            good = 0xFF85DAA4;
            warn = 0xFFF2B86D;
            panelTop = 0xFF10202E;
            panelBottom = 0xFF050C13;
            panelBorder = 0x144CC9F0;
        } else {
            accent = p.accent;
            onAccent = 0xFFFFFFFF;
            text = p.text;
            muted = p.muted;
            muted2 = 0xFF4D647A;
            line = p.line;
            lineSoft = 0xFFD5DFE9;
            cardTop = 0xFFFFFFFF;
            cardBottom = 0xFFFFFFFF;
            cardBorder = p.line;
            good = p.good;
            warn = p.warn;
            panelTop = p.bgDeep;
            panelBottom = p.bgDeep;
            panelBorder = p.line;
        }
    }

    int px(float dp) {
        return Math.round(dp * density);
    }

    int band(int level) {
        return level == CellsModel.DANGER ? danger : level == CellsModel.WARN ? warn : level == CellsModel.GOOD ? good : muted2;
    }

    static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0xFFFFFF);
    }

    static Typeface weight(int w) {
        return Typeface.create(Typeface.DEFAULT, w, false);
    }

    TextView text(Context c, float sizeDp, int weight, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        t.setTypeface(weight(weight));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    GradientDrawable round(int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(px(radiusDp));
        if (strokeDp > 0) d.setStroke(Math.max(1, px(strokeDp)), stroke);
        return d;
    }

    GradientDrawable card() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { cardTop, cardBottom });
        d.setCornerRadius(px(24));
        d.setStroke(Math.max(1, px(1)), cardBorder);
        return d;
    }

    /** The schematic panel: night is the navy radial substrate, day a light panel (backlog 4). */
    GradientDrawable panel() {
        GradientDrawable d = new GradientDrawable();
        if (night) {
            d.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            d.setColors(new int[] { panelTop, 0xFF08121B, panelBottom });
            d.setGradientRadius(px(1300));
            d.setGradientCenter(0.5f, 0.45f);
        } else {
            d.setColor(panelTop);
        }
        d.setCornerRadius(px(20));
        d.setStroke(Math.max(1, px(1)), panelBorder);
        return d;
    }
}

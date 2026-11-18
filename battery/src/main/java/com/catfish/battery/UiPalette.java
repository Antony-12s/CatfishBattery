package com.catfish.battery;

import android.content.Context;
import android.content.res.Configuration;

/**
 * Day and night colours for the native pages, taken from the approved WebView look (BatteryWindowStyle css()).
 * Night pages may still apply the Midnight Champagne accents (BatteryWindowStyle champagneNight()).
 */
final class UiPalette {
    final boolean night;
    final int bg, bgDeep, surface, surface2, line, text, muted, accent, good, warn, danger;

    private UiPalette(boolean night, int bg, int bgDeep, int surface, int surface2, int line, int text, int muted,
                      int accent, int good, int warn, int danger) {
        this.night = night;
        this.bg = bg;
        this.bgDeep = bgDeep;
        this.surface = surface;
        this.surface2 = surface2;
        this.line = line;
        this.text = text;
        this.muted = muted;
        this.accent = accent;
        this.good = good;
        this.warn = warn;
        this.danger = danger;
    }

    static UiPalette of(boolean night) {
        return night
                ? new UiPalette(true, 0xFF20272E, 0xFF0B1118, 0xFF111A24, 0xFF172230, 0xFF304255, 0xFFE8EEF5,
                        0xFFA7B6C8, 0xFF2A74D6, 0xFF4FE09A, 0xFFFFB55F, 0xFFFF6C79)
                : new UiPalette(false, 0xFFEDF2F7, 0xFFE7EEF5, 0xFFFFFFFF, 0xFFF0F5FA, 0xFFB8C7D7, 0xFF142A40,
                        0xFF445D75, 0xFF2368C5, 0xFF178657, 0xFF9A5A10, 0xFFB83C4B);
    }

    static UiPalette of(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return of(mode == Configuration.UI_MODE_NIGHT_YES);
    }
}

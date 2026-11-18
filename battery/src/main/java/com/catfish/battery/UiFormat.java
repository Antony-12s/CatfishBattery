package com.catfish.battery;

import java.util.Locale;

/**
 * Number formats shared by every native page and view, so the same reading never shows two ways.
 * Mirrors the WebView helpers (fx/fmt/kw/mins in BatteryWebPages). Missing values are "--", never 0.
 */
final class UiFormat {
    static final String MISSING = "--";

    private UiFormat() { }

    /** Value with a fixed number of decimals and a unit suffix; minus is U+2212 like the original pages. */
    static String fixed(double value, int decimals, String suffix) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return MISSING;
        return String.format(Locale.ROOT, "%." + decimals + "f", value).replace('-', '\u2212') + suffix;
    }

    /** Time left: under an hour in minutes ("45 min"), from an hour as hours and minutes ("11 h 55 min"). */
    static String duration(double minutes) {
        if (Double.isNaN(minutes) || Double.isInfinite(minutes) || minutes < 0) return MISSING;
        long total = Math.round(minutes);
        return total < 60 ? total + " min" : total / 60 + " h " + total % 60 + " min";
    }

    /** State of health with two decimals, "96.50 %" (canspi steps are 0.5 %, so the last digit is usually 0). */
    static String percent2(Double pct) {
        return pct == null ? MISSING : fixed(pct, 2, " %");
    }

    /** Power in W below 1 kW, kW with one decimal below 100 kW, whole kW above (BatteryWebPages kw()). */
    static String power(double watts) {
        if (Double.isNaN(watts) || Double.isInfinite(watts)) return MISSING;
        double a = Math.abs(watts);
        if (a >= 100000) return fixed(watts / 1000, 0, " kW");
        if (a >= 1000) return fixed(watts / 1000, 1, " kW");
        return fixed(Math.round(watts), 0, " W");
    }
}

package com.catfish.battery;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Status page text formats, ported from the WebView batteryScript (relAge, drawTotals, money) and kept pure Java so they
 * are unit tested. Generic number formats live in {@link UiFormat}; missing values are "--".
 */
final class StatusFormat {
    /** Warn level of a reading: the colour of a facts value. */
    static final int GOOD = 0, WARN = 1, DANGER = 2;

    private static final String[] MONTHS = "Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split(" ");

    private StatusFormat() { }

    /** "Updated" badge age: just now, 12s ago, 3m ago, 2h ago, 1d ago. */
    static String relAge(long ms) {
        if (ms < 0) return UiFormat.MISSING;
        long s = Math.round(ms / 1000.0);
        if (s < 2) return "just now";
        if (s < 60) return s + "s ago";
        long m = s / 60;
        if (m < 60) return m + "m ago";
        long h = m / 60;
        if (h < 24) return h + "h ago";
        return h / 24 + "d ago";
    }

    /** Energy total in Wh as kWh: two decimals below 100 kWh, one above. */
    static String kwh(Double wh) {
        if (wh == null) return UiFormat.MISSING;
        return UiFormat.fixed(wh / 1000, wh < 100000 ? 2 : 1, " kWh");
    }

    /** Start of the totals: "since 8 Nov 07:05", or "from boot" when the logger has no start. */
    static String since(long sinceMs, TimeZone zone) {
        if (sinceMs == 0) return "from boot";
        Calendar c = Calendar.getInstance(zone, Locale.ROOT);
        c.setTimeInMillis(sinceMs);
        return String.format(Locale.ROOT, "since %d %s %02d:%02d", c.get(Calendar.DAY_OF_MONTH),
                MONTHS[c.get(Calendar.MONTH)], c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** Money like the web pages: one decimal under 100, whole with thousands separators from 100. */
    static String money(Double value, String symbol) {
        if (value == null || value.isNaN() || value.isInfinite()) return UiFormat.MISSING;
        if (Math.abs(value) < 100) return symbol + UiFormat.fixed(value, 1, "");
        return symbol + String.format(Locale.ROOT, "%,d", Math.round(value)).replace('-', '\u2212');
    }

    /** Price per kWh, "2.50 ฿/kWh". */
    static String rate(double price, String symbol) {
        return UiFormat.fixed(price, 2, " " + symbol + "/kWh");
    }

    /** Cell spread colour: warn from 20 mV, danger from 50 mV. */
    static int deltaLevel(Integer deltaMv) {
        if (deltaMv == null) return GOOD;
        return deltaMv >= 50 ? DANGER : deltaMv >= 20 ? WARN : GOOD;
    }

    /** Sensor colour, the same bands as the Sensors legend: under 35 °C good, 35-42 °C warn, above 42 °C danger. */
    static int tempLevel(Integer celsius) {
        if (celsius == null) return GOOD;
        return celsius > 42 ? DANGER : celsius >= 35 ? WARN : GOOD;
    }

    /** Whole-number reading with its unit, "--" when missing. */
    static String whole(Integer value, String suffix) {
        return value == null ? UiFormat.MISSING : UiFormat.fixed(value, 0, suffix);
    }
}

package com.catfish.battery;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The SOC sparkline in the schematic's HV PACK block. Its window follows the car: since plug-in while charging (with
 * the target SOC), since leaving Park while driving (the last hour when the start was not seen), the last 24 h when
 * parked or the gear is unknown. At least 15 min wide, and the value axis at least 5 % tall so a 0.4 % drop while
 * parked does not look like a cliff. Points come from BatteryDataHub.historyJson("24h") ([ts, soc, kw, temp, diff]).
 */
final class SocTrend {
    static final int PARK = 4;
    private static final long MIN = 60_000L, HOUR = 60 * MIN;

    final String label, fromText, delta;
    final long fromMs, toMs;
    final double[] ts, soc;
    final double lo, hi, target;

    private SocTrend(String label, String fromText, long fromMs, long toMs, double[] ts, double[] soc, double target,
                     String note) {
        this.label = label;
        this.fromText = fromText;
        this.fromMs = fromMs;
        this.toMs = toMs;
        this.ts = ts;
        this.soc = soc;
        this.target = target;
        double low = Arrays.stream(soc).min().orElse(0), high = Arrays.stream(soc).max().orElse(100);
        if (Double.isFinite(target)) high = Math.max(high, target);
        if (high - low < 5) {
            double mid = (high + low) / 2;
            low = mid - 2.5;
            high = mid + 2.5;
        }
        lo = Math.max(0, Math.floor(low - 1));
        hi = Math.min(100, Math.ceil(high + 1));
        if (soc.length < 2) { delta = ""; return; }
        double change = soc[soc.length - 1] - soc[0];
        delta = (change >= 0 ? "+" : "\u2212") + String.format(Locale.ROOT, "%.1f %%", Math.abs(change)) + note;
    }

    boolean empty() { return soc.length < 2; }

    static SocTrend of(JSONObject history, JSONObject data, long now, TimeZone zone) {
        JSONObject live = history == null ? null : history.optJSONObject("live");
        JSONObject flow = data == null ? null : data.optJSONObject("flow");
        Object gearValue = data == null ? null : data.opt("gear");
        Integer gear = gearValue instanceof Number ? ((Number) gearValue).intValue() : null;
        long leftPark = data == null ? 0 : data.optLong("leftParkMs", 0);
        String label, fromText, note = "";
        long from;
        double target = Double.NaN;
        if (live != null && live.optLong("start", 0) > 0) {
            from = live.optLong("start");
            label = "SOC · THIS CHARGE";
            fromText = "plugged " + clock(from, zone);
            double t = flow == null ? Double.NaN : flow.optDouble("targetSoc", Double.NaN);
            if (t > 0 && t <= 100) target = t;
            note = " · " + UiFormat.duration((now - from) / (double) MIN);
        } else if (gear != null && gear != PARK) {
            if (leftPark > 0 && leftPark <= now) {
                from = leftPark;
                label = "SOC · THIS TRIP";
                fromText = "left P " + clock(from, zone);
                note = " · " + UiFormat.duration((now - from) / (double) MIN);
            } else {
                from = now - HOUR;
                label = "SOC · 1 H";
                fromText = "\u22121 h";
            }
        } else {
            from = now - 24 * HOUR;
            label = "SOC · 24 H";
            fromText = "\u221224 h";
        }
        from = Math.min(from, now - 15 * MIN);
        JSONArray points = history == null ? null : history.optJSONArray("points");
        int n = points == null ? 0 : points.length();
        double[] ts = new double[n], soc = new double[n];
        int kept = 0;
        for (int i = 0; i < n; i++) {
            JSONArray p = points.optJSONArray(i);
            if (p == null) continue;
            long t = p.optLong(0, 0);
            Object v = p.opt(1);
            if (t < from || t > now || !(v instanceof Number)) continue;
            double s = ((Number) v).doubleValue();
            if (!(s >= 0 && s <= 100)) continue;
            ts[kept] = t;
            soc[kept++] = s;
        }
        return new SocTrend(label, fromText, from, now, Arrays.copyOf(ts, kept), Arrays.copyOf(soc, kept), target, note);
    }

    private static String clock(long ms, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.ROOT);
        f.setTimeZone(zone);
        return f.format(new Date(ms));
    }
}

package com.catfish.battery;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Text of the Charging page, ported from the WebView chargingScript: the range, the range's six totals tiles, the
 * live charge panel and the recent charges. Pure Java so it is tested on the JVM; ChargingPage only lays it out.
 */
final class ChargingModel {
    static final List<String> RANGES = Arrays.asList("1h", "6h", "24h", "7d", "30d");
    private static final String[] RANGE_LABELS = { "Last hour", "Last 6 hours", "Last 24 hours", "Last 7 days", "Last 30 days" };
    static final String[] DAYS = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
    static final String[] MONTHS = { "January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December" };

    private ChargingModel() { }

    /** A saved range, or the last 24 hours when there is none or it is unknown. */
    static String range(String saved) { return RANGES.contains(saved) ? saved : "24h"; }

    static String rangeLabel(String range) { return RANGE_LABELS[RANGES.indexOf(range(range))]; }

    /** "Last 24 hours · Tue 17 Nov – Wed 18 Nov": the range, and the days its samples span. */
    static String kicker(String range, JSONArray points, TimeZone tz) {
        String label = rangeLabel(range);
        if (points == null || points.length() < 2) return label;
        JSONArray first = points.optJSONArray(0), last = points.optJSONArray(points.length() - 1);
        if (first == null || last == null) return label;
        return label + " · " + when(first.optLong(0), true, tz) + " – " + when(last.optLong(0), true, tz);
    }

    /** "Wed 18 Nov · 14:05", or "Wed 18 Nov" for dates only. */
    static String when(long ts, boolean dateOnly, TimeZone tz) {
        Calendar c = cal(ts, tz);
        String day = day(c);
        return dateOnly ? day : day + " · " + hm(c);
    }

    static String day(Calendar c) {
        return DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH) + " "
                + MONTHS[c.get(Calendar.MONTH)].substring(0, 3);
    }

    static String hm(Calendar c) {
        return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    static Calendar cal(long ts, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ts);
        return c;
    }

    /** One stats tile: label, value, the line under it, and a tone ("" or "good"). */
    static final class Tile {
        final String label, value, sub, tone;
        Tile(String label, String value, String sub, String tone) {
            this.label = label;
            this.value = value;
            this.sub = sub;
            this.tone = tone;
        }
    }

    /** The range's totals, priced like the Energy flow tab: what the driving took, used minus regen. */
    static List<Tile> statsTiles(JSONObject st, ChargingCost cost) {
        if (st == null) st = new JSONObject();
        Double out = ChargingCost.num(st, "kwhOut"), in = ChargingCost.num(st, "kwhIn"),
                regen = ChargingCost.num(st, "kwhRegen"), lo = ChargingCost.num(st, "socMin"),
                hi = ChargingCost.num(st, "socMax"), price = cost.homeRate();
        Double net = out != null && regen != null ? out - regen : null;
        List<Tile> t = new ArrayList<>();
        t.add(new Tile("Used", kwh(out), "pack → loads", ""));
        t.add(new Tile("Charged", kwh(in), "charger → pack", ""));
        t.add(new Tile("Regen", kwh(regen), "braking energy", "good"));
        t.add(new Tile("Cost", cost.money(cost.driveCost(net)),
                price != null && price > 0 ? cost.rateText(price) + " · used − regen" : "set prices in Settings", ""));
        t.add(new Tile("SOC", lo != null && hi != null ? fixed(lo, 0, "") + "–" + fixed(hi, 0, "%") : UiFormat.MISSING,
                "lowest – highest", ""));
        t.add(new Tile("Cell diff", fixed(ChargingCost.num(st, "cellDiffMax"), 0, " mV"), "widest spread", ""));
        return t;
    }

    /** The live charge panel's facts; cost is "" when the charge has no price (the line hides). */
    static final class Live {
        final String kwNow, soc, kwhIn, cost, peak, eta80, eta100;
        Live(String kwNow, String soc, String kwhIn, String cost, String peak, String eta80, String eta100) {
            this.kwNow = kwNow;
            this.soc = soc;
            this.kwhIn = kwhIn;
            this.cost = cost;
            this.peak = peak;
            this.eta80 = eta80;
            this.eta100 = eta100;
        }
    }

    static Live live(JSONObject live, ChargingCost cost) {
        Double socEnd = ChargingCost.num(live, "socEnd"), kwNow = ChargingCost.num(live, "kwNow"),
                kwh = ChargingCost.kwhOf(live), eta80 = ChargingCost.num(live, "etaTo80Min"),
                eta100 = ChargingCost.num(live, "etaTo100Min");
        int target = socEnd != null && socEnd < 80 ? 80 : 100;
        Double c = cost.chargeCost(live, kwh);
        return new Live(kwNow == null ? UiFormat.MISSING : fixed(Math.abs(kwNow), 1, " kW"),
                fixed(socEnd, 0, "%") + " → " + target + "%",
                fixed(kwh, 1, " kWh"),
                c == null ? "" : "≈ " + cost.money(c),
                fixed(ChargingCost.num(live, "peakKw"), 1, " kW"),
                eta80 == null ? UiFormat.MISSING : UiFormat.duration(eta80),
                eta100 == null ? UiFormat.MISSING : UiFormat.duration(eta100));
    }

    /** One recent charge: kind tag, start, duration (or the wake time for a parked one), cost, SOC track, facts. */
    static final class Row {
        final String tag, when, sub, cost;
        final boolean dc, ac;
        final double trackFrom, trackTo;
        final List<String> meta;
        Row(String tag, boolean ac, boolean dc, String when, String sub, String cost, double trackFrom, double trackTo,
            List<String> meta) {
            this.tag = tag;
            this.ac = ac;
            this.dc = dc;
            this.when = when;
            this.sub = sub;
            this.cost = cost;
            this.trackFrom = trackFrom;
            this.trackTo = trackTo;
            this.meta = meta;
        }
    }

    /** Newest first: the last three charges, or only the last one while a charge is going on. */
    static List<Row> recent(JSONArray sessions, boolean live, ChargingCost cost, TimeZone tz) {
        List<Row> rows = new ArrayList<>();
        if (sessions == null) return rows;
        int n = sessions.length(), from = Math.max(0, n - (live ? 1 : 3));
        for (int i = from; i < n; i++) {
            JSONObject s = sessions.optJSONObject(i);
            if (s != null) rows.add(row(s, cost, tz));
        }
        Collections.reverse(rows);
        return rows;
    }

    static Row row(JSONObject s, ChargingCost cost, TimeZone tz) {
        String type = ChargingCost.str(s, "type");
        Double a0 = ChargingCost.num(s, "socStart"), z0 = ChargingCost.num(s, "socEnd"), kwh = ChargingCost.kwhOf(s),
                peak = ChargingCost.num(s, "peakKw"), c = cost.chargeCost(s, kwh);
        double a = a0 == null ? 0 : a0, z = z0 == null ? a : z0;
        long start = s.optLong("start"), end = s.optLong("end");
        String sub = s.optBoolean("parked") ? "parked · until " + hm(cal(end, tz)) : UiFormat.duration((end - start) / 60000d);
        List<String> meta = new ArrayList<>();
        meta.add(fixed(a0, 0, "") + " → " + fixed(z0, 0, " %"));
        meta.add("+" + fixed(kwh, 1, " kWh"));
        if (peak != null) meta.add(fixed(peak, 0, " kW peak"));
        return new Row(type == null || type.isEmpty() ? "–" : type.toUpperCase(Locale.ROOT), "ac".equals(type),
                "dc".equals(type), when(start, false, tz), sub, c == null ? UiFormat.MISSING : cost.money(c),
                Math.min(a, z), Math.max(a, z), meta);
    }

    static String kwh(Double v) { return fixed(v, 1, " kWh"); }

    static String fixed(Double v, int decimals, String suffix) {
        return v == null ? UiFormat.MISSING : UiFormat.fixed(v, decimals, suffix);
    }
}

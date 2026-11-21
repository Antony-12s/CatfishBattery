package com.catfish.battery;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Text of the Charge history page, ported from the WebView chargeHistoryScript: this month against the budget, the
 * average price and all-time totals, then every charge newest first in month blocks, and the charger picker.
 * Built from BatteryDataHub.sessionsJson(); pure Java so it is tested on the JVM.
 */
final class HistoryModel {
    private final List<JSONObject> all = new ArrayList<>();
    private final JSONObject live;
    private final ChargingCost cost;
    private final long now;
    private final TimeZone tz;

    HistoryModel(JSONObject data, ChargingCost cost, long now, TimeZone tz) {
        this.cost = cost;
        this.now = now;
        this.tz = tz;
        JSONArray list = data == null ? null : data.optJSONArray("sessions");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject s = list.optJSONObject(i);
            if (s != null && ChargingCost.num(s, "start") != null && ChargingCost.num(s, "end") != null) all.add(s);
        }
        JSONObject l = data == null ? null : data.optJSONObject("live");
        live = l != null && ChargingCost.num(l, "start") != null ? l : null;
    }

    boolean charging() { return live != null; }

    /** No charge recorded and none going on. */
    boolean isEmpty() { return all.isEmpty() && live == null; }

    /** A KPI tile: label, value, a smaller unit after it, the budget bar (-1 = none) and the line under it. */
    static final class Kpi {
        final String label, value, unit, sub, barTone;
        final int barPercent;
        Kpi(String label, String value, String unit, int barPercent, String barTone, String sub) {
            this.label = label;
            this.value = value;
            this.unit = unit;
            this.barPercent = barPercent;
            this.barTone = barTone;
            this.sub = sub;
        }
    }

    List<Kpi> kpis() {
        Calendar today = ChargingModel.cal(now, tz);
        List<JSONObject> month = new ArrayList<>();
        for (JSONObject s : all) if (monthKey(s.optLong("start")) == monthKey(now)) month.add(s);
        double total = sum(all), mk = sum(month);
        Double allCost = sumCost(all), mc = sumCost(month);
        double budget = cost.hasSettings() ? budgetOf() : 0;
        Double share = budget > 0 && mc != null ? mc / budget : null;
        Double avg = allCost != null && total > 0 ? allCost / total : null;
        List<Kpi> k = new ArrayList<>();
        k.add(new Kpi("This month · " + ChargingModel.MONTHS[today.get(Calendar.MONTH)], cost.money(mc),
                budget > 0 ? "of " + cost.money(budget) : "",
                budget > 0 ? (share == null ? 0 : (int) Math.min(100, Math.round(share * 100))) : -1,
                share == null ? "" : budgetTone(share),
                plural(month.size()) + " · " + kwhText(month.isEmpty() ? null : mk)
                        + (share != null ? " · " + Math.round(share * 100) + " % of budget" : "")));
        k.add(new Kpi("Average price", avg != null ? cost.avgPriceValue(avg) : UiFormat.MISSING, avg != null ? "/kWh" : "", -1, "",
                avg != null ? "with charging losses, all charges" : all.isEmpty() ? "" : "set prices in Settings"));
        JSONObject first = all.isEmpty() ? null : all.get(0);
        k.add(new Kpi("All time", kwhText(all.isEmpty() ? null : total), plural(all.size()), -1, "",
                (allCost != null ? cost.money(allCost) + " · " : "")
                        + (first != null ? "since " + ChargingModel.day(ChargingModel.cal(first.optLong("start"), tz)) : "none yet")));
        return k;
    }

    /** The month against the budget: amber from 80 %, red past it. */
    static String budgetTone(double share) { return share > 1 ? "danger" : share >= 0.8 ? "warn" : ""; }

    /** One month block: "November 2026", its totals, and its charges newest first. */
    static final class Month {
        final String label, summary;
        final List<Row> rows = new ArrayList<>();
        Month(String label, String summary) {
            this.label = label;
            this.summary = summary;
        }
    }

    List<Month> months() {
        List<Month> out = new ArrayList<>();
        List<JSONObject> group = new ArrayList<>();
        int key = Integer.MIN_VALUE;
        for (int i = all.size() - 1; i >= 0; i--) {
            JSONObject s = all.get(i);
            int k = monthKey(s.optLong("start"));
            if (k != key && !group.isEmpty()) { out.add(month(group)); group = new ArrayList<>(); }
            key = k;
            group.add(s);
        }
        if (!group.isEmpty()) out.add(month(group));
        return out;
    }

    private Month month(List<JSONObject> rows) {
        Calendar c = ChargingModel.cal(rows.get(0).optLong("start"), tz);
        Double money = sumCost(rows);
        Month m = new Month(ChargingModel.MONTHS[c.get(Calendar.MONTH)] + " " + c.get(Calendar.YEAR),
                plural(rows.size()) + " · " + kwhText(sum(rows)) + (money != null ? " · " + cost.money(money) : ""));
        for (JSONObject s : rows) m.rows.add(row(s, false));
        return m;
    }

    /** One charge row; tapping it opens the charger picker for {@link #start}. */
    static final class Row {
        final long start;
        final boolean live, ac, dc, chargerDefault;
        final String tag, when, sub, soc, kwh, peak, charger, cost;
        final double trackFrom, trackTo;
        Row(long start, boolean live, String type, String when, String sub, String soc, double trackFrom, double trackTo,
            String kwh, String peak, String charger, boolean chargerDefault, String cost) {
            this.start = start;
            this.live = live;
            this.ac = "ac".equals(type);
            this.dc = "dc".equals(type);
            this.tag = type == null || type.isEmpty() ? "–" : type.toUpperCase(Locale.ROOT);
            this.when = when;
            this.sub = sub;
            this.soc = soc;
            this.trackFrom = trackFrom;
            this.trackTo = trackTo;
            this.kwh = kwh;
            this.peak = peak;
            this.charger = charger;
            this.chargerDefault = chargerDefault;
            this.cost = cost;
        }
    }

    Row liveRow() { return live == null ? null : row(live, true); }

    private Row row(JSONObject s, boolean isLive) {
        Double a0 = ChargingCost.num(s, "socStart"), z0 = ChargingCost.num(s, "socEnd"), peak = ChargingCost.num(s, "peakKw");
        double a = a0 == null ? 0 : a0, z = z0 == null ? a : z0;
        JSONObject ch = cost.chargerOf(s);
        String pick = ChargingCost.str(s, "charger");
        return new Row(s.optLong("start"), isLive, ChargingCost.str(s, "type"), when(s), sub(s, isLive),
                ChargingModel.fixed(a0, 0, "") + " → " + ChargingModel.fixed(z0, 0, " %"), Math.min(a, z), Math.max(a, z),
                "+" + kwhText(ChargingCost.kwhOf(s)), peak == null ? "" : UiFormat.fixed(peak, 0, " kW peak"),
                ch == null ? null : ch.optString("name"), pick == null || pick.isEmpty(),
                cost.money(cost.chargeCost(s, ChargingCost.kwhOf(s))));
    }

    private String when(JSONObject s) { return ChargingModel.when(s.optLong("start"), false, tz); }

    /** A parked charge happened between the head unit sleeping and waking: say until when, not a duration. */
    private String sub(JSONObject s, boolean isLive) {
        if (isLive) return "charging now";
        long a = s.optLong("start"), z = s.optLong("end");
        if (!s.optBoolean("parked")) return UiFormat.duration((z - a) / 60000d);
        Calendar ca = ChargingModel.cal(a, tz), cz = ChargingModel.cal(z, tz);
        boolean sameDay = ca.get(Calendar.YEAR) == cz.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cz.get(Calendar.DAY_OF_YEAR);
        return "parked · until " + (sameDay ? "" : ChargingModel.day(cz) + " ") + ChargingModel.hm(cz);
    }

    /** The charger picker: Default (the AC or DC default from Settings), then every charger with its price. */
    static final class Picker {
        final long start;
        final String title;
        final List<Option> options = new ArrayList<>();
        Picker(long start, String title) {
            this.start = start;
            this.title = title;
        }
    }

    static final class Option {
        final String id, name, detail;
        final boolean on;
        Option(String id, String name, String detail, boolean on) {
            this.id = id;
            this.name = name;
            this.detail = detail;
            this.on = on;
        }
    }

    /** Null when the charge is gone or the settings are not loaded. */
    Picker picker(long start) {
        JSONObject s = null;
        for (JSONObject x : all) if (x.optLong("start") == start) s = x;
        if (s == null && live != null && live.optLong("start") == start) s = live;
        if (s == null || !cost.hasSettings()) return null;
        boolean dc = "dc".equals(ChargingCost.str(s, "type"));
        String pick = ChargingCost.str(s, "charger");
        if (pick == null) pick = "";
        JSONObject def = cost.defaultCharger(dc);
        Picker p = new Picker(start, "Charger for " + when(s));
        p.options.add(new Option("", "Default" + (def != null ? " · " + def.optString("name") : ""), dc ? "DC" : "AC",
                pick.isEmpty()));
        JSONArray list = settingsChargers();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i);
            if (c == null) continue;
            String rate = c.optBoolean("tou")
                    ? cost.rateText(c.optDouble("offPeak", 0)) + " – " + cost.rateText(c.optDouble("peak", 0))
                    : cost.rateText(c.optDouble("price", 0));
            p.options.add(new Option(c.optString("id"), c.optString("name"), rate, pick.equals(c.optString("id"))));
        }
        return p;
    }

    /** kWh with one decimal under 100, whole from 100. */
    static String kwhText(Double k) {
        return k == null ? UiFormat.MISSING : UiFormat.fixed(k, k < 100 ? 1 : 0, " kWh");
    }

    static String plural(int n) { return n + " charge" + (n == 1 ? "" : "s"); }

    private double budgetOf() { return Math.max(0, cost.settingsNumber("budget")); }
    private JSONArray settingsChargers() { return cost.settingsChargers(); }

    private int monthKey(long ts) {
        Calendar c = ChargingModel.cal(ts, tz);
        return c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH);
    }

    private static double sum(List<JSONObject> list) {
        double t = 0;
        for (JSONObject s : list) { Double k = ChargingCost.kwhOf(s); if (k != null) t += k; }
        return t;
    }

    /** A total cost only when at least one charge had a price. */
    private Double sumCost(List<JSONObject> list) {
        Double t = null;
        for (JSONObject s : list) {
            Double c = cost.chargeCost(s, ChargingCost.kwhOf(s));
            if (c != null) t = (t == null ? 0 : t) + c;
        }
        return t;
    }
}

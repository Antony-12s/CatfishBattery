package com.catfish.battery;

import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Status page facts rows and Pack header counters for one poll, from BatteryDataHub.dataJson() (and settingsJson() for
 * the cost), with the WebView drawCells/drawTemps/drawTotals rules. Missing values are "--".
 */
final class StatusFacts {
    private static final Map<String, String> CURRENCY = new HashMap<>();

    static {
        String[] c = { "THB", "\u0e3f", "USD", "$", "EUR", "\u20ac", "GBP", "\u00a3", "JPY", "\u00a5", "CNY", "\u00a5",
                "MYR", "RM", "SGD", "S$", "LAK", "\u20ad", "VND", "\u20ab", "IDR", "Rp", "PHP", "\u20b1", "INR", "\u20b9",
                "AUD", "A$", "KRW", "\u20a9" };
        for (int i = 0; i < c.length; i += 2) CURRENCY.put(c[i], c[i + 1]);
    }

    final String cellMax, cellMin, cellDelta;
    final int deltaLevel, tempMaxLevel, tempMinLevel;
    final String tempMax, tempMin, tempSpread;
    final String used, charged, regen, cost, costNote, since;
    final String cellCount, sensorCount;
    final String legendVmin, legendVmax, legendTmin, legendTmax;

    private StatusFacts(Integer vMax, Integer vMin, Integer tMax, Integer tMin, int cells, int sensors,
                        JSONObject totals, JSONObject settings, TimeZone zone) {
        Integer delta = vMax != null && vMin != null && vMax >= vMin ? vMax - vMin : null;
        cellMax = StatusFormat.whole(vMax, " mV");
        cellMin = StatusFormat.whole(vMin, " mV");
        cellDelta = StatusFormat.whole(delta, " mV");
        deltaLevel = StatusFormat.deltaLevel(delta);
        Integer spread = tMax == null ? null : tMax - tMin;
        tempMax = StatusFormat.whole(tMax, " \u00b0C");
        tempMaxLevel = StatusFormat.tempLevel(tMax);
        tempMinLevel = StatusFormat.tempLevel(tMin);
        tempMin = StatusFormat.whole(tMin, " \u00b0C");
        tempSpread = StatusFormat.whole(spread, " \u00b0C");
        legendVmin = cellMin;
        legendVmax = cellMax;
        legendTmin = tempMin;
        legendTmax = tempMax;
        cellCount = cells + " / " + CellsModel.CELLS;
        sensorCount = sensors + " / " + CellsModel.SENSORS;

        Double usedWh = num(totals, "usedWh"), chargedWh = num(totals, "chargedWh"), regenWh = num(totals, "regenWh");
        used = StatusFormat.kwh(usedWh);
        charged = StatusFormat.kwh(chargedWh);
        regen = StatusFormat.kwh(regenWh);
        // cost is what the driving took: energy out of the pack less what regen put back, at the home (AC) rate
        String symbol = settings == null ? "\u0e3f" : CURRENCY.getOrDefault(settings.optString("currency"), "\u0e3f");
        Double rate = homeRate(settings);
        Double driveCost = null;
        if (usedWh != null && regenWh != null && rate != null && rate > 0) {
            double loss = settings.optDouble("lossAc", 0);
            driveCost = (usedWh - regenWh) / 1000 / (1 - (Double.isNaN(loss) ? 0 : loss) / 100) * rate;
        }
        cost = StatusFormat.money(driveCost, symbol);
        costNote = rate != null && rate > 0 ? StatusFormat.rate(rate, symbol) : "no price set";
        Double s = num(totals, "since");
        since = StatusFormat.since(s == null ? 0 : s.longValue(), zone);
    }

    /** {@code settingsData} is BatteryDataHub.settingsJson() ({saved, settings}) or null while not loaded. */
    static StatusFacts from(JSONObject data, JSONObject settingsData, TimeZone zone) {
        boolean live = data.optBoolean("streamConnected", false);
        Map<Integer, Integer> cells = CellsModel.cellSamples(data);
        Integer max = null, min = null;
        for (int v : cells.values()) {
            if (max == null || v > max) max = v;
            if (min == null || v < min) min = v;
        }
        if (max == null) {
            Double bmsMax = live ? range(data, "cellMaxV", 2, 5) : null, bmsMin = live ? range(data, "cellMinV", 2, 5) : null;
            max = bmsMax == null ? null : (int) Math.round(bmsMax * 1000);
            min = bmsMin == null ? null : (int) Math.round(bmsMin * 1000);
        }
        Map<Integer, Integer> temps = CellsModel.sensorTemps(data);
        Integer tMax = null, tMin = null;
        for (int v : temps.values()) {
            if (tMax == null || v > tMax) tMax = v;
            if (tMin == null || v < tMin) tMin = v;
        }
        JSONObject settings = settingsData == null ? null : settingsData.optJSONObject("settings");
        return new StatusFacts(max, min, tMax, tMin, cells.size(), temps.size(), data.optJSONObject("totals"), settings, zone);
    }

    /** The AC (home) charger's rate: its off-peak price when it bills by time of use. */
    private static Double homeRate(JSONObject settings) {
        if (settings == null) return null;
        JSONArray list = settings.optJSONArray("chargers");
        if (list == null || list.length() == 0) return null;
        String ac = settings.optString("ac");
        JSONObject charger = null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i);
            if (c != null && ac.equals(c.optString("id"))) { charger = c; break; }
        }
        if (charger == null) charger = list.optJSONObject(0);
        if (charger == null) return null;
        double r = charger.optBoolean("tou", false) ? charger.optDouble("offPeak", 0) : charger.optDouble("price", 0);
        return Double.isNaN(r) ? null : r;
    }

    private static Double num(JSONObject o, String key) {
        Object v = o == null ? null : o.opt(key);
        if (!(v instanceof Number)) return null;
        double d = ((Number) v).doubleValue();
        return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
    }

    private static Double range(JSONObject o, String key, double lo, double hi) {
        Double d = num(o, key);
        return d == null || d < lo || d > hi ? null : d;
    }
}

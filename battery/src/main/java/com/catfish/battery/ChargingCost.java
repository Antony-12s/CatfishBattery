package com.catfish.battery;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Prices and costs for the Charging and History pages, ported from the WebView commonScript (money, rateText,
 * chargerOf, rateOf, chargeCost, homeRate, driveCost). Reads the "settings" object of BatteryDataHub.settingsJson();
 * without it nothing is priced.
 */
final class ChargingCost {
    static final Map<String, String> CURRENCY = new LinkedHashMap<>();
    /** Minor currency unit (1/100th of the main unit) for currencies commonly priced in cents/pence/sen per kWh. */
    static final Map<String, String> SUBUNIT = new LinkedHashMap<>();
    static {
        String[] pairs = { "THB", "฿", "USD", "$", "EUR", "€", "GBP", "£", "JPY", "¥", "CNY", "¥", "MYR", "RM",
                "SGD", "S$", "LAK", "₭", "VND", "₫", "IDR", "Rp", "PHP", "₱", "INR", "₹", "AUD", "A$", "KRW", "₩" };
        for (int i = 0; i < pairs.length; i += 2) CURRENCY.put(pairs[i], pairs[i + 1]);
        String[] subs = { "USD", "¢", "EUR", "c", "GBP", "p", "MYR", "sen", "SGD", "¢", "AUD", "c" };
        for (int i = 0; i < subs.length; i += 2) SUBUNIT.put(subs[i], subs[i + 1]);
    }

    /** Subunit symbol (e.g. "¢" for USD, "c" for AUD, "p" for GBP) or null when the currency has none. */
    static String subunit(String currency) { return currency == null ? null : SUBUNIT.get(currency); }

    /** Whether rates are entered and shown in subunits (cents/pence/sen per kWh) instead of the main unit. */
    static boolean priceInSubunit(JSONObject settings) {
        return settings != null && "sub".equals(settings.optString("priceUnit"))
                && subunit(settings.optString("currency")) != null;
    }

    /** Formats a rate (stored in major units) in subunits: "5 c/kWh", "5.5 ¢/kWh", "29.11 ¢/kWh". */
    static String formatSubunitRate(double rate, String subSymbol) {
        double sub = rate * 100;
        int decimals = Math.abs(sub - Math.round(sub)) < 1e-6 ? 0
                : Math.abs(sub * 10 - Math.round(sub * 10)) < 1e-6 ? 1 : 2;
        return UiFormat.fixed(sub, decimals, " " + subSymbol + "/kWh");
    }

    /** Formats a rate (stored in major units) in either main units or subunits. */
    static String formatRate(double rate, String symbol, String subSymbol) {
        if (subSymbol != null) return formatSubunitRate(rate, subSymbol);
        boolean two = Math.abs(rate * 100 - Math.round(rate * 100)) < 1e-6;
        return UiFormat.fixed(rate, two ? 2 : 3, " " + symbol + "/kWh");
    }

    private final JSONObject settings;
    private final TimeZone tz;

    ChargingCost(JSONObject settings, TimeZone tz) {
        this.settings = settings;
        this.tz = tz;
    }

    boolean hasSettings() { return settings != null; }

    /** A number from the settings, 0 when missing. */
    double settingsNumber(String key) { return settings == null ? 0 : settings.optDouble(key, 0); }

    JSONArray settingsChargers() { return settings == null ? null : settings.optJSONArray("chargers"); }

    /** The AC or DC default charger from the settings. */
    JSONObject defaultCharger(boolean dc) {
        JSONArray list = settingsChargers();
        if (list == null || list.length() == 0) return null;
        JSONObject c = find(list, settings.optString(dc ? "dc" : "ac"));
        if (c == null) c = find(list, settings.optString("ac"));
        return c != null ? c : list.optJSONObject(0);
    }

    String symbol() {
        String s = settings == null ? null : CURRENCY.get(settings.optString("currency"));
        return s == null ? "฿" : s;
    }

    boolean inSubunit() { return priceInSubunit(settings); }

    String subunitSymbol() { return settings == null ? null : subunit(settings.optString("currency")); }

    /** Two decimals for non-zero values under 1, one decimal under 100, then grouped whole numbers; "--" when unknown. */
    String money(Double v) {
        if (!finite(v)) return UiFormat.MISSING;
        double abs = Math.abs(v);
        if (abs > 0 && abs < 1) return symbol() + UiFormat.fixed(v, 2, "");
        return symbol() + (abs < 100 ? UiFormat.fixed(v, 1, "")
                : String.format(Locale.US, "%,d", Math.round(v)).replace('-', '\u2212'));
    }

    String rateText(double rate) {
        return formatRate(rate, symbol(), inSubunit() ? subunitSymbol() : null);
    }

    /** Average price KPI value (paired with "/kWh" unit): "5.6 c" in subunit mode, "$0.05" under 1, else money(). */
    String avgPriceValue(double avg) {
        if (inSubunit()) {
            double sub = avg * 100;
            return UiFormat.fixed(sub, Math.abs(sub) < 100 ? 1 : 0, " " + subunitSymbol());
        }
        return money(avg);
    }

    /** The charge's charger: the driver's pick, else the default for its kind (a parked charge goes with AC). */
    JSONObject chargerOf(JSONObject session) {
        if (settings == null) return null;
        String id = str(session, "charger");
        if (id == null || id.isEmpty()) return defaultCharger("dc".equals(str(session, "type")));
        JSONArray list = settingsChargers();
        if (list == null || list.length() == 0) return null;
        JSONObject c = find(list, id);
        return c != null ? c : defaultCharger(false);
    }

    /** TOU the way Thai utilities bill it: weekdays 09:00-22:00 on-peak; nights and weekends off-peak. */
    boolean offPeakAt(long ts) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ts);
        int h = c.get(Calendar.HOUR_OF_DAY), w = c.get(Calendar.DAY_OF_WEEK);
        return w == Calendar.SUNDAY || w == Calendar.SATURDAY || h < 9 || h >= 22;
    }

    /** A parked charge has no known time and counts as off-peak; public holidays are not known. */
    Double rateOf(JSONObject charger, JSONObject session) {
        if (charger == null) return null;
        if (!charger.optBoolean("tou")) return charger.optDouble("price", 0);
        boolean off = session == null || session.optBoolean("parked")
                || offPeakAt((session.optLong("start") + session.optLong("end")) / 2);
        return charger.optDouble(off ? "offPeak" : "peak", 0);
    }

    /** What a charge cost: the kWh the pack took, grossed up by the charging loss, at its charger's rate. */
    Double chargeCost(JSONObject session, Double kwh) {
        Double r = rateOf(chargerOf(session), session);
        if (!finite(kwh) || r == null || !(r > 0)) return null;
        double loss = settings.optDouble("dc".equals(str(session, "type")) ? "lossDc" : "lossAc", 0);
        return kwh / (1 - loss / 100) * r;
    }

    /** Driving is priced as the energy to put back through the AC charger, off-peak when it bills by TOU. */
    Double homeRate() {
        JSONObject c = chargerOf(new JSONObject());
        if (c == null) return null;
        return c.optBoolean("tou") ? c.optDouble("offPeak", 0) : c.optDouble("price", 0);
    }

    Double driveCost(Double kwh) {
        Double r = homeRate();
        if (!finite(kwh) || r == null || !(r > 0)) return null;
        return kwh / (1 - settings.optDouble("lossAc", 0) / 100) * r;
    }

    /** What a charge put in: the car's own pack energy figure, else the pack power the app added up. */
    static Double kwhOf(JSONObject s) {
        Double added = num(s, "kwhAdded");
        return added != null ? added : num(s, "kwhIn");
    }

    /** A finite number, or null for missing / JSON null / NaN. */
    static Double num(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        double v = o.optDouble(key);
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    /** A string, or null for missing / JSON null (optString would give "null"). */
    static String str(JSONObject o, String key) {
        return o == null || o.isNull(key) ? null : o.optString(key);
    }

    static boolean finite(Double v) { return v != null && !v.isNaN() && !v.isInfinite(); }

    private static JSONObject find(JSONArray list, String id) {
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i);
            if (c != null && c.optString("id").equals(id)) return c;
        }
        return null;
    }
}

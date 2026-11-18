package com.catfish.battery;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Settings page's form state, ported from the WebView settingsScript (render / collect): number fields keep
 * the text as typed, so edits survive focus changes and page switches until Save. {@link #toJson()} is what the
 * page saves; BatterySettings.clean stays the only validation, and {@link #issues()} reports what it would change.
 */
final class SettingsForm {
    /** One charger row as typed. */
    static final class Charger {
        final String id;
        String name, price, offPeak, peak;
        boolean tou;
        Charger(String id, String name, String price, boolean tou, String offPeak, String peak) {
            this.id = id;
            this.name = name;
            this.price = price;
            this.tou = tou;
            this.offPeak = offPeak;
            this.peak = peak;
        }
    }

    static final int[] ALERT_STEPS = { 0, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100 };

    String currency = "THB";
    final List<Charger> chargers = new ArrayList<>();
    String ac = "", dc = "";
    String lossAc = "", lossDc = "", budget = "";
    int alertSoc;
    String resetMode = "off";
    int resetDay = 1, resetMonths = 2;

    /** The form for a settings object (the "settings" of BatteryDataHub.settingsJson(), already clean). */
    static SettingsForm from(JSONObject s) {
        SettingsForm f = new SettingsForm();
        if (s == null) s = new JSONObject();
        f.currency = s.optString("currency", "THB");
        JSONArray list = s.optJSONArray("chargers");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i);
            if (c == null) continue;
            f.chargers.add(new Charger(c.optString("id"), c.optString("name"), shown(c.optDouble("price", 0)),
                    c.optBoolean("tou"), shown(c.optDouble("offPeak", 0)), shown(c.optDouble("peak", 0))));
        }
        f.ac = s.optString("ac");
        f.dc = s.optString("dc");
        f.lossAc = shown(s.optDouble("lossAc", 0));
        f.lossDc = shown(s.optDouble("lossDc", 0));
        f.budget = shown(s.optDouble("budget", 0));
        int alert = s.optInt("alertSoc", 0);
        f.alertSoc = alert >= 50 && alert <= 100 && alert % 5 == 0 ? alert : 0;
        f.resetMode = s.optString("resetMode", "off");
        f.resetDay = Math.max(1, Math.min(31, s.optInt("resetDay", 1)));
        f.resetMonths = Math.max(2, Math.min(12, s.optInt("resetMonths", 2)));
        return f;
    }

    /** What Save sends; the months count only in the every-N-months mode. */
    JSONObject toJson() {
        try {
            JSONArray list = new JSONArray();
            for (Charger c : chargers) {
                list.put(new JSONObject().put("id", c.id).put("name", c.name.trim()).put("price", priceOf(c.price))
                        .put("tou", c.tou).put("offPeak", priceOf(c.offPeak)).put("peak", priceOf(c.peak)));
            }
            return new JSONObject().put("currency", currency).put("chargers", list).put("ac", ac).put("dc", dc)
                    .put("lossAc", priceOf(lossAc)).put("lossDc", priceOf(lossDc)).put("budget", priceOf(budget))
                    .put("alertSoc", alertSoc).put("resetMode", resetMode).put("resetDay", resetDay)
                    .put("resetMonths", "months".equals(resetMode) ? resetMonths : 1);
        } catch (JSONException e) {
            throw new IllegalStateException(e); // priceOf never returns NaN or infinity
        }
    }

    /** A new, empty charger with an id BatterySettings accepts ([a-z0-9], unique). */
    Charger addCharger(long now) {
        String base = "c" + Long.toString(Math.abs(now), 36), id = base;
        for (int n = 2; byId(id) != null; n++) id = base + n;
        if (id.length() > 24) id = id.substring(id.length() - 24);
        Charger c = new Charger(id, "", "", false, "", "");
        chargers.add(c);
        return c;
    }

    /** Removes a charger unless it is the last one; a default that pointed at it moves to the first charger. */
    boolean remove(String id) {
        Charger c = byId(id);
        if (c == null || chargers.size() < 2) return false;
        chargers.remove(c);
        String first = chargers.get(0).id;
        if (id.equals(ac)) ac = first;
        if (id.equals(dc)) dc = first;
        return true;
    }

    Charger byId(String id) {
        for (Charger c : chargers) if (c.id.equals(id)) return c;
        return null;
    }

    /**
     * Fields BatterySettings.clean would save differently from what is typed, keyed "lossAc", "lossDc", "budget" or
     * "charger.&lt;id&gt;.&lt;name|price|offPeak|peak&gt;", with the message to show beside the field.
     */
    Map<String, String> issues() {
        Map<String, String> out = new LinkedHashMap<>();
        JSONObject sent = toJson();
        JSONObject clean;
        try { clean = new JSONObject(BatterySettings.clean(sent.toString())); }
        catch (JSONException e) { return out; }
        for (String k : new String[] { "lossAc", "lossDc", "budget" }) number(out, k, sent, clean, k);
        JSONArray a = sent.optJSONArray("chargers"), b = clean.optJSONArray("chargers");
        for (int i = 0; a != null && b != null && i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i), y = null;
            for (int j = 0; j < b.length(); j++) if (b.optJSONObject(j).optString("id").equals(x.optString("id"))) y = b.optJSONObject(j);
            if (y == null) continue;
            String key = "charger." + x.optString("id") + ".";
            if (!x.optString("name").equals(y.optString("name"))) out.put(key + "name", "Saved as " + y.optString("name"));
            for (String k : new String[] { "price", "offPeak", "peak" }) number(out, key + k, x, y, k);
        }
        return out;
    }

    private static void number(Map<String, String> out, String key, JSONObject sent, JSONObject clean, String k) {
        double a = sent.optDouble(k, 0), b = clean.optDouble(k, 0);
        if (Math.abs(a - b) > 1e-9) out.put(key, "Saved as " + (b == 0 ? "0" : shown(b)));
    }

    /** The Auto reset line: the next date comes from the app after a save; before that the change is pending. */
    String nextResetText(JSONObject saved, Long nextReset, TimeZone tz) {
        if ("off".equals(resetMode)) return "Off";
        boolean same = saved != null && resetMode.equals(saved.optString("resetMode"))
                && resetDay == saved.optInt("resetDay") && (!"months".equals(resetMode) || resetMonths == saved.optInt("resetMonths"));
        if (!same) return "Next date shows after Save";
        if (nextReset == null) return "Scheduled";
        Calendar c = ChargingModel.cal(nextReset, tz);
        return "Next: " + c.get(Calendar.DAY_OF_MONTH) + " " + ChargingModel.MONTHS[c.get(Calendar.MONTH)].substring(0, 3)
                + " " + c.get(Calendar.YEAR);
    }

    /** One line for the Settings summary card: how many chargers and which are the AC / DC defaults. */
    String chargersSummary() {
        String count = chargers.size() + (chargers.size() == 1 ? " charger" : " chargers");
        String acName = chargerName(ac), dcName = chargerName(dc);
        return ac.equals(dc) ? count + " · " + acName + " for AC and DC" : count + " · AC " + acName + " · DC " + dcName;
    }

    String prefsSummary() {
        return currency + " · loss " + orZero(lossAc) + " / " + orZero(lossDc) + " % · alert "
                + (alertSoc == 0 ? "off" : alertSoc + " %");
    }

    String totalsSummary() {
        if ("off".equals(resetMode)) return "Auto reset off";
        String day = resetDay >= 31 ? "last day" : "day " + resetDay;
        return "Auto reset " + ("months".equals(resetMode) ? "every " + resetMonths + " months" : "monthly") + ", " + day;
    }

    private String chargerName(String id) {
        for (Charger c : chargers) {
            if (c.id.equals(id)) return c.name == null || c.name.isBlank() ? "Unnamed" : c.name.trim();
        }
        return "none";
    }

    private static String orZero(String v) { return v == null || v.isEmpty() ? "0" : v; }

    /** A setting as the field shows it: empty for 0, no trailing zeros. */
    static String shown(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v == 0) return "";
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** What a price box shows while typing: the car's number pad has no point, so any run of non-digits is one. */
    static String priceText(String v) {
        String s = (v == null ? "" : v).replaceAll("[^0-9]+", ".");
        int i = s.indexOf('.');
        return i < 0 ? s : s.substring(0, i + 1) + s.substring(i + 1).replace(".", "");
    }

    private static final Pattern NUMBER = Pattern.compile("^[0-9]*\\.?[0-9]*");

    /** A typed price as a number; 0 when nothing parses (the WebView's numOf). */
    static double priceOf(String v) {
        String s = (v == null ? "" : v.trim()).replaceFirst("[^0-9]+", ".");
        Matcher m = NUMBER.matcher(s);
        String n = m.find() ? m.group() : "";
        if (n.isEmpty() || ".".equals(n)) return 0;
        double d = Double.parseDouble(n.endsWith(".") ? n + "0" : n);
        return Double.isInfinite(d) ? 0 : d;
    }
}

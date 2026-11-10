package com.catfish.battery;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Battery tab's settings: the currency, the chargers with their prices, which one AC and DC charges
 * default to, charging losses, a monthly budget and the charge alert. Whatever a page sends is rebuilt
 * from these keys only, with every number clamped, so the saved file never holds anything else.
 */
final class BatterySettings {
    static final List<String> CURRENCIES = Arrays.asList(
            "THB", "USD", "EUR", "GBP", "JPY", "CNY", "MYR", "SGD", "LAK", "VND", "IDR", "PHP", "INR", "AUD", "KRW");
    private static final int NAME_MAX = 40;
    private static final double PRICE_MAX = 1e6;

    private BatterySettings() { }

    /** A clean copy of {@code raw}: defaults for whatever is missing, broken or out of range. */
    static String clean(String raw) {
        JSONObject in;
        try { in = new JSONObject(raw == null || raw.isEmpty() ? "{}" : raw); }
        catch (JSONException e) { in = new JSONObject(); }
        try {
            JSONObject out = new JSONObject();
            String currency = in.optString("currency", "THB");
            out.put("currency", CURRENCIES.contains(currency) ? currency : "THB");
            JSONArray chargers = new JSONArray();
            Set<String> ids = new HashSet<>();
            JSONArray list = in.optJSONArray("chargers");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject c = list.optJSONObject(i);
                String id = c == null ? "" : c.optString("id", "");
                if (!id.matches("[a-z0-9]{1,24}") || !ids.add(id)) continue;
                String name = c.optString("name", "").trim();
                chargers.put(new JSONObject()
                        .put("id", id)
                        .put("name", name.isEmpty() ? "Charger " + (chargers.length() + 1)
                                : name.substring(0, Math.min(NAME_MAX, name.length())))
                        .put("price", clamp(c.optDouble("price", 0), 0, PRICE_MAX))
                        .put("tou", c.optBoolean("tou", false))
                        .put("peak", clamp(c.optDouble("peak", 0), 0, PRICE_MAX))
                        .put("offPeak", clamp(c.optDouble("offPeak", 0), 0, PRICE_MAX)));
            }
            if (chargers.length() == 0) {
                ids.add("home");
                chargers.put(new JSONObject().put("id", "home").put("name", "Home").put("price", 0)
                        .put("tou", false).put("peak", 0).put("offPeak", 0));
            }
            out.put("chargers", chargers);
            String first = chargers.getJSONObject(0).getString("id");
            out.put("ac", ids.contains(in.optString("ac")) ? in.optString("ac") : first);
            out.put("dc", ids.contains(in.optString("dc")) ? in.optString("dc") : first);
            out.put("lossAc", clamp(in.optDouble("lossAc", 0), 0, 50));
            out.put("lossDc", clamp(in.optDouble("lossDc", 0), 0, 50));
            out.put("budget", clamp(in.optDouble("budget", 0), 0, PRICE_MAX));
            out.put("alertSoc", (int) Math.round(clamp(in.optDouble("alertSoc", 0), 0, 100)));
            return out.toString();
        } catch (JSONException e) {
            // put() only throws on NaN or infinity, which clamp() rules out
            throw new IllegalStateException(e);
        }
    }

    /** The SOC to announce while charging, 0 when off; from a clean() result. */
    static int alertSoc(String clean) {
        try { return new JSONObject(clean).optInt("alertSoc", 0); }
        catch (JSONException e) { return 0; }
    }

    /** NaN, infinities and anything outside [min, max] land on the nearest bound (NaN on min). */
    private static double clamp(double v, double min, double max) {
        return Double.isNaN(v) ? min : Math.max(min, Math.min(max, v));
    }
}

package com.catfish.battery;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public charger prices for the Settings page, per currency. The list ships in assets/charger-prices.json; the same
 * file on GitHub main is fetched when online and kept in the app's files, so a price update needs no new APK.
 * Everything from the file is rebuilt through {@link #parse}: unknown keys dropped, strings capped, numbers checked.
 */
final class ChargerPrices {
    private static final String TAG = "CatfishBattery";
    static final String ASSET = "charger-prices.json";
    static final String URL_MAIN =
            "https://raw.githubusercontent.com/Antony-12s/CatfishBattery/main/battery/src/main/assets/charger-prices.json";
    private static final int MAX_BYTES = 256 * 1024;
    private static final int TEXT_MAX = 80;
    private static final double PRICE_MAX = 1e6;

    /** One network's price: a flat {@code price}, or {@code peak} / {@code offPeak} when {@code tou}. */
    static final class Network {
        final String brand, detail, source, url, date;
        final double price, peak, offPeak;
        final boolean tou, from;
        Network(String brand, String detail, double price, double peak, double offPeak, boolean from,
                String source, String url, String date) {
            this.brand = brand;
            this.detail = detail;
            this.price = price;
            this.peak = peak;
            this.offPeak = offPeak;
            this.tou = peak > 0 && offPeak > 0;
            this.from = from;
            this.source = source;
            this.url = url;
            this.date = date;
        }

        /** The name a charger made from this network gets, within the 40 characters Settings keeps. */
        String chargerName() {
            String n = detail.isEmpty() ? brand : brand + " " + detail;
            return n.length() <= 40 ? n : n.substring(0, 40).trim();
        }
    }

    static final class Region {
        final String currency, name, peakHours;
        final List<Network> networks;
        Region(String currency, String name, String peakHours, List<Network> networks) {
            this.currency = currency;
            this.name = name;
            this.peakHours = peakHours;
            this.networks = networks;
        }
    }

    final String updated;
    final Map<String, Region> regions;

    private ChargerPrices(String updated, Map<String, Region> regions) {
        this.updated = updated;
        this.regions = regions;
    }

    Region forCurrency(String currency) { return regions.get(currency); }

    /** Settings > Region choices as {currency, label}: the listed countries first, then every other currency as "Other". */
    static java.util.List<String[]> regionChoices(ChargerPrices p) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        if (p != null) {
            for (Region r : p.regions.values()) {
                if (ChargingCost.CURRENCY.containsKey(r.currency)) out.add(new String[] { r.currency, label(r) });
            }
        }
        for (String code : ChargingCost.CURRENCY.keySet()) {
            if (p == null || p.forCurrency(code) == null) out.add(new String[] { code, "Other · " + code });
        }
        return out;
    }

    /** The Region card's one line: country, currency and how many networks have prices. */
    static String regionSummary(ChargerPrices p, String currency) {
        Region r = p == null ? null : p.forCurrency(currency);
        return r == null ? "Other · " + currency + " · no network list" : label(r) + " · " + r.networks.size() + " public networks";
    }

    private static String label(Region r) { return (r.name.isEmpty() ? r.currency : r.name) + " · " + r.currency; }

    /** The prices in {@code json}, or null when it is not a version 1 file with at least one usable network. */
    static ChargerPrices parse(String json) {
        if (json == null) return null;
        JSONObject in;
        try { in = new JSONObject(json); } catch (JSONException e) { return null; }
        if (in.optInt("version", 0) != 1) return null;
        Map<String, Region> regions = new LinkedHashMap<>();
        JSONArray list = in.optJSONArray("regions");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject r = list.optJSONObject(i);
            if (r == null) continue;
            String currency = r.optString("currency");
            if (!BatterySettings.CURRENCIES.contains(currency) || regions.containsKey(currency)) continue;
            List<Network> networks = new ArrayList<>();
            JSONArray ns = r.optJSONArray("networks");
            for (int j = 0; ns != null && j < ns.length(); j++) {
                Network n = network(ns.optJSONObject(j));
                if (n != null) networks.add(n);
            }
            if (!networks.isEmpty()) regions.put(currency, new Region(currency, text(r, "name"), text(r, "peakHours"), networks));
        }
        return regions.isEmpty() ? null : new ChargerPrices(date(in.optString("updated")), regions);
    }

    private static Network network(JSONObject n) {
        if (n == null) return null;
        String brand = text(n, "brand");
        if (brand.isEmpty()) return null;
        double price = price(n, "price"), peak = price(n, "peak"), offPeak = price(n, "offPeak");
        if (!(peak > 0 && offPeak > 0)) { peak = 0; offPeak = 0; }
        if (peak == 0 && price == 0) return null;
        String url = n.optString("url");
        return new Network(brand, text(n, "detail"), price, peak, offPeak, n.optBoolean("from", false),
                text(n, "source"), url.startsWith("https://") && url.length() <= 300 ? url : "", date(n.optString("date")));
    }

    /** A display string: control characters gone, trimmed, at most TEXT_MAX characters. */
    private static String text(JSONObject o, String key) {
        String s = o.optString(key, "").replaceAll("\\p{Cntrl}", " ").trim();
        return s.length() <= TEXT_MAX ? s : s.substring(0, TEXT_MAX).trim();
    }

    private static double price(JSONObject o, String key) {
        double v = o.optDouble(key, 0);
        return Double.isNaN(v) || v <= 0 || v > PRICE_MAX ? 0 : v;
    }

    private static String date(String s) { return s != null && s.matches("\\d{4}-\\d{2}-\\d{2}") ? s : ""; }

    /**
     * kWh the pack needs to go from the car's SOC to {@code targetSoc}. The pack size comes from the car's own remaining
     * energy over its SOC; null when either is missing or the result is not a plausible pack (10 to 200 kWh).
     */
    static Double kwhTo(Double soc, Double energyWh, double targetSoc) {
        if (!ChargingCost.finite(soc) || !ChargingCost.finite(energyWh) || soc < 5 || soc > 100 || energyWh <= 0) return null;
        double packKwh = energyWh / 1000 / (soc / 100);
        if (packKwh < 10 || packKwh > 200) return null;
        return Math.max(0, (targetSoc - soc) / 100 * packKwh);
    }

    /** {@link #kwhTo(Double, Double, double)} from a dataJson() poll; null unless the car stream is connected. */
    static Double kwhTo(JSONObject data, double targetSoc) {
        if (data == null || !Boolean.TRUE.equals(data.opt("streamConnected"))) return null;
        return kwhTo(ChargingCost.num(data, "soc"), ChargingCost.num(data, "energyWh"), targetSoc);
    }

    /** "2026-09-01" as "1 Sep 2026"; empty when not a date. */
    static String dateText(String iso) {
        if (iso == null || !iso.matches("\\d{4}-\\d{2}-\\d{2}")) return "";
        int month = Integer.parseInt(iso.substring(5, 7)), day = Integer.parseInt(iso.substring(8, 10));
        if (month < 1 || month > 12) return "";
        return day + " " + ChargingModel.MONTHS[month - 1].substring(0, 3) + " " + iso.substring(0, 4);
    }

    /** What {@code kwh} into the pack costs at {@code rate}, grossed up by the charging loss like ChargingCost does. */
    static Double cost(Double kwh, double rate, double lossPercent) {
        if (!ChargingCost.finite(kwh) || !(rate > 0)) return null;
        return kwh / (1 - Math.max(0, Math.min(50, lossPercent)) / 100) * rate;
    }

    // ---- loading ----

    private static File saved(Context c) { return new File(c.getFilesDir(), ASSET); }

    /** The last fetched list, else the one in the APK; null only when both are broken. */
    static ChargerPrices load(Context context) {
        File f = saved(context);
        if (f.isFile()) {
            try {
                ChargerPrices p = parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                if (p != null) return p;
            } catch (IOException e) { Log.w(TAG, "Saved charger prices unreadable", e); }
        }
        try (InputStream in = context.getAssets().open(ASSET)) {
            return parse(read(in));
        } catch (IOException e) {
            Log.e(TAG, "Bundled charger prices unreadable", e);
            return null;
        }
    }

    /** Downloads the list from GitHub main; a valid one is saved and returned, anything else gives null. */
    static ChargerPrices fetch(Context context) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(URL_MAIN).openConnection();
            http.setConnectTimeout(5000);
            http.setReadTimeout(5000);
            if (http.getResponseCode() != 200) return null;
            String json;
            try (InputStream in = http.getInputStream()) { json = read(in); }
            ChargerPrices p = parse(json);
            if (p == null) return null;
            File f = saved(context), tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(f)) Log.w(TAG, "Charger prices not saved");
            return p;
        } catch (IOException | RuntimeException e) {
            Log.i(TAG, "Charger prices not fetched: " + e);
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    /** The stream as UTF-8, refusing anything over MAX_BYTES. */
    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) throw new IOException("charger prices over " + MAX_BYTES + " bytes");
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}

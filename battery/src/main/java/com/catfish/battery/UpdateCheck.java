package com.catfish.battery;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Settings > Update: the latest published releases of this app on GitHub (read-only API call, no download or install),
 * compared with the installed version, with each release's notes as plain changelog lines.
 */
final class UpdateCheck {
    private static final String TAG = "CatfishBattery";
    static final String API = "https://api.github.com/repos/Antony-12s/CatfishBattery/releases?per_page=5";
    static final String RELEASES_PAGE = "https://github.com/Antony-12s/CatfishBattery/releases";
    private static final int MAX_BYTES = 256 * 1024, TEXT_MAX = 400, NOTES_MAX = 60;

    // release assets are installed only from this repository's own release downloads
    static final String DOWNLOADS = "https://github.com/Antony-12s/CatfishBattery/releases/download/";

    static final class Release {
        final String version, title, date, url;
        final List<String> notes;
        final String apkUrl, sha256Url; // null when the release has no installable APK and checksum
        Release(String version, String title, String date, String url, List<String> notes) {
            this(version, title, date, url, notes, null, null);
        }
        Release(String version, String title, String date, String url, List<String> notes, String apkUrl, String sha256Url) {
            this.version = version;
            this.title = title;
            this.date = date;
            this.url = url;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.sha256Url = sha256Url;
        }
    }

    private UpdateCheck() { }

    /** Published, non-prerelease releases with a numeric version and an https link, newest first; empty on bad input. */
    static List<Release> parse(String json) {
        List<Release> out = new ArrayList<>();
        JSONArray list;
        try { list = new JSONArray(json); } catch (JSONException | RuntimeException e) { return out; }
        for (int i = 0; i < list.length(); i++) {
            JSONObject r = list.optJSONObject(i);
            if (r == null || r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
            String version = r.optString("tag_name").replaceFirst("^v", "");
            String url = r.optString("html_url");
            if (!version.matches("\\d+(\\.\\d+){0,3}") || !url.startsWith("https://github.com/")) continue;
            String published = r.optString("published_at");
            String date = published.length() >= 10 ? ChargerPrices.dateText(published.substring(0, 10)) : "";
            String apk = null, sha = null;
            JSONArray assets = r.optJSONArray("assets");
            for (int a = 0; assets != null && a < assets.length(); a++) {
                JSONObject asset = assets.optJSONObject(a);
                String link = asset == null ? "" : asset.optString("browser_download_url");
                if (!link.startsWith(DOWNLOADS + "v" + version + "/")) continue;
                if (link.endsWith("_release.apk")) apk = link;
                else if (link.endsWith("_release.apk.sha256")) sha = link;
            }
            boolean both = apk != null && sha != null && sha.equals(apk + ".sha256");
            out.add(new Release(version, clean(r.optString("name")), date, url, notes(r.optString("body")),
                    both ? apk : null, both ? sha : null));
        }
        return out;
    }

    /** Markdown release notes as short plain lines: headings that repeat the title dropped, bullets kept with their depth. */
    static List<String> notes(String body) {
        List<String> out = new ArrayList<>();
        for (String raw : body.split("\\r?\\n")) {
            if (raw.isBlank() || raw.trim().startsWith("#")) continue;
            int indent = raw.length() - raw.stripLeading().length();
            String line = raw.trim();
            boolean bullet = line.startsWith("- ") || line.startsWith("* ");
            if (bullet) line = line.substring(2);
            line = clean(line.replace("**", "").replace("`", ""));
            if (line.isEmpty()) continue;
            out.add(bullet ? (indent >= 2 ? "   ".repeat(Math.min(indent / 2, 3)) + "– " : "• ") + line : line);
            if (out.size() == NOTES_MAX) break;
        }
        return out;
    }

    /** True when version a is numerically newer than b ("1.10" > "1.9"; "1.5" equals "1.5.0"). */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    static Release latest(List<Release> list) {
        Release best = null;
        for (Release r : list == null ? Collections.<Release>emptyList() : list) {
            if (best == null || newer(r.version, best.version)) best = r;
        }
        return best;
    }

    /** The Update card's one line. */
    static String summary(List<Release> list, String installed) {
        Release l = latest(list);
        if (l == null) return "Not checked yet · you have " + installed;
        return newer(l.version, installed) ? l.version + " available · you have " + installed : "Up to date · " + installed;
    }

    /** Off the UI thread: the release list, or null when offline or GitHub answers anything but 200. */
    static List<Release> fetch() {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(API).openConnection();
            http.setConnectTimeout(5000);
            http.setReadTimeout(5000);
            http.setRequestProperty("Accept", "application/vnd.github+json");
            if (http.getResponseCode() != 200) return null;
            try (InputStream in = http.getInputStream()) { return parse(read(in)); }
        } catch (IOException | RuntimeException e) {
            Log.i(TAG, "Update check failed: " + e);
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) throw new IOException("releases over " + MAX_BYTES + " bytes");
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static int number(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    /** Display text from the network: control characters gone, trimmed, at most TEXT_MAX characters. */
    private static String clean(String s) {
        String t = s == null ? "" : s.replaceAll("\\p{Cntrl}", "").trim();
        return t.length() <= TEXT_MAX ? t : t.substring(0, TEXT_MAX).trim() + "…";
    }
}

package com.catfish.battery;

import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signal name -> id table from the head unit's own APVP config (read-only, world-readable firmware file).
 * APVP ids differ per firmware (build 264133 vs 244520 differ by +24..+165 per group), and some transfer
 * servers match subscriptions by id, so the ids are resolved by name on the car instead of hardcoded.
 */
final class ApvpSignalConfig {
    private static final String TAG = "GCP-BatteryAPVP";
    static final File ROOT = new File("/vendor/etc/transfers/libapvp");
    private static final String FILE = "signal_config.json";
    private static final long MAX_BYTES = 16L << 20; // the 264133 file is 2.4 MB
    // Entry shape: {"id":{"name":"HvBattUDc","value":308282804},"module":...}
    private static final Pattern ENTRY = Pattern.compile(
            "\"id\"\\s*:\\s*\\{\\s*\"name\"\\s*:\\s*\"([^\"\\\\]{1,128})\"\\s*,\\s*\"value\"\\s*:\\s*(\\d{1,10})\\s*\\}");

    private ApvpSignalConfig() { }

    /** Name -> id for every well-formed entry; empty when nothing parses. */
    static Map<String, Integer> parse(CharSequence json) {
        HashMap<String, Integer> out = new HashMap<>();
        if (json == null) return out;
        Matcher m = ENTRY.matcher(json);
        while (m.find()) {
            long id = Long.parseLong(m.group(2));
            if (id > 0 && id <= Integer.MAX_VALUE) out.putIfAbsent(m.group(1).trim(), (int) id);
        }
        return out;
    }

    /**
     * Reads {@code ROOT/signal_config.json}, else the first {@code ROOT/<platform>/signal_config.json}
     * (264133 ships it under p145_j1). Null when no readable, non-empty table exists.
     */
    static Map<String, Integer> load(File root) {
        File direct = new File(root, FILE);
        Map<String, Integer> found = read(direct);
        if (found != null) return found;
        File[] dirs = root.listFiles((java.io.FileFilter) File::isDirectory);
        if (dirs == null) return null;
        Arrays.sort(dirs);
        for (File dir : dirs) {
            found = read(new File(dir, FILE));
            if (found != null) return found;
        }
        return null;
    }

    private static Map<String, Integer> read(File file) {
        try {
            if (!file.isFile() || file.length() > MAX_BYTES) return null;
            Map<String, Integer> parsed = parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (parsed.isEmpty()) return null;
            Log.i(TAG, "APVP signal table " + file + ": " + parsed.size() + " signals");
            return Collections.unmodifiableMap(parsed);
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "APVP signal table unreadable: " + file, e);
            return null;
        }
    }

    /**
     * The car's spelling of {@code name}: exact, else the doubled or halved form (Geely doubles some names,
     * e.g. HvSysRlyStsHvSysRlySts, IDcDcActLoSideIDcDcActLoSide, and not consistently across builds).
     */
    static String carName(Map<String, Integer> table, String name) {
        if (table.containsKey(name)) return name;
        if (table.containsKey(name + name)) return name + name;
        int half = name.length() / 2;
        if (name.length() % 2 == 0 && name.substring(0, half).equals(name.substring(half))
                && table.containsKey(name.substring(0, half))) return name.substring(0, half);
        return null;
    }
}

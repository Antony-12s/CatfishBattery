package com.catfish.battery;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Battery state of health. Two sources, both only while GEAR_SELECTION is Park:
 * - "becm": the BECM's own SOH (DID 496D, 0.01 % steps), read once per day when the app opens (BecmSoh);
 * - "canspi": HvBattEgyCdn (0.5 % steps) from one subscribe-only snapshot of the local nanomq broker, once per park.
 *   It matched the BECM (96.5 % against 96.44 %) on 2026-11-18 and fills in when no BECM read is less than a day old.
 * History is kept in soh.csv (ts,soh_pct,source; older rows without a source are canspi).
 */
final class SohRecorder {
    private static final String TAG = "CatfishSoh";
    static final int GEAR_PARK = 4;
    static final String BECM = "becm", CANSPI = "canspi";
    private static final long DAY_MS = 24L * 3600L * 1000L;
    private static final int BROKER_PORT = 1883;
    // nanomq listens on IPv4 127.0.0.1 only; Android's InetAddress.getLoopbackAddress() is ::1, which is refused.
    static final InetAddress BROKER_HOST = ipv4Loopback();

    // MQTT 3.1.1 CONNECT (clean session, keepalive 60 s, client id "catfish-battery-soh"), SUBSCRIBE canspi QoS 0,
    // DISCONNECT. Never publish: rule, control and cloud-upload topics share this anonymous broker.
    static final byte[] CONNECT = bytes(0x10, 0x1F, 0, 4, 'M', 'Q', 'T', 'T', 4, 2, 0, 60, 0, 19,
            'c', 'a', 't', 'f', 'i', 's', 'h', '-', 'b', 'a', 't', 't', 'e', 'r', 'y', '-', 's', 'o', 'h');
    static final byte[] SUBSCRIBE = bytes(0x82, 0x0B, 0, 1, 0, 6, 'c', 'a', 'n', 's', 'p', 'i', 0);
    static final byte[] DISCONNECT = bytes(0xE0, 0);

    static final class Entry {
        final long ts;
        final double pct;
        final String source;

        Entry(long ts, double pct) { this(ts, pct, CANSPI); }

        Entry(long ts, double pct, String source) {
            this.ts = ts;
            this.pct = pct;
            this.source = source;
        }
    }

    private final File file;
    private final Supplier<Integer> gear;
    private final Callable<Double> fetch;
    private final List<Entry> entries = new ArrayList<>();
    private boolean recordedThisPark;
    private boolean becmRunning;
    private String lastOutcome = "";
    private String state = "starting"; // for the page: why there is no new reading
    private ScheduledExecutorService executor;

    SohRecorder(File dir, Supplier<Integer> gear, Callable<Double> fetch) {
        this.file = new File(dir, "soh.csv");
        this.gear = gear;
        this.fetch = fetch;
        load();
    }

    /** First reading, then a new row when the value changes or a day has passed, so a parked car is not logged every stop. */
    static boolean shouldRecord(Entry last, long ts, double pct) {
        return last == null || pct != last.pct || ts - last.ts >= DAY_MS;
    }

    synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(() -> {
            try {
                tick(System.currentTimeMillis());
            } catch (RuntimeException e) {
                Log.w(TAG, "SOH check failed", e);
            }
        }, 5, 60, TimeUnit.SECONDS);
    }

    /** Once per day when the app opens: the BECM read runs on the recorder's thread, never on the caller's. */
    synchronized void readBecmIfDue(Callable<Double> becm) {
        if (executor == null || becmRunning) return;
        executor.execute(() -> becmTick(System.currentTimeMillis(), becm));
    }

    /** One BECM attempt: only when no BECM row exists for today and the gear is Park; failures are retried next open. */
    void becmTick(long now, Callable<Double> becm) {
        synchronized (this) {
            if (becmRunning || hasBecmToday(now)) return;
            Integer g = gear.get();
            if (g == null || g != GEAR_PARK) {
                note("BECM SOH skipped: " + (g == null ? "gear unknown" : "gear " + g));
                return;
            }
            becmRunning = true;
            state = "reading";
        }
        double pct;
        String failure = null;
        try {
            Double v = becm.call();
            pct = v == null ? Double.NaN : v;
        } catch (Exception e) {
            pct = Double.NaN;
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        synchronized (this) {
            becmRunning = false;
            if (pct > 0 && pct <= 100) {
                append(now, pct, BECM);
                state = "ok";
                note("BECM SOH " + pct + " %");
            } else {
                state = "becm failed";
                Log.w(TAG, "BECM SOH read failed: " + (failure == null ? "no value" : failure));
            }
        }
    }

    private boolean hasBecmToday(long now) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (BECM.equals(e.source) && BecmSoh.sameDay(e.ts, now, java.time.ZoneId.systemDefault())) return true;
        }
        return false;
    }

    private boolean hasBecmWithinDay(long now) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (BECM.equals(e.source)) return now - e.ts < DAY_MS;
        }
        return false;
    }

    synchronized void stop() {
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    synchronized void tick(long now) {
        Integer g = gear.get();
        if (g == null || g != GEAR_PARK) {
            recordedThisPark = false;
            state = g == null ? "gear?" : "not in P";
            note(g == null ? "gear unknown" : "not parked (gear " + g + ")");
            return;
        }
        if (recordedThisPark) return;
        if (hasBecmWithinDay(now)) { // the finer BECM value stays on top; canspi only fills a gap
            recordedThisPark = true;
            return;
        }
        double pct;
        try {
            Double v = fetch.call();
            pct = v == null ? Double.NaN : v;
        } catch (Exception e) {
            pct = Double.NaN;
        }
        if (!(pct > 0 && pct <= 100)) { // unavailable or implausible: retry on the next tick of this park
            state = "no data";
            note("parked, SOH unavailable (" + pct + ")");
            return;
        }
        state = "ok";
        note("parked, SOH " + pct + " %");
        recordedThisPark = true;
        Entry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
        if (!shouldRecord(last, now, pct)) return;
        append(now, pct, CANSPI);
    }

    private void append(long now, double pct, String source) {
        entries.add(new Entry(now, pct, source));
        try {
            if (!file.exists()) {
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                Files.write(file.toPath(), "ts,soh_pct,source\n".getBytes(StandardCharsets.UTF_8));
            }
            Files.write(file.toPath(), String.format(Locale.ROOT, "%d,%.2f,%s%n", now, pct, source).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.w(TAG, "SOH history not saved", e);
        }
    }

    /** Logs only when the outcome changes, so a long park does not flood logcat. */
    private void note(String outcome) {
        if (outcome.equals(lastOutcome)) return;
        lastOutcome = outcome;
        Log.i(TAG, outcome);
    }

    synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Latest and previous recorded SOH for the page; null values when nothing has been recorded yet. */
    synchronized String json() {
        Entry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
        Entry prev = entries.size() < 2 ? null : entries.get(entries.size() - 2);
        return "{\"pct\":" + (last == null ? "null" : last.pct) + ",\"atMs\":" + (last == null ? "null" : last.ts)
                + ",\"prevPct\":" + (prev == null ? "null" : prev.pct) + ",\"prevAtMs\":" + (prev == null ? "null" : prev.ts)
                + ",\"count\":" + entries.size() + ",\"source\":" + (last == null ? "null" : "\"" + last.source + "\"")
                + ",\"state\":\"" + state + "\"}";
    }

    private void load() {
        if (!file.exists()) return;
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String[] parts = line.split(",");
                if (parts.length != 2 && parts.length != 3) continue;
                try {
                    String source = parts.length == 3 && BECM.equals(parts[2].trim()) ? BECM : CANSPI;
                    entries.add(new Entry(Long.parseLong(parts[0].trim()), Double.parseDouble(parts[1].trim()), source));
                } catch (NumberFormatException skip) { } // header or a damaged line
            }
        } catch (IOException e) {
            Log.w(TAG, "SOH history unreadable", e);
        }
    }

    /** HvBattEgyCdn from one canspi snapshot on 127.0.0.1:{port}; NaN when the broker or the frame is unavailable. */
    static double fetchEgyCdn(int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(BROKER_HOST, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(CONNECT);
            out.write(SUBSCRIBE);
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            long deadline = System.currentTimeMillis() + timeoutMs;
            double result = Double.NaN;
            while (System.currentTimeMillis() < deadline && stream.size() < 256 * 1024) {
                int n = in.read(chunk);
                if (n < 0) break;
                stream.write(chunk, 0, n);
                MqttFrames.Publish canspi = null;
                for (MqttFrames.Publish p : MqttFrames.publishes(stream.toByteArray())) {
                    if (p.topic.equals("canspi")) { canspi = p; break; }
                }
                if (canspi == null) continue;
                try {
                    result = CanSpiDecoder.HV_BATT_EGY_CDN.value(CanSpiDecoder.parse(canspi.payload));
                } catch (IllegalArgumentException malformed) {
                    Log.w(TAG, "Malformed canspi snapshot", malformed);
                }
                break;
            }
            out.write(DISCONNECT);
            out.flush();
            return result;
        } catch (IOException e) {
            return Double.NaN;
        }
    }

    /**
     * GEAR_SELECTION through android.car's CarPropertyManager (read only), or null when the car API or the value is
     * unavailable. Reflection keeps android.car out of the build; the gRPC VHAL stream does not carry the gear.
     */
    @android.annotation.SuppressLint("PrivateApi") // android.car is the head unit's system car library (uses-library)
    static Supplier<Integer> carGear(android.content.Context context) {
        return carInt(context, 0x11400400);
    }

    /** Any int VHAL property through CarPropertyManager.getIntProperty (read only), null when unavailable. */
    @android.annotation.SuppressLint("PrivateApi")
    static Supplier<Integer> carInt(android.content.Context context, int propertyId) {
        Object[] manager = {null};
        return () -> {
            try {
                if (manager[0] == null) {
                    Class<?> carClass = Class.forName("android.car.Car");
                    Object car = carClass.getMethod("createCar", android.content.Context.class).invoke(null, context);
                    if (car == null) return null;
                    manager[0] = carClass.getMethod("getCarManager", String.class).invoke(car, "property");
                }
                return (Integer) manager[0].getClass().getMethod("getIntProperty", int.class, int.class)
                        .invoke(manager[0], propertyId, 0);
            } catch (ReflectiveOperationException | RuntimeException e) {
                manager[0] = null; // car service restarted or not ready: rebuild on the next tick
                return null;
            }
        };
    }

    static double fetchEgyCdn() {
        return fetchEgyCdn(BROKER_PORT, 3000);
    }

    private static InetAddress ipv4Loopback() {
        try {
            return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (java.net.UnknownHostException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) b[i] = (byte) values[i];
        return b;
    }
}

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
        final double km;         // odometer when recorded, NaN when unknown (rows from before 2026-11-18)
        final double cellDiffMv; // highest minus lowest cell when recorded, NaN when unknown

        Entry(long ts, double pct) { this(ts, pct, CANSPI, Double.NaN, Double.NaN); }

        Entry(long ts, double pct, String source) { this(ts, pct, source, Double.NaN, Double.NaN); }

        Entry(long ts, double pct, String source, double km, double cellDiffMv) {
            this.ts = ts;
            this.pct = pct;
            this.source = source;
            this.km = km;
            this.cellDiffMv = cellDiffMv;
        }
    }

    private final File file;
    private final Supplier<Integer> gear;
    private final Callable<Double> fetch;
    static final String HEADER = "ts,soh_pct,source,odometer_km,cell_diff_mv";
    private volatile Supplier<Double> odometer = () -> Double.NaN;
    private volatile Supplier<Double> cellDiff = () -> Double.NaN;

    /** Cell spread in mV (from the read-only cell stream) stored next to every SOH row, for SOH against V diff. */
    void setCellDiff(Supplier<Double> cellDiff) { this.cellDiff = cellDiff; }

    /** Odometer in km (read only) stored next to every SOH row, for SOH per distance. */
    void setOdometer(Supplier<Double> odometer) { this.odometer = odometer; }
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

    final TripClock trip = new TripClock();

    /** The current GEAR_SELECTION (read only), also fed to the trip clock; null when unavailable. */
    Integer gearNow(long now) {
        Integer g = gear.get();
        trip.observe(g, now);
        return g;
    }

    synchronized void tick(long now) {
        Integer g = gearNow(now);
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
        Double read = odometer.get();
        double km = read != null && read >= 0 && read < 2_000_000 ? read : Double.NaN;
        Double spread = cellDiff.get();
        double diff = spread != null && spread >= 0 && spread < 1000 ? spread : Double.NaN;
        entries.add(new Entry(now, pct, source, km, diff));
        try {
            if (!file.exists()) {
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                Files.write(file.toPath(), (HEADER + "\n").getBytes(StandardCharsets.UTF_8));
            }
            Files.write(file.toPath(), (row(entries.get(entries.size() - 1)) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.w(TAG, "SOH history not saved", e);
        }
    }

    private static double optional(String[] parts, int i) {
        return parts.length > i && !parts[i].trim().isEmpty() ? Double.parseDouble(parts[i].trim()) : Double.NaN;
    }

    private static String row(Entry e) {
        return String.format(Locale.ROOT, "%d,%.2f,%s,%s,%s", e.ts, e.pct, e.source,
                Double.isNaN(e.km) ? "" : String.format(Locale.ROOT, "%.0f", e.km),
                Double.isNaN(e.cellDiffMv) ? "" : String.format(Locale.ROOT, "%.0f", e.cellDiffMv));
    }

    /** Logs only when the outcome changes, so a long park does not flood logcat. */
    private void note(String outcome) {
        if (outcome.equals(lastOutcome)) return;
        lastOutcome = outcome;
        Log.i(TAG, outcome);
    }

    /** Rewrites soh.csv in the current format through a temp file, so a power cut cannot lose the history. */
    private void upgrade() throws IOException {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Entry e : entries) out.append(row(e)).append('\n');
        File tmp = new File(file.getPath() + ".tmp");
        Files.write(tmp.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
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
                + ",\"km\":" + (last == null || Double.isNaN(last.km) ? "null" : String.format(Locale.ROOT, "%.0f", last.km))
                + ",\"count\":" + entries.size() + ",\"source\":" + (last == null ? "null" : "\"" + last.source + "\"")
                + ",\"state\":\"" + state + "\"}";
    }

    private void load() {
        if (!file.exists()) return;
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String[] parts = line.split(",", -1);
                if (parts.length < 2 || parts.length > 5) continue;
                try {
                    String source = parts.length >= 3 && BECM.equals(parts[2].trim()) ? BECM : CANSPI;
                    entries.add(new Entry(Long.parseLong(parts[0].trim()), Double.parseDouble(parts[1].trim()), source,
                            optional(parts, 3), optional(parts, 4)));
                } catch (NumberFormatException skip) { } // header or a damaged line
            }
            if (!lines.isEmpty() && !HEADER.equals(lines.get(0))) upgrade(); // older header: rewrite, rows kept
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
        return () -> {
            Object manager = CarProps.manager(context);
            if (manager == null) return null;
            try {
                return (Integer) manager.getClass().getMethod("getIntProperty", int.class, int.class)
                        .invoke(manager, propertyId, 0);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null; // unavailable this tick; the shared connection stays
            }
        };
    }

    /**
     * RANGE_REMAINING (0x11600308) through CarPropertyManager, read only, formatted like car_service output for
     * VehicleBatteryReader.parseRangeProbe. Needs android.car.permission.CAR_ENERGY (dangerous: granted at runtime).
     * This head unit flags the property UNAVAILABLE (status 2) while its value tracks the dashboard.
     */
    @android.annotation.SuppressLint("PrivateApi")
    static java.util.concurrent.Callable<String> carRange(android.content.Context context) {
        return () -> {
            Object manager = CarProps.manager(context);
            if (manager == null) return "";
            try {
                Object value = manager.getClass().getMethod("getProperty", Class.class, int.class, int.class)
                        .invoke(manager, Float.class, 0x11600308, 0);
                if (value == null) return "";
                int status = (Integer) value.getClass().getMethod("getStatus").invoke(value);
                Object km = value.getClass().getMethod("getValue").invoke(value);
                return km instanceof Float f ? rangeText(status, f) : "";
            } catch (ReflectiveOperationException | RuntimeException e) {
                return ""; // no CAR_ENERGY grant yet or unavailable: retry on the next tick

            }
        };
    }

    static String rangeText(int status, float km) {
        return "status: " + status + " floatValues: [" + km + "]";
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

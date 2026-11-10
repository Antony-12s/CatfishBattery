package com.catfish.battery;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Samples the read-only battery snapshot every 10 s into a CSV and records charge sessions: a charger plugged in
 * with energy going into the pack, or, found when the head unit wakes, energy that came in while it slept.
 * One class, plain synchronized, flat files only.
 */
public final class BatteryLogger {
    private static final String TAG = "CFBatteryLog";
    static final long INTERVAL_MS = 10000L;
    // ponytail: 4 MiB rotate ceiling with a single .1 backup (~5 days at 10 s/sample); add dated files if longer history matters.
    static final long ROTATE_BYTES = 4L << 20;
    static final long SESSION_ROTATE_BYTES = 256L << 10;
    static final String LOG_HEADER = "ts,soc,range_km,energy_wh,temp_c,volt_v,current_a,power_kw,cell_max_mv,cell_min_mv,cell_diff_mv,temp_max_c,temp_min_c,lv_v,soc_raw,dcdc_on,dcdc_hi_a,dcdc_lo_a,dc_charge_kw,cabin_w,batt_therm_w,conv_w,isg_v,isg_a,tot_drive,hv_cap_ah";
    static final String SESSION_HEADER = "start_ts,end_ts,soc_start,soc_end,kwh_in,peak_kw,max_temp_c,samples,kwh_added,type,parked";
    static final String TOTALS_HEADER = "since_ts,used_wh,charged_wh,regen_wh";
    private static final double OPEN_AMPS = -0.5d, CLOSE_AMPS = -0.2d;
    private static final int OPEN_SAMPLES = 3, CLOSE_SAMPLES = 6;
    private static final long ETA_WINDOW_MS = 5L * 60L * 1000L;
    static final long TOTALS_MAX_GAP_MS = 60000L;
    /** Row slot of isg_a (CSV column 23) in a parsed row, and the motor current below which energy in is regen. */
    private static final int ISG_A = 11;
    private static final double REGEN_AMPS = -1d;
    /** Least a parked charge must add: the SOC of a pack at rest drifts by about a percent. */
    static final double PARKED_MIN_WH = 1000d;
    /**
     * A gap in the log this long is the head unit asleep (the shortest seen on the car was 2 minutes). A shorter one,
     * such as an app update or a crash mid-charge, only carries the open session on, from charge-open.csv.
     */
    static final long PARKED_GAP_MS = 5L * 60L * 1000L;
    /** Pack energy per SOC point, from the car's own energy / SOC pairs (2026-09-17..29); only for a missing energy. */
    static final double WH_PER_SOC = 550d;

    private final File dir;
    private final VehicleBatteryReader reader;
    private final Totals totals;
    private ScheduledExecutorService worker;
    private Session session;
    private int chargeRun, idleRun;
    /** The last sample: a charger in and energy going into the pack. */
    private boolean charging;
    /** ts, SOC and pack Wh of the last live sample (NaN when unknown), and its plug. */
    private double[] last;
    private String lastPlug;
    /** The Battery tab's settings, as BatterySettings.clean() left them. */
    private String settings;
    /** SOC to announce while charging, 0 when off; and who announces it. */
    private int alertSoc;
    volatile java.util.function.IntConsumer onAlert;

    public BatteryLogger(Context context, VehicleBatteryReader reader) {
        this(new File(context.getApplicationContext().getFilesDir(), "battery-history"), reader);
    }

    BatteryLogger(File dir, VehicleBatteryReader reader) {
        this.dir = dir;
        this.reader = reader;
        dir.mkdirs();
        rotateIfHeaderChanged();
        long now = System.currentTimeMillis();
        totals = Totals.load(totalsFile(), now);
        if (!totalsFile().isFile()) saveTotals();
        // the head unit often powers down while parked: pick up where the log stopped, to see what came in meanwhile
        last = lastRow(logFile(), now + 86400000L);
        if (last == null) last = lastRow(rotatedFile(), now + 86400000L);
        session = Session.restore(openFile());
        settings = BatterySettings.clean(readText(settingsFile()));
        alertSoc = BatterySettings.alertSoc(settings);
    }

    /** A file written by an older build has a shorter header; keep it as the .1 backup and start fresh. */
    private void rotateIfHeaderChanged() {
        File file = logFile();
        if (!file.isFile() || file.length() == 0L) return;
        String first = null;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            try { first = in.readLine(); } finally { in.close(); }
        } catch (IOException e) { Log.w(TAG, "Battery log header check failed", e); }
        if (!LOG_HEADER.equals(first)) rotate(file);
    }

    private static void rotate(File file) {
        File old = new File(file.getParentFile(), file.getName().replace(".csv", ".1.csv"));
        old.delete();
        if (!file.renameTo(old)) file.delete();
    }

    public synchronized void start() {
        if (worker != null) return;
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CFBatteryLog"); t.setDaemon(true); return t;
        });
        worker.scheduleAtFixedRate(this::tick, INTERVAL_MS, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (worker != null) worker.shutdownNow();
        worker = null;
    }

    public File logFile() { return new File(dir, "battery-log.csv"); }
    File rotatedFile() { return new File(dir, "battery-log.1.csv"); }
    File sessionFile() { return new File(dir, "charge-sessions.csv"); }
    File totalsFile() { return new File(dir, "energy-totals.csv"); }
    File openFile() { return new File(dir, "charge-open.csv"); }
    File settingsFile() { return new File(dir, "battery-settings.json"); }
    /** Which charger the driver picked for a charge, by its start: start_ts,charger (empty = back to the default). */
    File chargerFile() { return new File(dir, "charge-chargers.csv"); }

    /** The saved settings, or the defaults when nothing was saved yet. */
    synchronized String settingsJson() {
        return "{\"saved\":" + settingsFile().isFile() + ",\"settings\":" + settings + "}";
    }

    /** Saves what a page sent, cleaned; returns the clean copy. */
    synchronized String saveSettings(String raw) {
        settings = BatterySettings.clean(raw);
        alertSoc = BatterySettings.alertSoc(settings);
        writeAtomic(settingsFile(), settings);
        return settingsJson();
    }

    /** The charger for the charge that started at {@code start}; an empty id puts it back on the default. */
    synchronized void setCharger(long start, String id) {
        if (start <= 0 || id == null || !id.matches("[a-z0-9]{0,24}")) throw new IllegalArgumentException("bad charger");
        try { append(chargerFile(), "start_ts,charger", start + "," + id, SESSION_ROTATE_BYTES); }
        catch (IOException e) { Log.w(TAG, "Charger pick write failed", e); }
    }

    /** start_ts -> charger id; the last pick of a charge wins, an empty one drops it. */
    private java.util.Map<Long, String> chargerPicks() {
        java.util.Map<Long, String> picks = new java.util.HashMap<>();
        for (String line : readText(chargerFile()).split("\n")) {
            String[] f = line.trim().split(",", -1);
            if (f.length < 2 || !f[1].matches("[a-z0-9]{0,24}") || Double.isNaN(parse(f[0]))) continue;
            if (f[1].isEmpty()) picks.remove((long) parse(f[0])); else picks.put((long) parse(f[0]), f[1]);
        }
        return picks;
    }

    private static String readText(File file) {
        if (!file.isFile()) return "";
        try { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
        catch (IOException e) { Log.w(TAG, file.getName() + " read failed", e); return ""; }
    }

    private void tick() {
        try { sample(reader.read(), System.currentTimeMillis()); }
        catch (Throwable t) { Log.w(TAG, "Battery sample failed", t); }
    }

    synchronized void sample(VehicleBatteryReader.Snapshot s, long ts) {
        boolean live = s != null && s.streamConnected;
        String plug = live ? plugType(s.dcChargeKw, s.flowJson) : null;
        Double amps = live ? s.battCurrentA : null;
        // the car's own signals: a charger plugged in, and energy going into the pack
        charging = plug != null && chargingNow(amps, s.isgCurrentA);
        boolean idle = plug == null && (amps == null || amps >= CLOSE_AMPS);
        boolean parked = false;
        if (live) {
            parked = parkedCharge(s, ts, plug);
            try { append(logFile(), LOG_HEADER, row(s, ts), ROTATE_BYTES); }
            catch (Throwable t) { Log.w(TAG, "Battery log write failed", t); }
            last = new double[]{ts, unbox(s.soc), unbox(s.energyWh)};
            lastPlug = plug;
        }
        addPower(ts, live ? s.battPowerKw : null, plug != null);
        if (charging) { chargeRun++; idleRun = 0; }
        else if (idle) { idleRun++; chargeRun = 0; }
        if (session == null && charging) session = new Session(ts, s.soc, s.energyWh);
        if (session != null && charging) session.add(s, ts, plug);
        if (session != null && chargeRun >= OPEN_SAMPLES) session.confirmed = true;
        if (session != null && session.confirmed && charging && alertDue(session.socStart, s.soc, alertSoc, session.alerted)) {
            session.alerted = true;
            java.util.function.IntConsumer alert = onAlert;
            if (alert != null) alert.accept(alertSoc);
        }
        // kept on disk while open: an app update or a crash mid-charge used to lose the whole session
        if (session != null && session.confirmed && (charging || parked)) writeAtomic(openFile(), session.state());
        // a charge ends when the plug is out and nothing goes in; one that finished stays open until then
        if (session != null && idleRun >= CLOSE_SAMPLES) {
            if (session.confirmed) {
                try { append(sessionFile(), SESSION_HEADER, session.csv(), SESSION_ROTATE_BYTES); }
                catch (Throwable t) { Log.w(TAG, "Charge session write failed", t); }
            }
            session = null;
            openFile().delete();
        }
        if (session != null && !session.confirmed && chargeRun == 0) session = null;
    }

    /**
     * A charge while the head unit slept: after a gap of PARKED_GAP_MS or more, the pack holds at least PARKED_MIN_WH
     * more than at the last sample before it. Only a charger adds energy to a parked car, so it counts even when no
     * plug was seen. Returns whether it opened or stretched a session.
     */
    private boolean parkedCharge(VehicleBatteryReader.Snapshot s, long ts, String plug) {
        if (last == null || ts - (long) last[0] < PARKED_GAP_MS) return false;
        if (!(addedWh(boxed(last[1]), s.soc, boxed(last[2]), s.energyWh) >= PARKED_MIN_WH)) return false;
        if (session == null) session = new Session((long) last[0], boxed(last[1]), boxed(last[2]));
        session.confirmed = true;
        session.parked = true;
        if (session.type == null) session.type = plug != null ? plug : lastPlug;
        session.endTs = ts;
        if (s.soc != null) session.socEnd = s.soc;
        if (s.energyWh != null) session.whEnd = s.energyWh;
        idleRun = 0;
        return true;
    }

    /**
     * The charge alert is due once per charge: when the SOC reaches the driver's mark, for a charge that began
     * below it (a top-up that starts above the mark says nothing). 0 is off.
     */
    static boolean alertDue(Double socStart, Double socNow, int alertSoc, boolean alerted) {
        return alertSoc > 0 && !alerted && socNow != null && socNow >= alertSoc && (socStart == null || socStart < alertSoc);
    }

    /** What went into the pack: the car's own energy figure, else the SOC change; NaN when neither is known. */
    static double addedWh(Double socStart, Double socEnd, Double whStart, Double whEnd) {
        if (whStart != null && whEnd != null) return whEnd - whStart;
        return socStart != null && socEnd != null ? (socEnd - socStart) * WH_PER_SOC : Double.NaN;
    }

    /**
     * Current into the pack from a charger. Regen also sends current into the pack, and every braking stretch
     * used to open a "charge session" of a few minutes with the SOC unchanged; the motor generating rules it out.
     */
    static boolean chargingNow(Double amps, Double isgAmps) {
        return amps != null && amps < OPEN_AMPS && !(isgAmps != null && isgAmps < REGEN_AMPS);
    }

    /** The confirmed open charge session while energy is going in, or null. */
    synchronized Session liveSession() {
        return session != null && session.confirmed && charging ? session : null;
    }

    synchronized void addPower(long ts, Double kw, boolean plugged) {
        if (totals.add(ts, kw, plugged)) saveTotals();
    }

    synchronized String totalsJson() { return totals.json(); }

    synchronized void resetTotals(long now) {
        totals.reset(now);
        saveTotals();
    }

    private void saveTotals() { writeAtomic(totalsFile(), TOTALS_HEADER + "\n" + totals.csv() + "\n"); }

    /** Written to a temp file and moved over the old one, so a power cut cannot leave half a file. */
    private static void writeAtomic(File file, String text) {
        File tmp = new File(file.getPath() + ".tmp");
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
            try { w.write(text); } finally { w.close(); }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { Log.w(TAG, file.getName() + " write failed", e); }
    }

    /**
     * "dc" or "ac" while the car says a charger is plugged in, else null: the page's rule of a charge handle state of
     * 1-3 (4-7 is the V2L adapter), or DC charge power, which the car sent during the one logged DC charge.
     */
    static String plugType(Double dcChargeKw, String flowJson) {
        if (chargeHandle(flowJson, "dcHandle") || dcChargeKw != null && dcChargeKw > 0.5d) return "dc";
        return chargeHandle(flowJson, "obcHandle") ? "ac" : null;
    }

    private static boolean chargeHandle(String flowJson, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[0-9.]+)").matcher(flowJson == null ? "" : flowJson);
        if (!m.find()) return false;
        double v = parse(m.group(1));
        return v >= 1d && v <= 3d;
    }

    private static String row(VehicleBatteryReader.Snapshot s, long ts) {
        Long max = cellMv(s.cells, s.cellMaxV, true);
        Long min = cellMv(s.cells, s.cellMinV, false);
        return ts + "," + field(s.soc) + "," + field(s.rangeKm) + "," + field(s.energyWh) + "," + field(s.batteryTempC) +
                "," + field(s.battVoltageV) + "," + field(s.battCurrentA) + "," + field(s.battPowerKw) +
                "," + (max == null ? "" : max) + "," + (min == null ? "" : min) +
                "," + (max == null || min == null ? "" : String.valueOf(max - min)) +
                "," + field(s.battTempMaxC) + "," + field(s.battTempMinC) + "," + field(s.lvBatteryV) + "," + field(s.socRaw) +
                "," + (s.dcdcOn == null ? "" : s.dcdcOn ? "1" : "0") + "," + field(s.dcdcHiA) + "," + field(s.dcdcLoA) + "," + field(s.dcChargeKw) +
                "," + field(s.cabinThermW) + "," + field(s.battThermW) + "," + field(s.dcdcW) + "," + field(s.isgVoltageV) + "," + field(s.isgCurrentA) + "," + field(s.totDrivePwr) + "," + field(s.hvBattCap);
    }

    /**
     * Highest or lowest cell in mV: from the per-cell stream first, as on the Status page, else the VHAL
     * max / min. This car sends no VHAL max / min, so without the cells the log had no cell spread at all.
     */
    static Long cellMv(List<VehicleBatteryReader.CellSample> cells, Double vhalV, boolean highest) {
        Long best = null;
        for (VehicleBatteryReader.CellSample c : cells) {
            if (c.millivolts < 2000 || c.millivolts > 5000) continue;
            if (best == null || (highest ? c.millivolts > best : c.millivolts < best)) best = (long) c.millivolts;
        }
        return best != null ? best : vhalV == null ? null : Long.valueOf(Math.round(vhalV * 1000d));
    }

    static void append(File file, String header, String line, long rotateBytes) throws IOException {
        if (file.length() > rotateBytes) rotate(file);
        boolean fresh = !file.exists() || file.length() == 0L;
        // A power cut mid-write leaves half a row; without a line break of our own the next row would be glued
        // onto it, and the glued timestamp read as a date far in the future.
        boolean torn = !fresh && !endsWithNewline(file);
        Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
        try {
            if (fresh) w.write(header + "\n");
            if (torn) w.write("\n");
            w.write(line + "\n");
            w.flush();
        } finally { w.close(); }
    }

    private static boolean endsWithNewline(File file) throws IOException {
        RandomAccessFile f = new RandomAccessFile(file, "r");
        try {
            f.seek(f.length() - 1);
            return f.read() == '\n';
        } finally { f.close(); }
    }

    public String historyJson(long sinceMs, int maxPoints) {
        List<double[]> rows = new ArrayList<>();
        // a day of slack for the clock; anything later is a glued row from before the torn-row fix
        long untilMs = System.currentTimeMillis() + 86400000L;
        readRows(rotatedFile(), sinceMs, untilMs, rows);
        readRows(logFile(), sinceMs, untilMs, rows);
        StringBuilder out = new StringBuilder("{\"points\":[");
        List<double[]> kept = downsample(rows, maxPoints);
        for (int i = 0; i < kept.size(); i++) {
            double[] r = kept.get(i);
            if (i > 0) out.append(',');
            out.append('[').append((long) r[0]).append(',').append(num(r[1])).append(',').append(num(r[7]))
               .append(',').append(num(r[4])).append(',').append(num(r[10])).append(']');
        }
        out.append("],\"stats\":").append(stats(rows)).append(',');
        return appendSessions(out, rows).append('}').toString();
    }

    /** Every recorded charge and the one going on now, for the History tab. */
    public String sessionsJson() {
        return appendSessions(new StringBuilder("{"), new ArrayList<>()).append('}').toString();
    }

    /** "sessions":[...],"live":...; the live ETA comes from the rows' SOC slope, so none without rows. */
    private StringBuilder appendSessions(StringBuilder out, List<double[]> rows) {
        java.util.Map<Long, String> picks = chargerPicks();
        out.append("\"sessions\":[");
        List<String> sessions = readSessions(picks);
        for (int i = 0; i < sessions.size(); i++) { if (i > 0) out.append(','); out.append(sessions.get(i)); }
        out.append("],\"live\":");
        Session live = liveSession();
        if (live == null) return out.append("null");
        double[] eta = eta(rows, live.socEnd);
        return out.append(live.json(eta[0], eta[1], picks.get(live.startTs)));
    }

    /** JSON for a charger id, which chargerPicks() and setCharger() keep to [a-z0-9]. */
    private static String chargerJson(String id) { return id == null ? "null" : "\"" + id + "\""; }

    static List<double[]> downsample(List<double[]> rows, int maxPoints) {
        if (maxPoints <= 0 || rows.size() <= maxPoints) return rows;
        List<double[]> kept = new ArrayList<>(maxPoints);
        for (int i = 0; i < maxPoints; i++) kept.add(rows.get((int) ((long) i * rows.size() / maxPoints)));
        kept.set(maxPoints - 1, rows.get(rows.size() - 1));
        return kept;
    }

    /**
     * The range's pack energy: out to the loads, in from a charger, in from regen. The log has no plug state,
     * so energy into the pack while the motor current is negative (generating) counts as regen.
     */
    static String stats(List<double[]> rows) {
        double socMin = Double.NaN, socMax = Double.NaN, diffMax = Double.NaN, out = 0d, in = 0d, regen = 0d;
        double[] prev = null;
        for (double[] r : rows) {
            if (!Double.isNaN(r[1])) { socMin = Double.isNaN(socMin) ? r[1] : Math.min(socMin, r[1]); socMax = Double.isNaN(socMax) ? r[1] : Math.max(socMax, r[1]); }
            if (!Double.isNaN(r[10])) diffMax = Double.isNaN(diffMax) ? r[10] : Math.max(diffMax, r[10]);
            double kwh = trapezoid(prev, r);
            if (kwh > 0d) out += kwh;
            else if (kwh < 0d && (r[ISG_A] < REGEN_AMPS || prev[ISG_A] < REGEN_AMPS)) regen -= kwh;
            else in -= kwh;
            prev = r;
        }
        double none = Double.NaN;
        return "{\"samples\":" + rows.size() + ",\"socMin\":" + num(socMin) + ",\"socMax\":" + num(socMax) +
                ",\"kwhIn\":" + num(rows.isEmpty() ? none : in) + ",\"kwhOut\":" + num(rows.isEmpty() ? none : out) +
                ",\"kwhRegen\":" + num(rows.isEmpty() ? none : regen) + ",\"cellDiffMax\":" + num(diffMax) + "}";
    }

    /**
     * Pack kWh between two rows, above 0 out of the pack. A gap longer than the energy totals' minute is the
     * head unit asleep and counts nothing, as on the Energy flow tab.
     */
    private static double trapezoid(double[] a, double[] b) {
        if (a == null || Double.isNaN(a[7]) || Double.isNaN(b[7])) return 0d;
        double ms = b[0] - a[0];
        return ms <= 0d || ms > TOTALS_MAX_GAP_MS ? 0d : (a[7] + b[7]) / 2d * ms / 3600000d;
    }

    /** [etaTo80Min, etaTo100Min] from dSOC/dt over the last five minutes of rows; NaN when unknown. */
    static double[] eta(List<double[]> rows, Double socNow) {
        double[] none = {Double.NaN, Double.NaN};
        if (rows.isEmpty() || socNow == null) return none;
        double[] last = rows.get(rows.size() - 1), first = null;
        for (double[] r : rows) if (r[0] >= last[0] - ETA_WINDOW_MS && !Double.isNaN(r[1])) { first = r; break; }
        if (first == null || Double.isNaN(last[1]) || last[0] <= first[0]) return none;
        double perMin = (last[1] - first[1]) / ((last[0] - first[0]) / 60000d);
        if (perMin <= 0d) return none;
        return new double[]{socNow >= 80d ? Double.NaN : (80d - socNow) / perMin, socNow >= 100d ? Double.NaN : (100d - socNow) / perMin};
    }

    private static void readRows(File file, long sinceMs, long untilMs, List<double[]> into) {
        if (!file.isFile() || file.lastModified() < sinceMs) return;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    int comma = line.indexOf(',');
                    if (comma <= 0 || parse(line.substring(0, comma)) < sinceMs) continue;
                    String[] f = line.split(",", -1);
                    if (f.length < 11) continue;
                    // ponytail: the chart columns 0-10 plus the motor current; widen when another column is needed
                    double[] r = new double[ISG_A + 1];
                    for (int i = 0; i < 11; i++) r[i] = parse(f[i]);
                    r[ISG_A] = f.length > 23 ? parse(f[23]) : Double.NaN;
                    if (Double.isNaN(r[0]) || r[0] < sinceMs || r[0] > untilMs) continue;
                    into.add(r);
                }
            } finally { in.close(); }
        } catch (Exception e) { Log.w(TAG, "Battery log read failed", e); }
    }

    /** Oldest first, each with the charger the driver picked for it. The file's 256 KiB rotation (about 2000 charges) bounds the list. */
    private List<String> readSessions(java.util.Map<Long, String> picks) {
        List<String> out = new ArrayList<>();
        if (!sessionFile().isFile()) return out;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(sessionFile()), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] f = line.split(",", -1);
                    if (f.length < 8 || line.startsWith("start_ts")) continue;
                    // rows from before the plug rule (8 fields) include regen stretches: only one that took in a kWh was a charge
                    boolean old = f.length < 11;
                    if (old && !(parse(f[4]) >= 1d)) continue;
                    String type = old ? "" : f[9];
                    out.add("{\"start\":" + (long) parse(f[0]) + ",\"end\":" + (long) parse(f[1]) +
                            ",\"socStart\":" + num(parse(f[2])) + ",\"socEnd\":" + num(parse(f[3])) +
                            ",\"kwhIn\":" + num(parse(f[4])) + ",\"peakKw\":" + num(parse(f[5])) +
                            ",\"maxTempC\":" + num(parse(f[6])) + ",\"samples\":" + (long) parse(f[7]) +
                            ",\"kwhAdded\":" + num(old ? Double.NaN : parse(f[8])) +
                            ",\"type\":" + ("ac".equals(type) || "dc".equals(type) ? "\"" + type + "\"" : "null") +
                            ",\"parked\":" + (!old && "1".equals(f[10])) +
                            ",\"charger\":" + chargerJson(picks.get((long) parse(f[0]))) + "}");
                }
            } finally { in.close(); }
        } catch (Exception e) { Log.w(TAG, "Charge session read failed", e); }
        return out;
    }

    /** ts, SOC and pack Wh of the file's last row before untilMs, or null; a torn or glued last row is skipped. */
    static double[] lastRow(File file, long untilMs) {
        if (!file.isFile() || file.length() == 0L) return null;
        try {
            RandomAccessFile f = new RandomAccessFile(file, "r");
            try {
                long from = Math.max(0L, f.length() - 4096L);
                byte[] tail = new byte[(int) (f.length() - from)];
                f.seek(from);
                f.readFully(tail);
                String[] lines = new String(tail, StandardCharsets.UTF_8).split("\n");
                for (int i = lines.length - 1; i >= 0; i--) {
                    String[] c = lines[i].split(",", -1);
                    double ts = c.length < 4 ? Double.NaN : parse(c[0]);
                    if (!Double.isNaN(ts) && ts <= untilMs) return new double[]{ts, parse(c[1]), parse(c[3])};
                }
            } finally { f.close(); }
        } catch (IOException e) { Log.w(TAG, "Battery log tail read failed", e); }
        return null;
    }

    private static double parse(String s) {
        if (s == null || s.isEmpty()) return Double.NaN;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return Double.NaN; }
    }

    /** CSV field: empty when unknown. */
    private static String field(Double v) { return v == null || v.isNaN() || v.isInfinite() ? "" : String.format(Locale.US, "%.3f", v); }
    /** JSON number: null when unknown. */
    private static String num(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? "null" : String.format(Locale.US, "%.3f", v); }
    private static double unbox(Double v) { return v == null ? Double.NaN : v; }
    private static Double boxed(double v) { return Double.isNaN(v) ? null : v; }

    /** Pack energy since the last reset (Wh): out to the loads, in from a charger, in from regen. Kept across reboots. */
    static final class Totals {
        long sinceTs;
        double usedWh, chargedWh, regenWh;
        private long prevTs;
        private double prevKw = Double.NaN;

        Totals(long sinceTs) { this.sinceTs = sinceTs; }

        /**
         * Adds the trapezoid from the previous sample; power above 0 leaves the pack. A gap over a minute
         * (the head unit asleep, a reboot) is not bridged. Returns whether a total moved.
         */
        boolean add(long ts, Double kw, boolean plugged) {
            boolean moved = false;
            if (kw != null && !kw.isNaN() && !Double.isNaN(prevKw) && ts > prevTs && ts - prevTs <= TOTALS_MAX_GAP_MS) {
                double wh = (prevKw + kw) / 2d * (ts - prevTs) / 3600d;
                if (wh > 0d) usedWh += wh; else if (plugged) chargedWh -= wh; else regenWh -= wh;
                moved = wh != 0d;
            }
            prevTs = ts;
            prevKw = kw == null ? Double.NaN : kw;
            return moved;
        }

        void reset(long now) {
            sinceTs = now;
            usedWh = chargedWh = regenWh = 0d;
        }

        String csv() { return sinceTs + "," + field(usedWh) + "," + field(chargedWh) + "," + field(regenWh); }

        String json() {
            return "{\"since\":" + sinceTs + ",\"usedWh\":" + num(usedWh) + ",\"chargedWh\":" + num(chargedWh) +
                    ",\"regenWh\":" + num(regenWh) + "}";
        }

        /** The saved totals; fresh ones counting from now when the file is missing or unreadable. */
        static Totals load(File file, long now) {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
                try {
                    in.readLine();
                    String[] f = String.valueOf(in.readLine()).split(",", -1);
                    Totals t = new Totals((long) parse(f[0]));
                    t.usedWh = parse(f[1]);
                    t.chargedWh = parse(f[2]);
                    t.regenWh = parse(f[3]);
                    if (!Double.isNaN(parse(f[0])) && !Double.isNaN(t.usedWh + t.chargedWh + t.regenWh)) return t;
                } finally { in.close(); }
            } catch (Exception e) {
                if (file.exists()) Log.w(TAG, "Energy totals unreadable, starting over", e);
            }
            return new Totals(now);
        }
    }

    /**
     * One charge session; opened on the first charging sample and confirmed after OPEN_SAMPLES, or opened at wake
     * from the last sample before the head unit slept (parked: the times bound the charge, they do not time it).
     */
    static final class Session {
        final long startTs;
        final Double socStart, whStart;
        long endTs, prevTs;
        Double socEnd, whEnd, prevPower, kwNow;
        double kwhIn, peakKw = Double.NaN, maxTempC = Double.NaN;
        int samples;
        boolean confirmed, parked;
        /** The charge alert went off for this charge. */
        boolean alerted;
        /** "ac" or "dc" once the car showed the plug, else null. */
        String type;

        Session(long startTs, Double soc, Double wh) {
            this.startTs = startTs; this.socStart = soc; this.socEnd = soc; this.whStart = wh; this.whEnd = wh; this.endTs = startTs;
        }

        void add(VehicleBatteryReader.Snapshot s, long ts, String plug) {
            samples++;
            endTs = ts;
            if (s.soc != null) socEnd = s.soc;
            if (s.energyWh != null) whEnd = s.energyWh;
            if (type == null || "dc".equals(plug)) type = plug;
            Double power = s.battPowerKw;
            kwNow = power;
            if (power != null && power < 0d) {
                peakKw = Double.isNaN(peakKw) ? -power : Math.max(peakKw, -power);
                if (prevPower != null && prevPower < 0d && ts > prevTs && ts - prevTs <= TOTALS_MAX_GAP_MS)
                    kwhIn += (-prevPower - power) / 2d * ((ts - prevTs) / 3600000d);
            }
            if (s.batteryTempC != null) maxTempC = Double.isNaN(maxTempC) ? s.batteryTempC : Math.max(maxTempC, s.batteryTempC);
            prevPower = power;
            prevTs = ts;
        }

        double kwhAdded() { return addedWh(socStart, socEnd, whStart, whEnd) / 1000d; }

        String csv() {
            return startTs + "," + endTs + "," + field(socStart) + "," + field(socEnd) + "," + field(kwhIn) + "," + field(peakKw) + "," + field(maxTempC) + "," + samples +
                    "," + field(kwhAdded()) + "," + (type == null ? "" : type) + "," + (parked ? 1 : 0);
        }

        /** The row plus both pack energies and the alert, which restore() needs to carry the session on. */
        String state() { return csv() + "," + field(whStart) + "," + field(whEnd) + "," + (alerted ? 1 : 0) + "\n"; }

        /** The session a killed process left open, or null. */
        static Session restore(File file) {
            if (!file.isFile()) return null;
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
                String[] f;
                try { f = String.valueOf(in.readLine()).split(",", -1); } finally { in.close(); }
                if (f.length < 13 || Double.isNaN(parse(f[0]))) return null;
                Session s = new Session((long) parse(f[0]), boxed(parse(f[2])), boxed(parse(f[11])));
                s.endTs = (long) parse(f[1]);
                s.socEnd = boxed(parse(f[3]));
                s.whEnd = boxed(parse(f[12]));
                s.kwhIn = Double.isNaN(parse(f[4])) ? 0d : parse(f[4]);
                s.peakKw = parse(f[5]);
                s.maxTempC = parse(f[6]);
                s.samples = (int) parse(f[7]);
                s.type = "ac".equals(f[9]) || "dc".equals(f[9]) ? f[9] : null;
                s.parked = "1".equals(f[10]);
                s.alerted = f.length > 13 && "1".equals(f[13].trim());
                s.confirmed = true;
                return s;
            } catch (Exception e) {
                Log.w(TAG, "Open charge session unreadable", e);
                return null;
            }
        }

        String json(double eta80, double eta100, String charger) {
            return "{\"start\":" + startTs + ",\"end\":" + endTs + ",\"socStart\":" + num(unbox(socStart)) +
                    ",\"socEnd\":" + num(unbox(socEnd)) + ",\"kwhIn\":" + num(kwhIn) + ",\"peakKw\":" + num(peakKw) +
                    ",\"maxTempC\":" + num(maxTempC) + ",\"samples\":" + samples + ",\"kwNow\":" + num(unbox(kwNow)) +
                    ",\"etaTo80Min\":" + num(eta80) + ",\"etaTo100Min\":" + num(eta100) +
                    ",\"kwhAdded\":" + num(kwhAdded()) + ",\"type\":" + (type == null ? "null" : "\"" + type + "\"") + ",\"parked\":" + parked +
                    ",\"charger\":" + chargerJson(charger) + "}";
        }
    }
}

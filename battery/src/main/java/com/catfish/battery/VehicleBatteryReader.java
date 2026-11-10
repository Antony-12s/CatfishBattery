package com.catfish.battery;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only view of the vehicle-property stream cache already used by Custom Profiles. */
public final class VehicleBatteryReader {
    private static final String TAG = "CFBatteryReader";
    private static final int SOC_PERCENT = 0x2160730E;
    private static final int SOC_DISPLAY = 0x21608057;
    private static final int SOC_APVP = 0x126005BA;
    // Explicitly selected for the web dashboard from the supplied EVCC export.
    private static final int RANGE_KM = 291504904;
    private static final int ENERGY_WH = 0x11600309;
    private static final int BATTERY_TEMP = 0x21607653;
    private static final int BATT_CURRENT_A = 308282801;
    private static final int BATT_VOLTAGE_V = 308282803;
    private static final int CELL_MAX_V = 0x126005BD;
    private static final int CELL_MIN_V = 0x126005BF;
    private static final int CELL_UI_ARRAY = 0x12700B9E;
    // Read-only ZCU->AP diagnostics on the same APVP subscription.
    private static final int HV_TEMP_MAX_C = 308282837;
    private static final int HV_TEMP_MIN_C = 308282838;
    private static final int HV_TEMP_AVG_C = 308282836;
    private static final int LV_BATTERY_V = 308282129;
    private static final int SOC_RAW = 308282181;
    // Battery health inputs (read-only, same subscription): estimated present capacity + HV pack capacity.
    // BattCpEstimdRaw is the numerator for a derived SOH; unit unconfirmed on this car, so expose it raw.
    private static final int BATT_CAP_ESTIMD = 306185030;
    private static final int HV_BATT_CAP = 308282798;
    // DC-DC converter and DC charging, read-only on the same subscription.
    private static final int DCDC_ACTIVE = 306185790;
    private static final int DCDC_HI_A = 308282947;
    private static final int DCDC_LO_A = 308282958;
    private static final int DC_CHARGE_STATE = 306185644;
    private static final int DC_CHARGE_KW = 308282795;
    // HV load breakdown and ISG bus. The three PwrCns/PwrAct counters are watts (HvConvPwrAct 620 against the measured
    // DC-DC 13.6 V x 44 A on 2026-09-29); TotDrvPwrAct is logged raw, scale unknown.
    private static final int CABIN_THERM_W = 306184675;
    private static final int BATT_THERM_W = 306184674;
    private static final int DCDC_W = 306184676;
    private static final int ISG_CURRENT_A = 308281832;
    private static final int ISG_VOLTAGE_V = 308282990;
    private static final int TOT_DRIVE_PWR = 306184741;
    // Energy-flow tab (read-only, same subscription): HV relay, on-board charger, plug handles, charge lid, session
    // energy / ETA / target SOC, V2L output, warnings. Exposed raw under "flow" in the JSON; enums decoded in the page.
    static final int[] FLOW_IDS = {306185657, 306185799, 308282952, 308282950, 306185797, 306185645, 306184632, 306184633,
            308282806, 306185647, 308282987, 291504192, 308282946, 308282944, 306185807, 306185808, 306185842};
    static final String[] FLOW_KEYS = {"relay", "obcState", "obcV", "obcA", "obcHandle", "dcHandle", "lidAc", "lidDc",
            "chargeKwh", "chargeEtaMin", "driveEtaMin", "targetSoc", "v2lV", "v2lA", "dischargeWarn", "dischargeFault", "fullChargeCaution"};
    /** Per-card freshness for the flow page: key -> property whose arrival time is reported as ageMs. */
    private static final String[] AGE_KEYS = {"pack", "motor", "cabin", "therm", "dcdc", "lv", "chg", "dc", "v2l", "relay"};
    private static final int[] AGE_IDS = {308282801, ISG_CURRENT_A, CABIN_THERM_W, BATT_THERM_W, DCDC_W, LV_BATTERY_V, 308282950, DC_CHARGE_KW, 308282944, 306185657};
    public static final int EXPECTED_CELL_COUNT = 118;
    public static final int EXPECTED_CELL_TEMP_COUNT = 12;

    private final Context context;
    private final LinkedHashMap<Integer, CellSample> cells = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, CellSample> observedCells = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, Double> observedScalars = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, Long> observedAtMs = new LinkedHashMap<>();
    /** Latest temperature per sensor 1..12 from PA_HvBattCellTinfo_Res; collected passively, never requested. */
    private final LinkedHashMap<Integer, Integer> cellTemps = new LinkedHashMap<>();
    private final Set<Integer> loggedScalarProperties = new HashSet<>();
    /** Shared VHAL stream (port 40004) that the rest of the app already listens to; attached read-only. */
    private VehiclePropertyObserver observer;
    private final VehiclePropertyObserver.VehiclePropertyListener listener = new VehiclePropertyObserver.VehiclePropertyListener() {
        @Override public void onPropertyUpdated(int propId, int areaId, int intValue, float floatValue, boolean isFloat) {
            acceptScalar(isFloat, propId, intValue, floatValue);
        }
        @Override public void onPropertyAvailabilityChanged(int propId, int areaId, boolean available) {
            if (!available) synchronized (VehicleBatteryReader.this) { observedScalars.remove(propId); }
        }
        @Override public void onConnectionStateChanged(boolean connected) {
            if (!connected) synchronized (VehicleBatteryReader.this) { observedScalars.keySet().removeIf(id -> !isApvpScalar(id)); }
        }
    };
    private final ApvpBatteryStream apvp;
    private boolean apvpConnected;
    private boolean analyzing;
    private long analysisStartedAtMs;
    private long lastCellUpdateAtMs;
    private long lastObservedCellUpdateAtMs;
    private int cellPayloadCount;
    private int lastCellPayloadLength;
    private ScheduledExecutorService rangeExecutor;
    private Double probedRangeKm;
    /** VehiclePropertyStatus 0/1/2 from the probe; -1 probe failed; -2 no probe configured. */
    private int rangeStatus = -2;
    private static final Pattern PROBE_STATUS = Pattern.compile("status: (\\d+)");
    private static final Pattern PROBE_FLOAT = Pattern.compile("floatValues: \\[([-0-9.]+)\\]");

    public VehicleBatteryReader(Context context) {
        this.context = context.getApplicationContext();
        try {
            // Attach before the server's initial SendAllPropertyValuesToStream response arrives.
            ensureClient();
        } catch (Exception e) {
            Log.w(TAG, "Initial vehicle stream attachment unavailable", e);
        }
        apvp = new ApvpBatteryStream(new ApvpBatteryStream.Listener() {
            @Override public void scalar(int id, double value) { acceptScalar(true,id,0,value); }
            @Override public void cells(int[] payload) { acceptCellArray(payload); }
            @Override public void cellTemps(int[] payload) { acceptCellTempArray(payload); }
            @Override public void connected(boolean connected) {
                synchronized (VehicleBatteryReader.this) {
                    apvpConnected=connected;
                    if (connected) { beginAnalysis(); return; }
                    observedScalars.keySet().removeIf(VehicleBatteryReader::isApvpScalar);
                    cells.clear(); observedCells.clear(); cellTemps.clear();
                    lastCellUpdateAtMs=0; lastObservedCellUpdateAtMs=0;
                    cellPayloadCount=0; lastCellPayloadLength=0;
                }
            }
        });
        beginAnalysis();
    }

    /**
     * The gRPC daemon never pushes RANGE_REMAINING, so poll car_service as root instead.
     * ponytail: one root shell every 20 s; upgrade path = declare CAR_ENERGY in the manifest and use
     * CarPropertyManager (needs a full apktool rebuild).
     */
    public synchronized void setRangeProbe(final Callable<String> probe) {
        if (rangeExecutor != null) rangeExecutor.shutdownNow();
        rangeStatus = -1;
        rangeExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CFRangeProbe"); t.setDaemon(true); return t;
        });
        rangeExecutor.scheduleWithFixedDelay(() -> {
            double[] parsed;
            try { parsed = parseRangeProbe(probe.call()); }
            catch (Throwable t) { Log.w(TAG, "Range probe failed", t); parsed = new double[]{-1, Double.NaN}; }
            synchronized (VehicleBatteryReader.this) {
                rangeStatus = (int) parsed[0];
                probedRangeKm = Double.isNaN(parsed[1]) ? null : parsed[1];
            }
        }, 3, 20, TimeUnit.SECONDS);
    }

    /**
     * Returns {status, km}: status -1 when unparsable, km NaN unless status is 0 or 2 and 0..1500.
     * Status 2 (UNAVAILABLE) is accepted on purpose: this head unit's VHAL flags RANGE_REMAINING
     * unavailable permanently while the value tracks the dashboard (451 km on both, 2026-09-17).
     */
    static double[] parseRangeProbe(String text) {
        Matcher status = PROBE_STATUS.matcher(text == null ? "" : text);
        if (!status.find()) return new double[]{-1, Double.NaN};
        int code = Integer.parseInt(status.group(1));
        Matcher value = PROBE_FLOAT.matcher(text);
        double km = Double.NaN;
        if ((code == 0 || code == 2) && value.find()) {
            try { km = Double.parseDouble(value.group(1)); } catch (NumberFormatException ignored) {}
            if (km < 0 || km > 1500) km = Double.NaN;
        }
        return new double[]{code, km};
    }

    /** Restarts the 118-cell collection on request (POST /batt-analyze); nothing on the page calls it any more. */
    public synchronized Snapshot startAnalyzing() {
        beginAnalysis();
        return read();
    }

    /**
     * Collection is automatic and read-only: it starts with the reader, restarts on every APVP
     * reconnect and whenever read() finds no collected cell, seeded from the passively observed cells.
     */
    private synchronized void beginAnalysis() {
        cells.clear();
        cells.putAll(observedCells);
        analyzing = true;
        analysisStartedAtMs = System.currentTimeMillis();
        lastCellUpdateAtMs = lastObservedCellUpdateAtMs;
    }

    public synchronized Snapshot read() {
        if (cells.isEmpty()) beginAnalysis();
        try {
            ensureClient();
            boolean vhalConnected = observer.isConnected();
            if (!vhalConnected && !apvpConnected) return unavailableSnapshot();
            Map<Integer, Float> floats = VehiclePropertyManager.floatCache();
            Map<Integer, Integer> integers = VehiclePropertyManager.intCache();
            Double soc = firstReading(floats, integers, 0d, 100d, SOC_PERCENT, SOC_DISPLAY, SOC_APVP);
            String source = reading(floats, integers, SOC_PERCENT, 0d, 100d) != null ? "TYPE_EV_BATTERY_PERCENTAGE" :
                    reading(floats, integers, SOC_DISPLAY, 0d, 100d) != null ? "DispHvBattLvlOfChrg" :
                    reading(floats, integers, SOC_APVP, 0d, 100d) != null ? "HvBattSoc" : "";
            Double cellMax = reading(floats, integers, CELL_MAX_V, 2d, 5d);
            Double cellMin = reading(floats, integers, CELL_MIN_V, 2d, 5d);
            if (cellMax != null && cellMin != null && (cellMax < cellMin || cellMax - cellMin > 1d)) {
                cellMax = null;
                cellMin = null;
            }
            Double battVoltage = apvpConnected ? value(observedScalars, BATT_VOLTAGE_V, 0d, 1000d) : null;
            Double battCurrent = apvpConnected ? value(observedScalars, BATT_CURRENT_A, -1000d, 1000d) : null;
            Double range = reading(floats, integers, RANGE_KM, 0d, 1500d);
            String rangeSource = range != null ? "VHAL cache" : probedRangeKm != null ? "car_service" : "";
            if (range == null) range = probedRangeKm;
            // DcDcActvd: 1 = ConversionToLVSide (on); DCChrgSt: 0 = Idle, otherwise charging via DC.
            Double dcdcActive = reading(floats, integers, DCDC_ACTIVE, 0d, 255d);
            Double dcChargeState = reading(floats, integers, DC_CHARGE_STATE, 0d, 255d);
            return new Snapshot(true, soc, source, range, rangeStatus, rangeSource,
                    reading(floats, integers, ENERGY_WH, 0d, 200000d),
                    reading(floats, integers, BATTERY_TEMP, -40d, 120d), battVoltage, battCurrent,
                    powerKw(battVoltage, battCurrent), cellMax, cellMin, cellSnapshot(), analyzing,
                    analysisStartedAtMs, lastCellUpdateAtMs, cellPayloadCount, lastCellPayloadLength,
                    reading(floats, integers, HV_TEMP_MAX_C, -40d, 120d),
                    reading(floats, integers, HV_TEMP_MIN_C, -40d, 120d),
                    reading(floats, integers, HV_TEMP_AVG_C, -40d, 120d),
                    reading(floats, integers, LV_BATTERY_V, 6d, 20d),
                    reading(floats, integers, SOC_RAW, 0d, 100d), cellTempSnapshot(),
                    dcdcActive == null ? null : dcdcActive != 0d,
                    reading(floats, integers, DCDC_HI_A, 0d, 50d), reading(floats, integers, DCDC_LO_A, 0d, 300d),
                    dcChargeState == null ? null : dcChargeState.intValue(),
                    reading(floats, integers, DC_CHARGE_KW, 0d, 400d),
                    reading(floats, integers, CABIN_THERM_W, 0d, 40000d), reading(floats, integers, BATT_THERM_W, 0d, 40000d),
                    reading(floats, integers, DCDC_W, 0d, 10000d), reading(floats, integers, ISG_CURRENT_A, -1000d, 1000d),
                    reading(floats, integers, ISG_VOLTAGE_V, 0d, 1000d), reading(floats, integers, TOT_DRIVE_PWR, -1000000d, 1000000d),
                    reading(floats, integers, BATT_CAP_ESTIMD, 0d, 1000000d), reading(floats, integers, HV_BATT_CAP, 0d, 1000000d),
                    apvpConnected ? flowJson() : "{}");
        } catch (Exception e) {
            Log.w(TAG, "Battery stream cache unavailable", e);
            return unavailableSnapshot();
        }
    }

    private synchronized void ensureClient() {
        if (observer != null) return;
        observer = new VehiclePropertyManager(context).getObserver();
        observer.addListener(listener);
        Log.i(TAG, "Read-only battery observer attached");
    }

    private synchronized void acceptScalar(boolean floating, int propertyId, Number integerValue,
                                           Number floatValue) {
        if (!isTrackedScalar(propertyId)) return;
        double value = floating ? floatValue.doubleValue() : integerValue.doubleValue();
        if (Double.isFinite(value)) {
            observedScalars.put(propertyId, value);
            observedAtMs.put(propertyId, System.currentTimeMillis());
            if (loggedScalarProperties.add(propertyId))
                Log.i(TAG, "Observed battery property " + propertyId + " = " + value);
        }
    }

    private static boolean isTrackedScalar(int propertyId) {
        return propertyId == SOC_PERCENT || propertyId == SOC_DISPLAY || propertyId == SOC_APVP ||
                propertyId == RANGE_KM || propertyId == ENERGY_WH || propertyId == BATTERY_TEMP ||
                propertyId == CELL_MAX_V || propertyId == CELL_MIN_V || isApvpScalar(propertyId);
    }

    /** Scalars that arrive on the APVP subscription (dropped together when it disconnects). */
    private static boolean isApvpScalar(int propertyId) {
        return propertyId == BATT_CURRENT_A || propertyId == BATT_VOLTAGE_V ||
                propertyId == HV_TEMP_MAX_C || propertyId == HV_TEMP_MIN_C || propertyId == HV_TEMP_AVG_C ||
                propertyId == LV_BATTERY_V || propertyId == SOC_RAW ||
                propertyId == DCDC_ACTIVE || propertyId == DCDC_HI_A || propertyId == DCDC_LO_A ||
                propertyId == DC_CHARGE_STATE || propertyId == DC_CHARGE_KW ||
                propertyId == CABIN_THERM_W || propertyId == BATT_THERM_W || propertyId == DCDC_W ||
                propertyId == ISG_CURRENT_A || propertyId == ISG_VOLTAGE_V || propertyId == TOT_DRIVE_PWR ||
                propertyId == BATT_CAP_ESTIMD || propertyId == HV_BATT_CAP || isFlowId(propertyId);
    }

    private static boolean isFlowId(int propertyId) {
        for (int id : FLOW_IDS) if (id == propertyId) return true;
        return false;
    }

    /** {"relay":1,...,"ageMs":{"pack":800,...}} from the APVP cache; null where a signal has not arrived yet. */
    private synchronized String flowJson() {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < FLOW_IDS.length; i++)
            b.append(i > 0 ? "," : "").append('"').append(FLOW_KEYS[i]).append("\":")
             .append(Snapshot.number(value(observedScalars, FLOW_IDS[i], -1e9, 1e9)));
        b.append(",\"ageMs\":{");
        long now = System.currentTimeMillis();
        for (int i = 0; i < AGE_IDS.length; i++) {
            Long at = observedAtMs.get(AGE_IDS[i]);
            b.append(i > 0 ? "," : "").append('"').append(AGE_KEYS[i]).append("\":").append(at == null ? "null" : String.valueOf(now - at));
        }
        return b.append("}}").toString();
    }

    private synchronized void acceptCellTempArray(int[] raw) {
        int[] decoded = decodeCellTemp(raw);
        if (decoded == null) return;
        Integer previous = cellTemps.put(decoded[0], decoded[1]);
        if (previous == null)
            Log.i(TAG, "Cell temperature sensor " + decoded[0] + " = " + decoded[1] + " C, total " +
                    cellTemps.size() + "/" + EXPECTED_CELL_TEMP_COUNT);
    }

    /** PA_HvBattCellTinfo_Res data[6] = valid, temp+40, sensor 1..12, 0, 0, 0; returns {sensor, degC} or null. */
    static int[] decodeCellTemp(int[] raw) {
        if (raw == null || raw.length < 3) return null;
        int valid = byteValue(raw[0]), offsetTemp = byteValue(raw[1]), sensor = byteValue(raw[2]);
        int temp = offsetTemp - 40;
        if (valid != 1 || sensor < 1 || sensor > EXPECTED_CELL_TEMP_COUNT || offsetTemp < 0 ||
                temp < -40 || temp > 120) return null;
        return new int[]{sensor, temp};
    }

    private synchronized List<CellTemp> cellTempSnapshot() {
        ArrayList<CellTemp> out = new ArrayList<>();
        for (int sensor = 1; sensor <= EXPECTED_CELL_TEMP_COUNT; sensor++) {
            Integer temp = cellTemps.get(sensor);
            if (temp != null) out.add(new CellTemp(sensor, temp));
        }
        return out;
    }

    private synchronized void acceptCellArray(int[] raw) {
        List<CellSample> decoded = decodeCellSamples(raw);
        cellPayloadCount++;
        lastCellPayloadLength = raw == null ? 0 : raw.length;
        if (decoded.isEmpty()) {
            if (cellPayloadCount == 1)
                Log.i(TAG, "Cell struct received: " + lastCellPayloadLength +
                        " integers, no valid six-byte record decoded");
            return;
        }
        int previousCount = observedCells.size();
        long now = System.currentTimeMillis();
        for (CellSample sample : decoded) {
            observedCells.put(sample.cell, sample);
            if (analyzing) cells.put(sample.cell, sample);
        }
        lastObservedCellUpdateAtMs = now;
        if (analyzing) lastCellUpdateAtMs = now;
        if (observedCells.size() != previousCount || cellPayloadCount == 1)
            Log.i(TAG, "Cell struct received: " + lastCellPayloadLength + " integers, decoded " +
                    decoded.size() + " records, total " + observedCells.size() + "/" +
                    EXPECTED_CELL_COUNT);
    }

    private synchronized List<CellSample> cellSnapshot() {
        ArrayList<CellSample> out = new ArrayList<>(cells.values());
        Collections.sort(out, new Comparator<CellSample>() {
            @Override public int compare(CellSample a, CellSample b) { return a.cell - b.cell; }
        });
        return out;
    }

    /** Decodes the observed 24-byte payload: valid, U1, U2, U3, U4 high, U4 low. */
    static List<CellSample> decodeCellSamples(int[] raw) {
        ArrayList<CellSample> best = new ArrayList<>();
        if (raw == null || raw.length < 6) return best;
        int lastOffset = Math.min(5, raw.length - 6);
        for (int offset = 0; offset <= lastOffset; offset++) {
            ArrayList<CellSample> candidate = new ArrayList<>();
            for (int i = offset; i + 5 < raw.length; i += 6) {
                int valid = byteValue(raw[i]);
                int cell = byteValue(raw[i + 1]);
                int u2 = byteValue(raw[i + 2]);
                int u3 = byteValue(raw[i + 3]);
                int high = byteValue(raw[i + 4]);
                int low = byteValue(raw[i + 5]);
                if (valid < 0 || cell < 0 || u2 < 0 || u3 < 0 || high < 0 || low < 0) continue;
                int packed = (high << 8) | low;
                int millivolts = packed >>> 3;
                if (valid == 1 && cell >= 1 && cell <= EXPECTED_CELL_COUNT &&
                        millivolts >= 2000 && millivolts <= 5000)
                    candidate.add(new CellSample(cell, u2, u3, millivolts, packed & 7));
            }
            if (candidate.size() > best.size()) best = candidate;
        }
        return best;
    }

    private static int byteValue(int value) { return value >= -128 && value <= 255 ? value & 0xff : -1; }

    static Double value(Map<Integer, ?> values, int id, double low, double high) {
        Object raw = values == null ? null : values.get(id);
        if (!(raw instanceof Number)) return null;
        double number = ((Number) raw).doubleValue();
        return Double.isFinite(number) && number >= low && number <= high ? number : null;
    }

    private synchronized Double reading(Map<Integer, ?> floats, Map<Integer, ?> integers, int id,
                                        double low, double high) {
        Double found = value(observedScalars, id, low, high);
        if (found == null) found = value(floats, id, low, high);
        if (found == null) found = value(integers, id, low, high);
        return found;
    }

    private synchronized Double firstReading(Map<Integer, ?> floats, Map<Integer, ?> integers,
                                             double low, double high, int... ids) {
        for (int id : ids) {
            Double found = reading(floats, integers, id, low, high);
            if (found != null) return found;
        }
        return null;
    }

    static Double powerKw(Double voltageV, Double currentA) {
        if (voltageV == null || currentA == null) return null;
        double power = voltageV * currentA / 1000d;
        return Double.isFinite(power) ? power : null;
    }

    public void close() {
        apvp.close();
        synchronized (this) {
            if (rangeExecutor != null) rangeExecutor.shutdownNow();
            if (observer != null) { observer.removeListener(listener); observer = null; }
        }
    }

    private synchronized Snapshot unavailableSnapshot() {
        return new Snapshot(false, null, "", null, rangeStatus, "", null, null, null, null, null, null, null,
                cellSnapshot(), analyzing, analysisStartedAtMs, lastCellUpdateAtMs,
                cellPayloadCount, lastCellPayloadLength, null, null, null, null, null, cellTempSnapshot(),
                null, null, null, null, null, null, null, null, null, null, null, null, null, "{}");
    }

    public static final class Snapshot {
        public final boolean streamConnected;
        public final Double soc, rangeKm, energyWh, batteryTempC, battVoltageV, battCurrentA,
                battPowerKw, cellMaxV, cellMinV, battTempMaxC, battTempMinC, battTempAvgC, lvBatteryV, socRaw,
                dcdcHiA, dcdcLoA, dcChargeKw;
        /** HvCabinThermPwrCns, HvBattThermPwrCns, HvConvPwrAct (W); IsgIDc (A), IsgUDc (V); TotDrvPwrAct raw (log only). */
        public final Double cabinThermW, battThermW, dcdcW, isgCurrentA, isgVoltageV, totDrivePwr;
        /** BattCpEstimdRaw (present capacity estimate, raw) and HvBattCap; SOH = battCapEstimd / nominal. */
        public final Double battCapEstimd, hvBattCap;
        /** Pre-serialised energy-flow scalars (see FLOW_KEYS) plus per-card ageMs; "{}" when the APVP stream is down. */
        public final String flowJson;
        /** DcDcActvd == 1 (ConversionToLVSide); null until seen. */
        public final Boolean dcdcOn;
        /** DCChrgSt raw enum, 0 = Idle; null until seen. */
        public final Integer dcChargeState;
        public final List<CellTemp> cellTemps;
        public final int cellTempCount;
        public final String socSource, rangeSource;
        public final int rangeStatus;
        public final List<CellSample> cells;
        public final boolean analyzing, analysisComplete;
        public final long analysisStartedAtMs, lastCellUpdateAtMs;
        public final int cellPayloadCount, lastCellPayloadLength;

        Snapshot(boolean streamConnected, Double soc, String socSource, Double rangeKm, int rangeStatus,
                 String rangeSource, Double energyWh, Double batteryTempC, Double battVoltageV, Double battCurrentA, Double battPowerKw,
                 Double cellMaxV, Double cellMinV, List<CellSample> cells, boolean analyzing,
                 long analysisStartedAtMs, long lastCellUpdateAtMs, int cellPayloadCount,
                 int lastCellPayloadLength, Double battTempMaxC, Double battTempMinC, Double battTempAvgC,
                 Double lvBatteryV, Double socRaw, List<CellTemp> cellTemps, Boolean dcdcOn, Double dcdcHiA,
                 Double dcdcLoA, Integer dcChargeState, Double dcChargeKw, Double cabinThermW, Double battThermW,
                 Double dcdcW, Double isgCurrentA, Double isgVoltageV, Double totDrivePwr,
                 Double battCapEstimd, Double hvBattCap, String flowJson) {
            this.streamConnected = streamConnected;
            this.soc = soc; this.socSource = socSource; this.rangeKm = rangeKm; this.energyWh = energyWh;
            this.rangeStatus = rangeStatus; this.rangeSource = rangeSource;
            this.batteryTempC = batteryTempC; this.battVoltageV = battVoltageV;
            this.battCurrentA = battCurrentA; this.battPowerKw = battPowerKw;
            this.cellMaxV = cellMaxV; this.cellMinV = cellMinV;
            this.cells = cells;
            this.analyzing = analyzing;
            this.analysisComplete = cells.size() >= EXPECTED_CELL_COUNT;
            this.analysisStartedAtMs = analysisStartedAtMs;
            this.lastCellUpdateAtMs = lastCellUpdateAtMs;
            this.cellPayloadCount = cellPayloadCount;
            this.lastCellPayloadLength = lastCellPayloadLength;
            this.battTempMaxC = battTempMaxC; this.battTempMinC = battTempMinC; this.battTempAvgC = battTempAvgC;
            this.lvBatteryV = lvBatteryV; this.socRaw = socRaw;
            this.cellTemps = cellTemps;
            this.cellTempCount = cellTemps.size();
            this.dcdcOn = dcdcOn; this.dcdcHiA = dcdcHiA; this.dcdcLoA = dcdcLoA;
            this.dcChargeState = dcChargeState; this.dcChargeKw = dcChargeKw;
            this.cabinThermW = cabinThermW; this.battThermW = battThermW; this.dcdcW = dcdcW;
            this.isgCurrentA = isgCurrentA; this.isgVoltageV = isgVoltageV; this.totDrivePwr = totDrivePwr;
            this.battCapEstimd = battCapEstimd; this.hvBattCap = hvBattCap;
            this.flowJson = flowJson;
        }

        public String toJson() {
            return "{\"streamConnected\":" + streamConnected + ",\"soc\":" + number(soc) +
                    ",\"socSource\":\"" + socSource + "\",\"rangeKm\":" + number(rangeKm) +
                    ",\"rangeStatus\":" + rangeStatus + ",\"rangeSource\":\"" + rangeSource + "\"" +
                    ",\"energyWh\":" + number(energyWh) +
                    ",\"batteryTempC\":" + number(batteryTempC) +
                    ",\"battVoltageV\":" + number(battVoltageV) +
                    ",\"battCurrentA\":" + number(battCurrentA) +
                    ",\"battPowerKw\":" + number(battPowerKw) +
                    ",\"cellMaxV\":" + number(cellMaxV) + ",\"cellMinV\":" + number(cellMinV) +
                    ",\"expectedCellCount\":" + EXPECTED_CELL_COUNT +
                    ",\"analyzing\":" + analyzing + ",\"analysisComplete\":" + analysisComplete +
                    ",\"analysisStartedAtMs\":" + analysisStartedAtMs +
                    ",\"lastCellUpdateAtMs\":" + lastCellUpdateAtMs +
                    ",\"cellPayloadCount\":" + cellPayloadCount +
                    ",\"lastCellPayloadLength\":" + lastCellPayloadLength +
                    ",\"cells\":" + cellsJson(cells) +
                    ",\"battTempMaxC\":" + number(battTempMaxC) + ",\"battTempMinC\":" + number(battTempMinC) +
                    ",\"battTempAvgC\":" + number(battTempAvgC) + ",\"lvBatteryV\":" + number(lvBatteryV) +
                    ",\"socRaw\":" + number(socRaw) +
                    ",\"expectedCellTempCount\":" + EXPECTED_CELL_TEMP_COUNT +
                    ",\"cellTempCount\":" + cellTempCount + ",\"cellTemps\":" + cellsJson(cellTemps) +
                    ",\"dcdcOn\":" + dcdcOn + ",\"dcdcHiA\":" + number(dcdcHiA) + ",\"dcdcLoA\":" + number(dcdcLoA) +
                    ",\"dcChargeState\":" + dcChargeState + ",\"dcChargeKw\":" + number(dcChargeKw) +
                    ",\"cabinThermW\":" + number(cabinThermW) + ",\"battThermW\":" + number(battThermW) + ",\"dcdcW\":" + number(dcdcW) +
                    ",\"isgCurrentA\":" + number(isgCurrentA) + ",\"isgVoltageV\":" + number(isgVoltageV) + ",\"totDrivePwr\":" + number(totDrivePwr) +
                    ",\"battCapEstimd\":" + number(battCapEstimd) + ",\"hvBattCap\":" + number(hvBattCap) +
                    ",\"flow\":" + flowJson + "}";
        }

        private static String cellsJson(List<?> cells) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) out.append(',');
                Object c = cells.get(i);
                out.append(c instanceof CellTemp ? ((CellTemp) c).toJson() : ((CellSample) c).toJson());
            }
            return out.append(']').toString();
        }

        private static String number(Double value) {
            return value == null ? "null" : String.format(Locale.US, "%.3f", value);
        }
    }

    public static final class CellSample {
        public final int cell, u2, u3, millivolts, flags;
        CellSample(int cell, int u2, int u3, int millivolts, int flags) {
            this.cell = cell; this.u2 = u2; this.u3 = u3;
            this.millivolts = millivolts; this.flags = flags;
        }
        String toJson() {
            return "{\"cell\":" + cell + ",\"u2\":" + u2 + ",\"u3\":" + u3 +
                    ",\"millivolts\":" + millivolts + ",\"flags\":" + flags + "}";
        }
    }

    public static final class CellTemp {
        public final int sensor, tempC;
        CellTemp(int sensor, int tempC) { this.sensor = sensor; this.tempC = tempC; }
        String toJson() { return "{\"sensor\":" + sensor + ",\"tempC\":" + tempC + "}"; }
    }
}

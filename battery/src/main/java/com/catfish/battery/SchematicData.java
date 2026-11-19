package com.catfish.battery;

import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Snapshot of the source readings used by the schematic. */
final class SchematicData {
    final double[] cells, temperatures, shares;
    final String[] loadPower, sensorReadings;
    final SchematicReadings.Band[] cellBands, sensorBands;
    final SchematicReadings.Cells extremes;
    final boolean hvOn, dcOn, plugged, dcPlug, charging;
    final String soc, soh, packPower, range, packVA, loadsTitle, dcState, dcHigh, dcLow,
            lvSoc, lvVoltage, lvState, chargePower, chargeNote,
            kicker, powerLine, chargeTitle, chargeEta, socTarget, chargeAdded, inletName, inletValue;
    final int inlet;
    final double socProgress;
    final SchematicReadings.Band lvBand;
    final boolean powerIn;
    // swipe card pages: range and energy, pack power, extremes ("--" / NaN / 0 when unknown)
    final String rangeKm, energyLeft, packKwText, powerState, highCellV;
    final double energyShare, packKw;
    final int highCell, hotSensor, coolSensor; // cell 1-118, sensor 1-12, 0 = unknown
    final boolean sohReading; // BECM DID 496D read in progress: the view shows a spinner in place of the value

    SchematicData(JSONObject data) {
        boolean live = data != null && Boolean.TRUE.equals(data.opt("streamConnected"));
        JSONObject flow = live ? data.optJSONObject("flow") : null;
        JSONObject current = live ? data : null;
        cells = samples(data,"cells","cell","millivolts",118,2000,5000);
        temperatures = samples(current,"cellTemps","sensor","tempC",12,-40,120);
        extremes = SchematicReadings.Cells.of(cells);
        cellBands = new SchematicReadings.Band[cells.length];
        for (int i = 0; i < cells.length; i++) {
            cellBands[i] = SchematicReadings.cellBand(cells[i],extremes.median());
        }
        sensorReadings = new String[temperatures.length];
        sensorBands = new SchematicReadings.Band[temperatures.length];
        for (int i = 0; i < temperatures.length; i++) {
            sensorReadings[i] = UiFormat.fixed(temperatures[i],0,"°");
            sensorBands[i] = SchematicReadings.sensorBand(temperatures[i]);
        }
        double volts = number(current,"battVoltageV",0,1000);
        double amps = number(current,"battCurrentA",-1000,1000);
        double watts = volts * amps;
        boolean into = amps < -.5, out = amps > .5;
        dcPlug = plugged(number(flow,"dcHandle",0,5));
        plugged = dcPlug || plugged(number(flow,"obcHandle",0,11));
        charging = plugged && into;
        hvOn = out || into && !plugged;
        Object dcdcOn = current == null ? null : current.opt("dcdcOn");
        dcOn = Boolean.TRUE.equals(dcdcOn);
        double lv = number(current,"lvBatteryV",6,20);
        double low = number(current,"dcdcLoA",0,300);
        double converter = number(current,"dcdcW",0,10000);
        double derived = lv * low;
        double conv = Double.isFinite(converter) ? converter : derived;
        double motor = number(current,"isgVoltageV",0,1000) * number(current,"isgCurrentA",-1000,1000);
        double cabin = number(current,"cabinThermW",0,40000);
        double thermal = number(current,"battThermW",0,40000);
        double other = out ? Math.max(0,watts - motor - cabin - thermal - conv) : Double.NaN;
        double[] loads = {motor,cabin,thermal,other};
        loadPower = new String[loads.length];
        shares = new double[loads.length];
        double sum = Arrays.stream(loads).map(SchematicReadings::knownLoad).sum()
                + SchematicReadings.knownLoad(conv);
        double denom = Math.max(out ? SchematicReadings.knownLoad(watts) : 0, sum);
        for (int i = 0; i < loads.length; i++) {
            loadPower[i] = UiFormat.power(loads[i]);
            shares[i] = denom > 0 ? SchematicReadings.knownLoad(loads[i]) / denom : 0;
        }
        double km = number(current,"rangeKm",0,1500);
        JSONObject health = data == null ? null : data.optJSONObject("soh");
        soh = SchematicReadings.soh(number(health,"pct",0,100),health == null ? null : health.optString("source",null));
        sohReading = health != null && "reading".equals(health.optString("state"));
        soc = UiFormat.fixed(number(current,"soc",0,100),1,"%");
        lvSoc = UiFormat.fixed(number(current,"socRaw",0,100),1," %");
        packPower = SchematicReadings.packPower(watts);
        powerIn = watts < 0;
        range = Double.isFinite(km) ? "≈ " + UiFormat.fixed(km,0," km") : "";
        packVA = UiFormat.fixed(volts,1," V") + " · " + UiFormat.fixed(amps,1," A");
        inlet = SchematicReadings.inlet(data);
        double v2lW = number(flow,"v2lV",0,300) * number(flow,"v2lA",0,100);
        loadsTitle = inlet == SchematicReadings.INLET_V2L ? "Power out \u00b7 V2L " + UiFormat.power(v2lW)
                : (into && !plugged ? "Regen" : out || charging ? "In use" : "Idle")
                + (hvOn && Double.isFinite(watts) ? " · " + UiFormat.power(Math.abs(watts)) : "");
        dcState = dcdcOn instanceof Boolean ? (dcOn ? "ON" : "OFF") : UiFormat.MISSING;
        lvState = dcdcOn instanceof Boolean ? (dcOn ? "charging" : "discharging") : UiFormat.MISSING;
        dcHigh = UiFormat.fixed(number(current,"dcdcHiA",0,50),1," A");
        dcLow = UiFormat.fixed(low,1," A") + " · "
                + (Double.isNaN(converter) && Double.isFinite(derived) ? "≈ " : "") + UiFormat.power(conv);
        lvVoltage = UiFormat.fixed(lv,1," V");
        lvBand = lv < 11.5 ? SchematicReadings.Band.DANGER : lv < 12 ? SchematicReadings.Band.WARN
                : dcOn && Double.isFinite(lv) ? SchematicReadings.Band.GOOD : SchematicReadings.Band.WAIT;
        double charger = dcPlug ? number(current,"dcChargeKw",0,400) * 1000
                : number(flow,"obcV",0,1000) * number(flow,"obcA",0,1000);
        chargePower = UiFormat.power(Double.isFinite(charger) ? charger : charging ? -watts : Double.NaN);
        double socNow = number(current,"soc",0,100), target = number(flow,"targetSoc",1,100);
        double eta = charging ? number(flow,"chargeEtaMin",0,1440) : Double.NaN;
        double chargerW = Double.isFinite(charger) ? charger : charging ? -watts : Double.NaN;
        kicker = "HV PACK \u00b7 400 V" + (range.isEmpty() ? "" : " \u00b7 " + range);
        powerLine = SchematicReadings.powerLine(volts,amps);
        chargeTitle = "Charging \u00b7 " + (dcPlug ? "DC" : "AC") + (Double.isFinite(chargerW) ? " " + UiFormat.power(chargerW) : "");
        chargeEta = Double.isNaN(eta) ? "" : (Double.isNaN(target) || target >= 100 ? "Full in " : "To " + UiFormat.fixed(target,0," %") + " in ")
                + UiFormat.duration(eta);
        socTarget = UiFormat.fixed(socNow,0," %") + (Double.isNaN(target) ? "" : " \u2192 " + UiFormat.fixed(target,0," %"));
        socProgress = Double.isFinite(socNow) ? socNow / (Double.isFinite(target) ? target : 100) : 0;
        chargeAdded = UiFormat.fixed(number(flow,"chargeKwh",0,200),1," kWh");
        inletName = inlet == SchematicReadings.INLET_DC ? "CCS2" : "AC Type 2";
        inletValue = inlet == SchematicReadings.INLET_V2L ? UiFormat.power(v2lW) + " out" : UiFormat.power(chargerW);
        rangeKm = UiFormat.fixed(km,0,"");
        double energy = number(current,"energyWh",0,200000);
        energyLeft = UiFormat.fixed(energy / 1000,1," kWh");
        energyShare = Double.isFinite(energy) && Double.isFinite(socNow) ? socNow / 100 : Double.NaN;
        packKw = watts / 1000;
        packKwText = Double.isNaN(packKw) ? UiFormat.MISSING
                : (packKw >= .05 ? "+" : packKw <= -.05 ? "\u2212" : "") + String.format(java.util.Locale.US,"%.1f",Math.abs(packKw));
        powerState = Double.isNaN(watts) ? "" : charging ? "Charging" : into && !plugged ? "Regen" : out ? "In use" : "Idle";
        highCell = extreme(cells,true) + 1;
        highCellV = highCell == 0 ? UiFormat.MISSING : UiFormat.fixed(cells[highCell-1] / 1000,3," V");
        hotSensor = extreme(temperatures,true) + 1;
        coolSensor = extreme(temperatures,false) + 1;
        chargeNote = SchematicReadings.chargeNote(number(flow,"chargeKwh",0,200),
                charging ? number(flow,"chargeEtaMin",0,1440) : Double.NaN);
    }

    /** Index of the first highest (or lowest) finite reading, -1 when there is none. */
    private static int extreme(double[] readings, boolean highest) {
        int at = -1;
        for (int i = 0; i < readings.length; i++) {
            if (!Double.isFinite(readings[i])) continue;
            if (at < 0 || (highest ? readings[i] > readings[at] : readings[i] < readings[at])) at = i;
        }
        return at;
    }

    private static boolean plugged(double handle) { return handle >= 1 && handle <= 3; }

    private static double number(JSONObject object, String key, double min, double max) {
        if (object != null && object.opt(key) instanceof Number reading) {
            double value = reading.doubleValue();
            if (value >= min && value <= max) return value;
        }
        return Double.NaN;
    }

    private static double[] samples(JSONObject data, String key, String id, String field,
                                    int count, int min, int max) {
        double[] readings = new double[count];
        Arrays.fill(readings,Double.NaN);
        JSONArray samples = data == null ? null : data.optJSONArray(key);
        if (samples == null) return readings;
        for (int i = 0; i < samples.length(); i++) {
            JSONObject sample = samples.optJSONObject(i);
            double index = number(sample,id,1,count), value = number(sample,field,min,max);
            if (index == Math.rint(index) && value == Math.rint(value)) readings[(int) index - 1] = value;
        }
        return readings;
    }
}

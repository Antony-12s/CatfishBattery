package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BatteryLoggerTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void totalsSplitPackEnergyByDirectionAndPlug() {
        // 36 kW for 10 s is 100 Wh; the gaps between the three pairs are too long to bridge.
        BatteryLogger.Totals t = new BatteryLogger.Totals(0L);
        t.add(0L, 36d, false);
        t.add(10000L, 36d, false);
        t.add(100000L, -36d, true);
        t.add(110000L, -36d, true);
        t.add(200000L, -36d, false);
        t.add(210000L, -36d, false);
        assertEquals(100d, t.usedWh, 1e-9);
        assertEquals(100d, t.chargedWh, 1e-9);
        assertEquals(100d, t.regenWh, 1e-9);
    }

    @Test
    public void totalsDoNotBridgeAGapOrAMissingSample() {
        BatteryLogger.Totals t = new BatteryLogger.Totals(0L);
        t.add(0L, 36d, false);
        assertFalse(t.add(70000L, 36d, false));
        assertTrue(t.add(80000L, 36d, false));
        t.add(90000L, null, false);
        assertFalse(t.add(100000L, 36d, false));
        assertEquals(100d, t.usedWh, 1e-9);
    }

    @Test
    public void totalsSurviveANewLoggerAndReset() {
        File dir = tmp.getRoot();
        BatteryLogger first = new BatteryLogger(dir, null);
        String since = first.totalsJson().replaceAll(".*\"since\":(\\d+).*", "$1");
        first.addPower(0L, 36d, false);
        first.addPower(10000L, 36d, false);

        BatteryLogger second = new BatteryLogger(dir, null);
        assertEquals("{\"since\":" + since + ",\"usedWh\":100.000,\"chargedWh\":0.000,\"regenWh\":0.000}", second.totalsJson());

        second.resetTotals(5L);
        assertEquals("{\"since\":5,\"usedWh\":0.000,\"chargedWh\":0.000,\"regenWh\":0.000}", new BatteryLogger(dir, null).totalsJson());
    }

    @Test
    public void autoResetRunsOnceAfterMissedDueDaysAndNotOnSave() {
        File dir = tmp.getRoot();
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        java.util.Calendar c = java.util.Calendar.getInstance(tz);
        c.clear();
        c.set(2026, 0, 10, 9, 0, 0);
        long jan10 = c.getTimeInMillis();
        BatteryLogger logger = new BatteryLogger(dir, null);
        logger.resetTotals(jan10);
        logger.addPower(jan10 + 1000L, 36d, false);
        logger.addPower(jan10 + 11000L, 36d, false);
        // saving the schedule on Jan 10 must not wipe the month already counted
        logger.saveSettings("{\"resetMode\":\"monthly\",\"resetDay\":1}", jan10 + 12000L);
        assertTrue(logger.totalsJson().contains("\"usedWh\":100.000"));

        // the head unit slept through Feb 1 and Mar 1: one reset, dated the last due day
        c.set(2026, 2, 15, 9, 0, 0);
        logger.autoReset(c.getTimeInMillis());
        c.set(2026, 2, 1, 0, 0, 0);
        assertEquals("{\"since\":" + c.getTimeInMillis() + ",\"usedWh\":0.000,\"chargedWh\":0.000,\"regenWh\":0.000}", logger.totalsJson());
    }

    @Test
    public void appendStartsItsOwnLineAfterATornRow() throws Exception {
        File log = new File(tmp.getRoot(), "log.csv");
        Files.write(log.toPath(), "ts,soc\n1700000000000,5".getBytes(StandardCharsets.UTF_8));
        BatteryLogger.append(log, "ts,soc", "1700000010000,51.000", 1L << 20);
        assertEquals("ts,soc\n1700000000000,5\n1700000010000,51.000\n",
                new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void historySkipsARowGluedOntoATornOne() throws Exception {
        // Seen on the car: half a row, then the next row glued on, so the first field reads as a
        // date far in the future. It stretched the chart until every line was off screen.
        File dir = tmp.getRoot();
        String blank = ",,,,,,,,,";
        Files.write(new File(dir, "battery-log.csv").toPath(), (BatteryLogger.LOG_HEADER + "\n"
                + "1700000000000,40.000" + blank + "\n"
                + "17000000051700000005000,65.000" + blank + "\n"
                + "1700000010000,41.000" + blank + "\n").getBytes(StandardCharsets.UTF_8));
        String json = new BatteryLogger(dir, null).historyJson(0L, 600);
        assertTrue(json, json.contains("\"samples\":2,\"socMin\":40.000,\"socMax\":41.000"));
    }

    @Test
    public void historyStatsSplitUsedChargedAndRegen() {
        // 36 kW for 10 s is 0.1 kWh; energy in counts as regen while the motor current is negative
        String json = BatteryLogger.stats(Arrays.asList(row(0L, 36d, 0d), row(10000L, 36d, 0d),
                row(100000L, -36d, 0d), row(110000L, -36d, 0d), row(200000L, -36d, -50d), row(210000L, -36d, -50d)));
        assertTrue(json, json.contains("\"kwhIn\":0.100,\"kwhOut\":0.100,\"kwhRegen\":0.100"));
    }

    /** A parsed log row: ts at 0, pack kW at 7, motor current in slot 11. */
    private static double[] row(long ts, double kw, double isgA) {
        double[] r = new double[12];
        Arrays.fill(r, Double.NaN);
        r[0] = ts;
        r[7] = kw;
        r[11] = isgA;
        return r;
    }

    @Test
    public void cellSpreadComesFromTheCellsWhenTheCarSendsNoMaxMin() {
        List<VehicleBatteryReader.CellSample> cells = Arrays.asList(new VehicleBatteryReader.CellSample(1, 0, 0, 3280, 0),
                new VehicleBatteryReader.CellSample(2, 0, 0, 3285, 0), new VehicleBatteryReader.CellSample(3, 0, 0, 0, 0));
        assertEquals(Long.valueOf(3285), BatteryLogger.cellMv(cells, null, true));
        assertEquals(Long.valueOf(3280), BatteryLogger.cellMv(cells, null, false));
        assertEquals(Long.valueOf(3286), BatteryLogger.cellMv(Collections.emptyList(), 3.286, true));
        assertNull(BatteryLogger.cellMv(Collections.emptyList(), null, true));
    }

    @Test
    public void regenDoesNotOpenAChargeSession() {
        assertTrue(BatteryLogger.chargingNow(-17d, 0d));
        assertTrue(BatteryLogger.chargingNow(-17d, null));
        assertFalse(BatteryLogger.chargingNow(-17d, -40d));
        assertFalse(BatteryLogger.chargingNow(2.9d, 0d));
        assertFalse(BatteryLogger.chargingNow(null, 0d));
    }

    @Test
    public void plugFollowsTheHandlesAndTheDcChargePower() {
        assertEquals("ac", BatteryLogger.plugType(0d, "{\"relay\":1.000,\"obcHandle\":1.000,\"dcHandle\":null}"));
        assertEquals("dc", BatteryLogger.plugType(null, "{\"obcHandle\":null,\"dcHandle\":2.000}"));
        // the one logged DC charge had its power on the stream; a DC plug wins over an AC handle
        assertEquals("dc", BatteryLogger.plugType(73.25d, "{}"));
        assertEquals("dc", BatteryLogger.plugType(73.25d, "{\"obcHandle\":1.000}"));
        // the V2L adapter is not a charger
        assertNull(BatteryLogger.plugType(0d, "{\"obcHandle\":5.000,\"dcHandle\":0.000}"));
        assertNull(BatteryLogger.plugType(null, "{}"));
        assertNull(BatteryLogger.plugType(null, null));
    }

    private static final String UNPLUGGED = "{\"obcHandle\":0.000,\"dcHandle\":0.000}";
    private static final String DC = "{\"obcHandle\":0.000,\"dcHandle\":2.000}";
    private static final String AC = "{\"obcHandle\":1.000,\"dcHandle\":0.000}";

    /** A live snapshot: SOC, the car's pack energy (Wh), pack current (below 0 into the pack) and motor current. */
    private static VehicleBatteryReader.Snapshot snap(double soc, double wh, double amps, double isgA, Double dcKw, String flow) {
        return new VehicleBatteryReader.Snapshot(true, soc, "test", null, 0, "test", wh, 30d, 390d, amps, 390d * amps / 1000d,
                null, null, Collections.<VehicleBatteryReader.CellSample>emptyList(), false, 0L, 0L, 0, 0, null, null, null,
                13.5d, null, Collections.<VehicleBatteryReader.CellTemp>emptyList(), null, null, null, null, dcKw, null, null,
                null, isgA, 390d, null, null, null, flow);
    }

    @Test
    public void aChargeIsAChargerPluggedInWithEnergyGoingIn() {
        BatteryLogger log = new BatteryLogger(tmp.getRoot(), null);
        long t = 1000000L;
        // braking: current into the pack with the motor generating and no plug
        for (int i = 0; i < 4; i++) log.sample(snap(50d, 27800d + i * 10d, -40d, -45d, 0d, UNPLUGGED), t += 10000L);
        assertNull(log.liveSession());
        for (int i = 0; i < 4; i++) log.sample(snap(50d + i, 27800d + i * 550d, -150d, 0d, 60d, DC), t += 10000L);
        assertNotNull(log.liveSession());
        // the charge is done but the plug is still in: not charging any more, and not over yet
        for (int i = 0; i < 8; i++) log.sample(snap(53d, 29450d, 0d, 0d, 0d, DC), t += 10000L);
        assertNull(log.liveSession());
        assertTrue(log.sessionsJson(), log.sessionsJson().startsWith("{\"sessions\":[],"));
        // unplugged: over once nothing has gone in for a minute
        for (int i = 0; i < 6; i++) log.sample(snap(53d, 29450d, 2d, 0d, 0d, UNPLUGGED), t += 10000L);
        String json = log.sessionsJson();
        assertTrue(json, json.contains("{\"start\":1050000,\"end\":1080000,\"socStart\":50.000,\"socEnd\":53.000"));
        assertTrue(json, json.contains("\"kwhAdded\":1.650,\"type\":\"dc\",\"parked\":false,\"charger\":null}"));
    }

    @Test
    public void aChargeWhileTheHeadUnitSleptShowsWhenItWakes() {
        // 26-27 Sep on the car: 40 % at 16:13, asleep, 96 % at 10:06
        BatteryLogger log = new BatteryLogger(tmp.getRoot(), null);
        log.sample(snap(40d, 22600d, 2d, 0d, 0d, UNPLUGGED), 1000000L);
        for (int i = 0; i < 6; i++) log.sample(snap(96d, 52980d, 2d, 0d, 0d, UNPLUGGED), 65000000L + i * 10000L);
        String json = log.sessionsJson();
        assertTrue(json, json.contains("{\"start\":1000000,\"end\":65000000,\"socStart\":40.000,\"socEnd\":96.000"));
        assertTrue(json, json.contains("\"kwhAdded\":30.380,\"type\":null,\"parked\":true,\"charger\":null}"));
    }

    @Test
    public void aDriveOrADriftWhileAsleepIsNoCharge() {
        BatteryLogger log = new BatteryLogger(tmp.getRoot(), null);
        // driven with nothing logging, then a SOC point back while parked (below 1 kWh)
        log.sample(snap(65d, 36160d, 2d, 0d, 0d, UNPLUGGED), 1000000L);
        log.sample(snap(37d, 20860d, 2d, 0d, 0d, UNPLUGGED), 68000000L);
        log.sample(snap(38d, 21600d, 2d, 0d, 0d, UNPLUGGED), 90000000L);
        for (int i = 1; i <= 6; i++) log.sample(snap(38d, 21600d, 2d, 0d, 0d, UNPLUGGED), 90000000L + i * 10000L);
        assertTrue(log.sessionsJson(), log.sessionsJson().startsWith("{\"sessions\":[],\"live\":null}"));
    }

    @Test
    public void aRestartedAppStillSeesWhatCameInWhileItWasOff() {
        File dir = tmp.getRoot();
        new BatteryLogger(dir, null).sample(snap(29d, 16240d, 2d, 0d, 0d, UNPLUGGED), 1000000L);
        // a new process after the head unit powered down; it wakes with the AC plug still in, the charge done
        BatteryLogger after = new BatteryLogger(dir, null);
        after.sample(snap(78d, 43420d, 0d, 0d, 0d, AC), 48000000L);
        assertNull(after.liveSession());
        for (int i = 1; i <= 6; i++) after.sample(snap(78d, 43420d, 2d, 0d, 0d, UNPLUGGED), 48000000L + i * 10000L);
        String json = after.sessionsJson();
        assertTrue(json, json.contains("{\"start\":1000000,\"end\":48000000,\"socStart\":29.000,\"socEnd\":78.000"));
        assertTrue(json, json.contains("\"kwhAdded\":27.180,\"type\":\"ac\",\"parked\":true,\"charger\":null}"));
    }

    @Test
    public void anAppUpdateMidChargeCarriesOnTheSameSession() {
        // 29 Sep on the car: an install at 20:51 killed the process 78 s into a DC charge
        File dir = tmp.getRoot();
        BatteryLogger first = new BatteryLogger(dir, null);
        long t = 1000000L;
        for (int i = 0; i < 4; i++) first.sample(snap(23d + i, 12920d + i * 560d, -220d, 0d, 86d, DC), t += 10000L);
        BatteryLogger after = new BatteryLogger(dir, null);
        t += 78000L;
        for (int i = 0; i < 3; i++) after.sample(snap(40d + i, 22000d + i * 560d, -220d, 0d, 86d, DC), t += 10000L);
        assertNotNull(after.liveSession());
        for (int i = 0; i < 6; i++) after.sample(snap(42d, 23120d, 2d, 0d, 0d, UNPLUGGED), t += 10000L);
        String json = after.sessionsJson();
        assertTrue(json, json.contains("{\"start\":1010000,\"end\":1148000,\"socStart\":23.000,\"socEnd\":42.000"));
        assertTrue(json, json.contains("\"kwhAdded\":10.200,\"type\":\"dc\",\"parked\":false,\"charger\":null}"));
        assertFalse(new File(dir, "charge-open.csv").exists());
    }

    @Test
    public void settingsAreSavedCleanAndSurviveANewLogger() {
        File dir = tmp.getRoot();
        assertTrue(new BatteryLogger(dir, null).settingsJson().startsWith("{\"saved\":false,"));
        new BatteryLogger(dir, null).saveSettings("{\"currency\":\"EUR\",\"alertSoc\":85,\"junk\":true}");
        String json = new BatteryLogger(dir, null).settingsJson();
        assertTrue(json, json.startsWith("{\"saved\":true,"));
        assertTrue(json, json.contains("\"currency\":\"EUR\"") && json.contains("\"alertSoc\":85") && !json.contains("junk"));
    }

    @Test
    public void aPickedChargerSticksToItsChargeUntilPutBack() throws Exception {
        File dir = tmp.getRoot();
        Files.write(new File(dir, "charge-sessions.csv").toPath(), (BatteryLogger.SESSION_HEADER + "\n"
                + "5000,6000,40.000,80.000,22.000,80.000,30.000,60,22.000,dc,0\n"
                + "7000,8000,50.000,60.000,5.500,7.000,30.000,60,5.500,ac,0\n").getBytes(StandardCharsets.UTF_8));
        BatteryLogger log = new BatteryLogger(dir, null);
        log.setCharger(5000L, "c1");
        log.setCharger(7000L, "home");
        log.setCharger(7000L, "");
        String json = log.sessionsJson();
        assertTrue(json, json.contains("\"start\":5000,") && json.contains("\"parked\":false,\"charger\":\"c1\"}"));
        assertTrue(json, json.contains("\"type\":\"ac\",\"parked\":false,\"charger\":null}"));
        try {
            log.setCharger(5000L, "c1\",\"x");
            assertTrue("a charger id outside [a-z0-9] must be refused", false);
        } catch (IllegalArgumentException expected) { }
    }

    @Test
    public void theChargeAlertGoesOffOncePerChargeThatStartedBelowIt() {
        assertTrue(BatteryLogger.alertDue(40d, 80d, 80, false));
        assertFalse(BatteryLogger.alertDue(40d, 79.9d, 80, false));
        assertFalse(BatteryLogger.alertDue(40d, 85d, 80, true));
        // a top-up that started above the mark, and an alert switched off
        assertFalse(BatteryLogger.alertDue(85d, 90d, 80, false));
        assertFalse(BatteryLogger.alertDue(40d, 90d, 0, false));

        BatteryLogger log = new BatteryLogger(tmp.getRoot(), null);
        log.saveSettings("{\"alertSoc\":80}");
        java.util.List<Integer> said = new java.util.ArrayList<>();
        log.onAlert = said::add;
        long t = 1000000L;
        for (int i = 0; i < 6; i++) log.sample(snap(77d + i, 43000d + i * 550d, -150d, 0d, 60d, DC), t += 10000L);
        assertEquals(Collections.singletonList(80), said);
    }

    @Test
    public void theListDropsTheRegenStretchesOfTheOldRule() throws Exception {
        File dir = tmp.getRoot();
        Files.write(new File(dir, "charge-sessions.csv").toPath(), ("start_ts,end_ts,soc_start,soc_end,kwh_in,peak_kw,max_temp_c,samples\n"
                + "1000,2000,63.000,63.000,0.033,10.980,33.500,3\n"
                + "3000,4000,87.800,99.800,7.310,30.853,36.000,132\n"
                + "5000,6000,40.000,41.000,0.400,7.000,30.000,6,0.550,ac,0\n").getBytes(StandardCharsets.UTF_8));
        String json = new BatteryLogger(dir, null).sessionsJson();
        assertFalse(json, json.contains("\"start\":1000,"));
        assertTrue(json, json.contains("\"start\":3000,") && json.contains("\"kwhAdded\":null,\"type\":null,\"parked\":false"));
        assertTrue(json, json.contains("\"start\":5000,") && json.contains("\"kwhAdded\":0.550,\"type\":\"ac\""));
    }

    @Test
    public void lastRowSkipsATornTailAndARowFromTheFuture() throws Exception {
        File log = new File(tmp.getRoot(), "log.csv");
        Files.write(log.toPath(), ("ts,soc,range_km,energy_wh\n1000,40.000,200.000,22600.000\n"
                + "17000000051700000005000,65.000,,36000.000\n2000,4").getBytes(StandardCharsets.UTF_8));
        double[] last = BatteryLogger.lastRow(log, 1700000000000L);
        assertEquals(1000d, last[0], 0d);
        assertEquals(40d, last[1], 0d);
        assertEquals(22600d, last[2], 0d);
        assertNull(BatteryLogger.lastRow(new File(tmp.getRoot(), "none.csv"), 0L));
    }
}

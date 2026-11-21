package com.catfish.battery;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Ids differ per firmware: the subscription must carry the car's ids, resolved by name from its signal_config.json. */
public class ApvpSignalConfigTest {
    // Build 244520 ids from the owner's read-only comparison (2026-11-20): no PA_HvBattCellTinfo_Res, plain pair instead.
    // IDcDcActLoSide's spelling/id there is unconfirmed; the entry only exercises the single-name fallback.
    private static final String CONFIG_244520 = "[" +
            entry("HvBattTMax", 308282795) + "," + entry("HvBattTMin", 308282796) + "," + entry("HvBattTAvg", 308282794) + "," +
            entry("PA_HvBattCellUIInfo_Res", 309332729) + "," + entry("HvBattUDc", 308282761) + "," +
            entry("HvBattIDc1", 308282759) + "," + entry("HvBattCap", 308282756) + "," + entry("VehBattUSysU", 308282105) + "," +
            entry("BattSocRaw", 308282157) + "," + entry("IDcDcActLoSide", 308282900) + "," +
            entry("HvBattCellTInfoHvBattTSnsrNr", 306185635) + "," + entry("HvBattCellTInfoHvBattSnsrT", 306185640) + "]";

    private static String entry(String name, int id) {
        return "{\"id\": {\n \"name\": \"" + name + "\",\n \"value\": " + id + "\n},\"module\":\"BECM\",\"readOnly\":true,\"valueType\":6291456}";
    }

    @Test public void parsesTheTrimmedCarFixture() throws Exception {
        Map<String, Integer> table = ApvpSignalConfig.parse(resource("/apvp/signal_config_264133_trimmed.json"));
        assertEquals(44, table.size());
        assertEquals(Integer.valueOf(308282804), table.get("HvBattUDc"));
        assertEquals(Integer.valueOf(309332908), table.get("PA_HvBattCellTinfo_Res"));
    }

    @Test public void subscribesWithTheCarsIdsNotTheBuiltInOnes() {
        ApvpBatteryStream.Plan plan = ApvpBatteryStream.plan(ApvpSignalConfig.parse(CONFIG_244520));
        assertEquals(308282795, idFor(plan, "HvBattTMax"));
        assertEquals(308282761, idFor(plan, "HvBattUDc"));
        assertEquals(309332729, idFor(plan, "PA_HvBattCellUIInfo_Res"));
        assertEquals(308282157, idFor(plan, "BattSocRaw"));
        assertTrue(plan.missing.contains("PA_HvBattCellTinfo_Res"));
        assertTrue(plan.missing.contains("HvBattSoc")); // not in this cut: left out, not guessed
        String request = new String(ApvpBatteryStream.subscription(plan), StandardCharsets.ISO_8859_1);
        assertFalse(request.contains("HvBattSoc"));
        assertTrue(request.contains("HvBattCellTInfoHvBattSnsrT"));
    }

    @Test public void doubledNamesResolveEitherWay() {
        Map<String, Integer> table = ApvpSignalConfig.parse(CONFIG_244520);
        assertEquals("IDcDcActLoSide", ApvpSignalConfig.carName(table, "IDcDcActLoSideIDcDcActLoSide"));
        Map<String, Integer> doubled = new HashMap<>();
        doubled.put("HvSysRlyStsHvSysRlySts", 1);
        assertEquals("HvSysRlyStsHvSysRlySts", ApvpSignalConfig.carName(doubled, "HvSysRlySts"));
        assertNull(ApvpSignalConfig.carName(doubled, "HvBattUDc"));
    }

    @Test public void theStructFirmwareDoesNotSubscribeThePlainPair() throws Exception {
        ApvpBatteryStream.Plan plan = ApvpBatteryStream.plan(ApvpSignalConfig.parse(resource("/apvp/signal_config_264133_trimmed.json")));
        String request = new String(ApvpBatteryStream.subscription(plan), StandardCharsets.ISO_8859_1);
        assertTrue(request.contains("PA_HvBattCellTinfo_Res"));
        assertFalse(request.contains("HvBattCellTInfoHvBattSnsrT"));
        assertEquals(308282804, idFor(plan, "HvBattUDc"));
    }

    @Test public void builtInPlanIsTheOldSubscription() {
        String request = new String(ApvpBatteryStream.subscription(), StandardCharsets.ISO_8859_1);
        assertTrue(request.contains("PA_HvBattCellTinfo_Res"));
        assertFalse(request.contains("HvBattCellTInfoHvBattSnsrT"));
    }

    @Test public void repliesUnderTheCarsIdAreReportedUnderTheAppId() {
        ApvpBatteryStream.Plan plan = ApvpBatteryStream.plan(ApvpSignalConfig.parse(CONFIG_244520));
        List<double[]> got = new ArrayList<>();
        // nameless reply: only the car's id identifies it
        ApvpBatteryStream.decodeSignal(signal(308282795, "", 31f), plan, listener(got, null), null);
        assertEquals(1, got.size());
        assertEquals(308282837, (int) got.get(0)[0]); // HV_TEMP_MAX_C key in VehicleBatteryReader
        assertEquals(31.0, got.get(0)[1], 0);
    }

    @Test public void plainCellTempUsesTheLastTemperatureWhenOnlyTheSensorNumberChanges() {
        ApvpBatteryStream.Plan plan = ApvpBatteryStream.plan(ApvpSignalConfig.parse(CONFIG_244520));
        ApvpBatteryStream.TempMux mux = new ApvpBatteryStream.TempMux();
        List<double[]> scalars = new ArrayList<>();
        List<int[]> temps = new ArrayList<>();
        // as on the car: initial values together, then the sensor number alone while the temperature is unchanged
        ApvpBatteryStream.decodeUpdate(update(signal(306185635, "HvBattCellTInfoHvBattTSnsrNr", 7f),
                signal(306185640, "HvBattCellTInfoHvBattSnsrT", 20f)), plan, mux, listener(scalars, temps));
        for (int n = 8; n <= 12; n++)
            ApvpBatteryStream.decodeUpdate(update(signal(306185635, "HvBattCellTInfoHvBattTSnsrNr", n)), plan, mux, listener(scalars, temps));
        ApvpBatteryStream.decodeUpdate(update(signal(306185635, "HvBattCellTInfoHvBattTSnsrNr", 1f),
                signal(306185640, "HvBattCellTInfoHvBattSnsrT", 24f)), plan, mux, listener(scalars, temps));
        assertEquals(7, temps.size());
        assertArrayEquals(new int[]{7, 20}, temps.get(0));
        assertArrayEquals(new int[]{12, 20}, temps.get(5));
        assertArrayEquals(new int[]{1, 24}, temps.get(6));
        assertTrue(scalars.isEmpty());
        assertNull(ApvpBatteryStream.cellTempPair(new double[]{13, 20}));
        assertNull(ApvpBatteryStream.cellTempPair(new double[]{2.5, 20}));
    }

    @Test public void nothingIsEmittedBeforeATemperatureIsKnown() {
        ApvpBatteryStream.Plan plan = ApvpBatteryStream.plan(ApvpSignalConfig.parse(CONFIG_244520));
        ApvpBatteryStream.TempMux mux = new ApvpBatteryStream.TempMux();
        List<int[]> temps = new ArrayList<>();
        ApvpBatteryStream.decodeUpdate(update(signal(306185635, "HvBattCellTInfoHvBattTSnsrNr", 3f)), plan, mux, listener(new ArrayList<>(), temps));
        assertTrue(temps.isEmpty());
        ApvpBatteryStream.decodeUpdate(update(signal(308282795, "HvBattTMax", 21f)), plan, mux, listener(new ArrayList<>(), temps));
        assertTrue(temps.isEmpty()); // unrelated signals do not re-emit the current sensor
    }

    @Test public void loadFindsTheTableDirectlyOrInAPlatformDir() throws Exception {
        File root = Files.createTempDirectory("libapvp").toFile();
        assertNull(ApvpSignalConfig.load(root));
        File platform = new File(root, "p145_j1");
        assertTrue(platform.mkdir());
        Files.write(new File(platform, "signal_config.json").toPath(), CONFIG_244520.getBytes(StandardCharsets.UTF_8));
        assertEquals(Integer.valueOf(308282761), ApvpSignalConfig.load(root).get("HvBattUDc"));
        Files.write(new File(root, "signal_config.json").toPath(), ("[" + entry("HvBattUDc", 5) + "]").getBytes(StandardCharsets.UTF_8));
        assertEquals(Integer.valueOf(5), ApvpSignalConfig.load(root).get("HvBattUDc"));
    }

    @Test public void builtInIsStableAcrossCalls() { assertSame(ApvpBatteryStream.BUILT_IN, ApvpBatteryStream.BUILT_IN); }

    private static int idFor(ApvpBatteryStream.Plan plan, String name) {
        for (int i = 0; i < plan.names.length; i++) if (name.equals(plan.names[i])) return plan.ids[i];
        throw new AssertionError(name + " not subscribed");
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = ApvpSignalConfigTest.class.getResourceAsStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) != -1;) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static byte[] signal(int id, String name, float value) {
        ByteArrayOutputStream ident = new ByteArrayOutputStream();
        varint(ident, 8); varint(ident, id);
        if (!name.isEmpty()) field(ident, 2, name.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, 1, ident.toByteArray());
        field(out, 5, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array());
        return out.toByteArray();
    }

    private static byte[] update(byte[]... signals) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] s : signals) field(body, 3, s);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, 1, body.toByteArray());
        return out.toByteArray();
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        do { int b = (int) (value & 127); value >>>= 7; out.write(b | (value != 0 ? 128 : 0)); } while (value != 0);
    }

    private static void field(ByteArrayOutputStream out, int tag, byte[] value) {
        varint(out, tag * 8 + 2); varint(out, value.length); out.write(value, 0, value.length);
    }

    private static ApvpBatteryStream.Listener listener(List<double[]> scalars, List<int[]> temps) {
        return new ApvpBatteryStream.Listener() {
            @Override public void scalar(int id, double value) { scalars.add(new double[]{id, value}); }
            @Override public void cells(int[] payload) { }
            @Override public void cellTemps(int[] payload) { }
            @Override public void cellTemp(int sensor, int tempC) { if (temps != null) temps.add(new int[]{sensor, tempC}); }
            @Override public void connected(boolean connected) { }
        };
    }
}

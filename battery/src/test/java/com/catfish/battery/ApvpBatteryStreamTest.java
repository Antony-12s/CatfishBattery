package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ApvpBatteryStreamTest {
    private static final int HV_BATT_SOC = 0x126005BA; // the id VehicleBatteryReader reads as the APVP SOC

    @Test public void subscribesToPackSocSoItArrivesWithTheInitialValues() {
        String request = new String(ApvpBatteryStream.subscription(), StandardCharsets.ISO_8859_1);
        assertTrue(request.contains("HvBattSoc"));
    }

    @Test public void packSocReplyWithShiftedIdMapsToTheReaderId() {
        List<double[]> got = new ArrayList<>();
        ApvpBatteryStream.decodeSignal(signal(308282811, "HvBattSoc", 84f), listener(got));
        assertEquals(1, got.size());
        assertEquals(HV_BATT_SOC, (int) got.get(0)[0]);
        assertEquals(84.0, got.get(0)[1], 0);
    }

    @Test public void subscribesToTheBmsCellExtremes() {
        String request = new String(ApvpBatteryStream.subscription(), StandardCharsets.ISO_8859_1);
        assertTrue(request.contains("CellUMax"));
        assertTrue(request.contains("CellUMin"));
        assertTrue(request.contains("HvBattVoltMaxSerlNr"));
        List<double[]> got = new ArrayList<>();
        ApvpBatteryStream.decodeSignal(signal(308282815, "CellUMax", 3.324f), listener(got)); // shifted reply id
        assertEquals(ApvpBatteryStream.CELL_U_MAX, (int) got.get(0)[0]);
        assertEquals(3.324, got.get(0)[1], 1e-6);
    }

    private static byte[] signal(int id, String name, float value) {
        ByteArrayOutputStream ident = new ByteArrayOutputStream();
        varint(ident, 8); varint(ident, id); field(ident, 2, name.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, 1, ident.toByteArray());
        field(out, 5, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array());
        return out.toByteArray();
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        do { int b = (int) (value & 127); value >>>= 7; out.write(b | (value != 0 ? 128 : 0)); } while (value != 0);
    }

    private static void field(ByteArrayOutputStream out, int tag, byte[] value) {
        varint(out, tag * 8 + 2); varint(out, value.length); out.write(value, 0, value.length);
    }

    private static ApvpBatteryStream.Listener listener(List<double[]> got) {
        return new ApvpBatteryStream.Listener() {
            @Override public void scalar(int id, double value) { got.add(new double[]{id, value}); }
            @Override public void cells(int[] payload) { }
            @Override public void cellTemps(int[] payload) { }
            @Override public void cellTemp(int sensor, int tempC) { }
            @Override public void connected(boolean connected) { }
        };
    }
}

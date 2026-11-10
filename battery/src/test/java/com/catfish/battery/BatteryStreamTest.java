package com.catfish.battery;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Wire-format decoders behind the web console battery pages (ApvpBatteryStream, VehicleBatteryReader). */
public class BatteryStreamTest {
    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
    private static final class Capture implements ApvpBatteryStream.Listener {
        final Map<Integer, Double> scalars = new HashMap<>();
        int[] cells, temps;
        @Override public void scalar(int id, double value) { scalars.put(id, value); }
        @Override public void cells(int[] payload) { cells = payload; }
        @Override public void cellTemps(int[] payload) { temps = payload; }
        @Override public void connected(boolean connected) { }
    }

    @Test
    public void decodesFloatSignalByIdAndName() {
        Capture c = new Capture();
        // Signal{identity{id=308282803 name="HvBattUDc"} float(field 5)=391.5}
        ApvpBatteryStream.decodeSignal(hex("0a1108b38b80930112094876426174745544632d00c0c343"), c);
        assertEquals(391.5, c.scalars.get(308282803), 1e-6);
    }

    @Test
    public void decodesIntegerSignalsAsZigzag() {
        Capture c = new Capture();
        // bytes captured on the car while DC charging: DCChrgnHndlSts [06] = 3 ConnectedWithPower, HvCabinThermPwrCns [80 0a] = 640 W
        ApvpBatteryStream.decodeSignal(hex("0a1608ad8b809201120e4443436872676e486e646c5374731a0106"), c);
        ApvpBatteryStream.decodeSignal(hex("0a1a08e38380920112124876436162696e546865726d507772436e731a02800a"), c);
        assertEquals(3d, c.scalars.get(306185645), 0d);
        assertEquals(640d, c.scalars.get(306184675), 0d);
        assertEquals(Long.valueOf(-1), ApvpBatteryStream.zigzag(1L));
    }

    @Test
    public void matchesFirmware13ShiftedIdByName() {
        Capture c = new Capture();
        // Firmware 1.3 answers the HvBattUDc subscription as id 308282804; the value lands under the requested id.
        ApvpBatteryStream.decodeSignal(hex("0a1108b48b80930112094876426174745544632d00c0c343"), c);
        assertEquals(391.5, c.scalars.get(308282803), 1e-6);
        assertEquals(1, c.scalars.size());
    }

    @Test
    public void rejectsSignalWithWrongIdentity() {
        Capture c = new Capture();
        ApvpBatteryStream.decodeSignal(hex("0a0208012a0400c0c343"), c);
        assertTrue(c.scalars.isEmpty());
    }

    @Test
    public void rejectsTruncatedUpdate() {
        try { ApvpBatteryStream.decodeUpdate(new byte[]{10, 127, 0}, new Capture()); fail("truncation accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test
    public void decodesCellRecordsAndSkipsInvalidOnes() {
        // valid, cell, u2, u3, high, low: 3.700 V = 3700 mV << 3 = 0x73A0
        int[] raw = {1, 7, 0, 0, 0x73, 0xA0, 1, 119, 0, 0, 0x73, 0xA0, 0, 8, 0, 0, 0x73, 0xA0};
        List<VehicleBatteryReader.CellSample> cells = VehicleBatteryReader.decodeCellSamples(raw);
        assertEquals(1, cells.size());
        assertEquals(7, cells.get(0).cell);
        assertEquals(3700, cells.get(0).millivolts);
    }

    @Test
    public void decodesCellTemperatureWithOffset() {
        assertArrayEquals(new int[]{5, 31}, VehicleBatteryReader.decodeCellTemp(new int[]{1, 71, 5, 0, 0, 0}));
        assertNull(VehicleBatteryReader.decodeCellTemp(new int[]{0, 71, 5, 0, 0, 0}));
        assertNull(VehicleBatteryReader.decodeCellTemp(new int[]{1, 71, 13, 0, 0, 0}));
    }

    @Test
    public void parsesRangeProbeIncludingUnavailableStatus() {
        double[] ok = VehicleBatteryReader.parseRangeProbe("Property 11600308 status: 2 floatValues: [451.0]");
        assertEquals(2, (int) ok[0]);
        assertEquals(451.0, ok[1], 1e-6);
        assertTrue(Double.isNaN(VehicleBatteryReader.parseRangeProbe("garbage")[1]));
        assertTrue(Double.isNaN(VehicleBatteryReader.parseRangeProbe("status: 0 floatValues: [9999.0]")[1]));
    }
}

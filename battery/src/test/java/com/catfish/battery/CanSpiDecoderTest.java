package com.catfish.battery;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import org.junit.Test;

/**
 * The head unit's bigdata "canspi" MQTT snapshot format. All payloads here are synthetic;
 * expected signal values come from cantools decoding the car's gee.json DBC, not from this decoder.
 */
public class CanSpiDecoderTest {
    private static final long TIMESTAMP = 1794577000123L;

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    /** Builds a snapshot: u64 timestamp, u16 body length, then the given raw record bytes. */
    private static byte[] snapshot(byte[]... records) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] record : records) body.write(record, 0, record.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (TIMESTAMP >>> shift));
        out.write(body.size() >>> 8);
        out.write(body.size());
        out.write(body.toByteArray(), 0, body.size());
        return out.toByteArray();
    }

    /** One record: 0x55 marker, u16 header (bus << 12 | id), flags/length byte, data. */
    private static byte[] record(int bus, int id, int flagsAndLength, byte[] data) {
        byte[] r = new byte[4 + data.length];
        r[0] = 0x55;
        r[1] = (byte) ((bus << 4) | (id >>> 8));
        r[2] = (byte) id;
        r[3] = (byte) flagsAndLength;
        System.arraycopy(data, 0, r, 4, data.length);
        return r;
    }

    private static byte[] propulsion(int id, String data) {
        return record(CanSpiDecoder.BUS_PROPULSION, id, 0x88, hex(data));
    }

    @Test
    public void parsesTimestampBusIdAndData() {
        CanSpiDecoder.Snapshot s = CanSpiDecoder.parse(snapshot(propulsion(0x141, "0000061800000000")));
        assertEquals(TIMESTAMP, s.timestampMs);
        assertEquals(1, s.frames.size());
        assertEquals(2, s.frames.get(0).bus);
        assertEquals(0x141, s.frames.get(0).id);
        assertArrayEquals(hex("0000061800000000"), s.frames.get(0).data);
    }

    @Test
    public void canFdLengthZeroMeans64Bytes() {
        byte[] fd = new byte[64];
        fd[63] = 7;
        CanSpiDecoder.Snapshot s = CanSpiDecoder.parse(snapshot(
                record(1, 0x140, 0xC0, fd), propulsion(0x141, "0000061800000000")));
        assertEquals(64, s.frames.get(0).data.length);
        assertEquals(7, s.frames.get(0).data[63]);
        assertEquals(0x141, s.frames.get(1).id);
    }

    @Test
    public void classicLengthZeroIsAnEmptyRecord() {
        CanSpiDecoder.Snapshot s = CanSpiDecoder.parse(snapshot(
                record(3, 0x155, 0x00, new byte[0]), propulsion(0x141, "0000061800000000")));
        assertEquals(0, s.frames.get(0).data.length);
        assertEquals(0x141, s.frames.get(1).id);
    }

    @Test
    public void findsFrameByBusAndId() {
        CanSpiDecoder.Snapshot s = CanSpiDecoder.parse(snapshot(
                record(1, 0x141, 0x88, new byte[8]), propulsion(0x141, "0000061800000000")));
        assertArrayEquals(hex("0000061800000000"), s.find(CanSpiDecoder.BUS_PROPULSION, 0x141).data);
        assertNull(s.find(CanSpiDecoder.BUS_PROPULSION, 0x4CA));
    }

    @Test
    public void rejectsRecordWithoutMarker() {
        byte[] bad = propulsion(0x141, "0000061800000000");
        bad[0] = 0x54;
        assertRejected(snapshot(bad));
    }

    @Test
    public void rejectsTruncatedRecord() {
        byte[] full = snapshot(propulsion(0x141, "0000061800000000"));
        byte[] cut = new byte[full.length - 3];
        System.arraycopy(full, 0, cut, 0, cut.length);
        cut[9] -= 3; // body length matches the cut, the record's own length does not
        assertRejected(cut);
    }

    @Test
    public void rejectsBodyLongerThanPayload() {
        byte[] p = snapshot(propulsion(0x141, "0000061800000000"));
        p[9] += 1;
        assertRejected(p);
    }

    @Test
    public void rejectsPayloadShorterThanHeader() {
        assertRejected(new byte[9]);
    }

    private static void assertRejected(byte[] payload) {
        try {
            CanSpiDecoder.parse(payload);
            fail("malformed snapshot accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test
    public void decodesHealthSignalsLikeTheCarDbc() {
        CanSpiDecoder.Snapshot s = CanSpiDecoder.parse(snapshot(
                propulsion(0x141, "0000061800000000"),
                propulsion(0x315, "7e0c295e70000000"),
                propulsion(0x499, "0659000000000000"),
                propulsion(0x293, "00000000000000c1"),
                propulsion(0x143, "3eaf000000000000"),
                propulsion(0x15C, "0000b80000000000"),
                propulsion(0x301, "0800000000000000"),
                propulsion(0x286, "07f8000000000000"),
                propulsion(0x178, "0000000000bac000"),
                propulsion(0x145, "6790000000677800")));
        assertEquals(390.0, CanSpiDecoder.HV_BATT_U_DC.value(s), 1e-9);
        assertEquals(-12.5, CanSpiDecoder.HV_BATT_T_AVG.value(s), 1e-9);
        assertEquals(33.0, CanSpiDecoder.HV_BATT_T_MAX.value(s), 1e-9);
        assertEquals(-40.0, CanSpiDecoder.HV_BATT_T_MIN.value(s), 1e-9);
        assertEquals(162.5, CanSpiDecoder.HV_BATT_CAP.value(s), 1e-9);
        assertEquals(96.5, CanSpiDecoder.HV_BATT_EGY_CDN.value(s), 1e-9);
        assertEquals(-33.3, CanSpiDecoder.HV_BATT_I_DC.value(s), 1e-9);
        assertEquals(92.0, CanSpiDecoder.HV_BATT_SOCE.value(s), 1e-9);
        assertEquals(4.0, CanSpiDecoder.HV_BATT_PWR_CDN.value(s), 1e-9);
        assertEquals(40800.0, CanSpiDecoder.HV_BATT_EGY_AVL_DCHA.value(s), 1e-9);
        assertEquals(74.7, CanSpiDecoder.HV_BATT_SOC.value(s), 1e-9);
        assertEquals(3.314, CanSpiDecoder.CELL_U_MAX.value(s), 1e-9);
        assertEquals(3.311, CanSpiDecoder.CELL_U_MIN.value(s), 1e-9);
    }

    @Test
    public void missingFrameIsUnavailableNotZero() {
        CanSpiDecoder.Snapshot s = CanSpiDecoder.parse(snapshot(propulsion(0x141, "0000061800000000")));
        assertTrue(Double.isNaN(CanSpiDecoder.HV_BATT_CAP.value(s)));
    }

    @Test
    public void shortFrameIsUnavailable() {
        CanSpiDecoder.Snapshot s = CanSpiDecoder.parse(snapshot(
                record(CanSpiDecoder.BUS_PROPULSION, 0x293, 0x84, hex("00000000"))));
        assertTrue(Double.isNaN(CanSpiDecoder.HV_BATT_EGY_CDN.value(s)));
    }
}

package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** VHAL stream batches decoded by hand (no generated protobuf code). */
public class VhalStreamTest {
    private static void varint(ByteArrayOutputStream out, long v) {
        do { int b = (int) (v & 127); v >>>= 7; out.write(b | (v != 0 ? 128 : 0)); } while (v != 0);
    }
    private static void bytes(ByteArrayOutputStream out, int tag, byte[] value) {
        varint(out, tag * 8L + 2); varint(out, value.length); out.write(value, 0, value.length);
    }
    private static void number(ByteArrayOutputStream out, int tag, long value) { varint(out, tag * 8L); varint(out, value); }

    /** One VehiclePropValue inside a WrappedVehiclePropValue. */
    private static byte[] wrapped(int prop, int area, int status, Integer sint32, Float f, Long int64) {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        number(value, 1, prop);
        number(value, 4, area);
        if (sint32 != null) {   // packed repeated sint32: zigzag
            ByteArrayOutputStream packed = new ByteArrayOutputStream();
            varint(packed, (sint32 << 1) ^ (sint32 >> 31));
            bytes(value, 5, packed.toByteArray());
        }
        if (int64 != null) {
            ByteArrayOutputStream packed = new ByteArrayOutputStream();
            varint(packed, int64);
            bytes(value, 6, packed.toByteArray());
        }
        if (f != null) bytes(value, 7, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(f).array());
        if (status != 0) number(value, 10, status);
        ByteArrayOutputStream w = new ByteArrayOutputStream();
        bytes(w, 1, value.toByteArray());
        return w.toByteArray();
    }

    private static final class Got implements VhalStream.Listener {
        final List<String> events = new ArrayList<>();
        @Override public void onValue(int prop, int area, int intValue, float floatValue, boolean isFloat) {
            events.add(String.format("%08x %d %s", prop, area, isFloat ? "f" + floatValue : "i" + intValue));
        }
        @Override public void onAvailability(int prop, int area, boolean available) {
            if (!available) events.add(String.format("%08x unavailable", prop));
        }
        @Override public void onConnected(boolean connected) { }
    }

    @Test public void decodesFloatIntAndUnavailableValues() {
        ByteArrayOutputStream batch = new ByteArrayOutputStream();
        bytes(batch, 1, wrapped(0x11600204, 0, 0, null, 29190.5f, null));   // odometer, float
        bytes(batch, 1, wrapped(0x11400400, 0, 0, 4, null, null));          // gear = Park
        bytes(batch, 1, wrapped(0x11400401, 0, 0, -3, null, null));         // a negative int32 survives zigzag
        bytes(batch, 1, wrapped(0x11600308, 0, 2, null, 334f, null));       // status 2: unavailable, no value
        Got got = new Got();
        VhalStream.decodeBatch(batch.toByteArray(), List.of(got));
        assertEquals(List.of("11600204 0 f29190.5", "11400400 0 i4", "11400401 0 i-3", "11600308 unavailable"), got.events);
    }

    @Test public void int64ValuesKeepTheirFloatBits() {
        ByteArrayOutputStream batch = new ByteArrayOutputStream();
        bytes(batch, 1, wrapped(0x15000001, 0, 0, null, null, (long) Float.floatToIntBits(13.6f)));
        Got got = new Got();
        VhalStream.decodeBatch(batch.toByteArray(), List.of(got));
        assertEquals(List.of("15000001 0 f13.6"), got.events);
    }

    @Test public void malformedBatchesAreIgnored() {
        Got got = new Got();
        VhalStream.decodeBatch(new byte[]{(byte) 0x0a, (byte) 0x7f, 1, 2}, List.of(got)); // length past the end
        VhalStream.decodeBatch(new byte[0], List.of(got));
        assertTrue(got.events.isEmpty());
    }
}

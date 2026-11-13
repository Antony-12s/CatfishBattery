package com.catfish.battery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decoder for the head unit's bigdata "canspi" MQTT payload (nanomq at 127.0.0.1:1883): a ~4 Hz
 * snapshot of the latest data of every CAN frame the CP forwards to the head unit.
 * Format and signal definitions are from EX5 build 264133, see docs/findings/2026-11-13-bigdata-battery-health.md.
 * Read-only: nothing here publishes to the broker.
 */
final class CanSpiDecoder {
    static final int BUS_PROPULSION = 2; // header bus index of PropulsionCAN

    private static final int HEADER_BYTES = 10; // u64 timestamp + u16 body length
    private static final int MARKER = 0x55;
    private static final int FLAG_CAN_FD = 0x40;

    // Health-adjacent signals from the car's gee.json DBC (PropulsionCAN). Meaning of SOCE/EgyCdn/PwrCdn is unconfirmed.
    static final Signal HV_BATT_CAP = new Signal("HvBattCap", 0x499, 7, 16, false, 0.1, 0, "Ah");
    static final Signal HV_BATT_SOCE = new Signal("HvBattSOCE", 0x15C, 23, 8, false, 0.5, 0, "%");
    static final Signal HV_BATT_EGY_CDN = new Signal("HvBattEgyCdn", 0x293, 63, 8, false, 0.5, 0, "%");
    static final Signal HV_BATT_PWR_CDN = new Signal("HvBattPwrCdn", 0x301, 7, 8, false, 0.5, 0, "%");
    static final Signal HV_BATT_EGY_AVL_DCHA = new Signal("HvBattEgyAvlDcha1", 0x286, 4, 13, false, 20, 0, "Wh");
    static final Signal HV_BATT_SOC = new Signal("HvBattSoc", 0x178, 47, 11, false, 0.05, 0, "%");
    static final Signal CELL_U_MAX = new Signal("CellUMax", 0x145, 7, 13, false, 0.001, 0, "V");
    static final Signal CELL_U_MIN = new Signal("CellUMin", 0x145, 47, 13, false, 0.001, 0, "V");
    static final Signal HV_BATT_T_AVG = new Signal("HvBattTAvg", 0x315, 6, 13, true, 0.1, 0, "degC");
    static final Signal HV_BATT_T_MAX = new Signal("HvBattTMax", 0x315, 9, 13, true, 0.1, 0, "degC");
    static final Signal HV_BATT_T_MIN = new Signal("HvBattTMin", 0x315, 28, 13, true, 0.1, 0, "degC");
    static final Signal HV_BATT_U_DC = new Signal("HvBattUDc", 0x141, 18, 11, false, 0.25, 0, "V");
    static final Signal HV_BATT_I_DC = new Signal("HvBattIDc1", 0x143, 6, 15, false, 0.1, -1638, "A");

    private CanSpiDecoder() { }

    static final class Frame {
        final int bus, id;
        final byte[] data;

        Frame(int bus, int id, byte[] data) {
            this.bus = bus;
            this.id = id;
            this.data = data;
        }
    }

    static final class Snapshot {
        final long timestampMs;
        final List<Frame> frames;

        Snapshot(long timestampMs, List<Frame> frames) {
            this.timestampMs = timestampMs;
            this.frames = Collections.unmodifiableList(frames);
        }

        /** The frame, or null when the CP does not forward it. */
        Frame find(int bus, int id) {
            for (Frame f : frames) if (f.bus == bus && f.id == id) return f;
            return null;
        }
    }

    /**
     * One DBC signal. shortcut: Motorola (big-endian) only; 1246 of 1248 gee.json signals are, add Intel when one is needed.
     */
    static final class Signal {
        final String name, unit;
        final int frameId, startBit, length;
        final boolean signed;
        final double scale, offset;

        Signal(String name, int frameId, int startBit, int length, boolean signed, double scale, double offset, String unit) {
            this.name = name;
            this.frameId = frameId;
            this.startBit = startBit;
            this.length = length;
            this.signed = signed;
            this.scale = scale;
            this.offset = offset;
            this.unit = unit;
        }

        /** Physical value, or NaN when the frame is missing or too short (unavailable, never zero). */
        double value(Snapshot snapshot) {
            Frame f = snapshot.find(BUS_PROPULSION, frameId);
            if (f == null) return Double.NaN;
            long raw = 0;
            int bit = startBit; // DBC numbering: byte * 8 + bit, start = MSB
            for (int i = 0; i < length; i++) {
                if (bit / 8 >= f.data.length) return Double.NaN;
                raw = (raw << 1) | ((f.data[bit / 8] >> (bit % 8)) & 1);
                bit = bit % 8 == 0 ? bit + 15 : bit - 1;
            }
            if (signed && (raw >> (length - 1)) == 1) raw -= 1L << length;
            return raw * scale + offset;
        }
    }

    static Snapshot parse(byte[] payload) {
        if (payload.length < HEADER_BYTES) throw new IllegalArgumentException("snapshot header truncated");
        long timestamp = 0;
        for (int i = 0; i < 8; i++) timestamp = (timestamp << 8) | (payload[i] & 0xFF);
        int end = HEADER_BYTES + (((payload[8] & 0xFF) << 8) | (payload[9] & 0xFF));
        if (end > payload.length) throw new IllegalArgumentException("body longer than payload");
        List<Frame> frames = new ArrayList<>();
        int pos = HEADER_BYTES;
        while (pos < end) {
            if (pos + 4 > end) throw new IllegalArgumentException("record header truncated at " + pos);
            if ((payload[pos] & 0xFF) != MARKER) throw new IllegalArgumentException("no record marker at " + pos);
            int header = ((payload[pos + 1] & 0xFF) << 8) | (payload[pos + 2] & 0xFF);
            int flags = payload[pos + 3] & 0xFF;
            // low 6 bits = length; 0 means 64 on CAN FD and an empty record on classic CAN
            int length = (flags & 0x3F) != 0 ? flags & 0x3F : (flags & FLAG_CAN_FD) != 0 ? 64 : 0;
            if (pos + 4 + length > end) throw new IllegalArgumentException("record data truncated at " + pos);
            byte[] data = new byte[length];
            System.arraycopy(payload, pos + 4, data, 0, length);
            frames.add(new Frame(header >>> 12, header & 0xFFF, data));
            pos += 4 + length;
        }
        return new Snapshot(timestamp, frames);
    }
}

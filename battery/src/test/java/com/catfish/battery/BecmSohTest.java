package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import org.junit.Test;

/** BECM DID 0x496D reply decoding and the once-per-day rule. */
public class BecmSohTest {
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    @Test public void decodesTheReplyReadOnThisCar() {
        // 2026-11-18, parked: 62 49 6D 00 00 25 AC = 9644 = 96.44 %
        assertEquals(96.44, BecmSoh.decode(bytes(0x62, 0x49, 0x6D, 0x00, 0x00, 0x25, 0xAC)), 1e-9);
    }

    @Test public void rejectsNegativeShortOrImplausibleReplies() {
        assertTrue(Double.isNaN(BecmSoh.decode(bytes(0x7F, 0x22, 0x31))));
        assertTrue(Double.isNaN(BecmSoh.decode(bytes(0x62, 0x49, 0x6D, 0x25))));
        assertTrue(Double.isNaN(BecmSoh.decode(bytes(0x62, 0x49, 0x6E, 0x00, 0x00, 0x25, 0xAC))));
        assertTrue(Double.isNaN(BecmSoh.decode(bytes(0x62, 0x49, 0x6D, 0x00, 0x00, 0x27, 0x11)))); // 100.01 %
        assertTrue(Double.isNaN(BecmSoh.decode(bytes(0x62, 0x49, 0x6D, 0x00, 0x00, 0x00, 0x00))));
        assertTrue(Double.isNaN(BecmSoh.decode(null)));
    }

    @Test public void sameDayUsesTheLocalCalendarDay() {
        long morning = 1794708000000L;                 // 2026-11-14 09:00 +07
        assertTrue(BecmSoh.sameDay(morning, morning + 14 * 3600_000L, BANGKOK));   // 23:00 same day
        assertFalse(BecmSoh.sameDay(morning, morning + 15 * 3600_000L, BANGKOK));  // 00:00 next day
        assertFalse(BecmSoh.sameDay(morning, morning - 10 * 3600_000L, BANGKOK));  // 23:00 day before
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) b[i] = (byte) values[i];
        return b;
    }
}

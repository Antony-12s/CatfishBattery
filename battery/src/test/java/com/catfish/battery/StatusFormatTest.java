package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import java.util.TimeZone;

import org.junit.Test;

/** Status page helpers: badge age text, freshness state, reset arming. Same rules as the WebView batteryScript. */
public class StatusFormatTest {
    @Test
    public void relAgeSteps() {
        assertEquals("--", StatusFormat.relAge(-1));
        assertEquals("just now", StatusFormat.relAge(0));
        assertEquals("just now", StatusFormat.relAge(1400));
        assertEquals("2s ago", StatusFormat.relAge(1600));
        assertEquals("59s ago", StatusFormat.relAge(59_000));
        assertEquals("1m ago", StatusFormat.relAge(60_000));
        assertEquals("59m ago", StatusFormat.relAge(59 * 60_000L + 29_000));
        assertEquals("1h ago", StatusFormat.relAge(3_600_000L));
        assertEquals("23h ago", StatusFormat.relAge(23 * 3_600_000L + 59 * 60_000L));
        assertEquals("2d ago", StatusFormat.relAge(49 * 3_600_000L));
    }

    @Test
    public void kwhUsesTwoDecimalsBelow100KwhAndOneAbove() {
        assertEquals("--", StatusFormat.kwh(null));
        assertEquals("0.00 kWh", StatusFormat.kwh(0.0));
        assertEquals("12.35 kWh", StatusFormat.kwh(12_345.0));
        assertEquals("123.5 kWh", StatusFormat.kwh(123_456.0));
    }

    @Test
    public void sinceIsDayMonthTimeOrFromBoot() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        Calendar c = Calendar.getInstance(utc);
        c.clear();
        c.set(2026, Calendar.NOVEMBER, 8, 7, 5);
        assertEquals("8 Nov 07:05 \u2192", StatusFormat.since(c.getTimeInMillis(), utc));
        assertEquals("from boot", StatusFormat.since(0, utc));
    }

    @Test
    public void moneyMatchesTheWebPages() {
        assertEquals("--", StatusFormat.money(null, "\u0e3f"));
        assertEquals("\u0e3f12.3", StatusFormat.money(12.34, "\u0e3f"));
        assertEquals("\u0e3f1,235", StatusFormat.money(1234.6, "\u0e3f"));
        assertEquals("$\u22121.5", StatusFormat.money(-1.5, "$"));
    }

    @Test
    public void deltaLevelThresholds() {
        assertEquals(StatusFormat.GOOD, StatusFormat.deltaLevel(null));
        assertEquals(StatusFormat.GOOD, StatusFormat.deltaLevel(19));
        assertEquals(StatusFormat.WARN, StatusFormat.deltaLevel(20));
        assertEquals(StatusFormat.WARN, StatusFormat.deltaLevel(49));
        assertEquals(StatusFormat.DANGER, StatusFormat.deltaLevel(50));
    }

    @Test
    public void integerReadingsShowDashesWhenMissing() {
        assertEquals("--", StatusFormat.whole(null, " mV"));
        assertEquals("3712 mV", StatusFormat.whole(3712, " mV"));
        assertEquals("\u22125 \u00b0C", StatusFormat.whole(-5, " \u00b0C"));
    }

    @Test
    public void freshnessLiveStaleReconnectingOffline() {
        StatusFreshness f = new StatusFreshness();
        StatusFreshness.State s = f.update(true, 0, 1_000);
        assertEquals("Connecting", s.label);
        assertEquals(StatusFreshness.OFFLINE, s.kind);
        assertEquals("No data yet", s.updated);

        s = f.update(true, 10_000, 11_000);
        assertEquals("Live", s.label);
        assertEquals(StatusFreshness.LIVE, s.kind);
        assertEquals("Updated just now", s.updated);
        assertFalse(s.updatedStale);
        assertFalse(s.dim);

        s = f.update(true, 10_000, 16_500);
        assertEquals("Stale", s.label);
        assertEquals(StatusFreshness.STALE, s.kind);
        assertEquals("Updated 7s ago", s.updated);
        assertTrue(s.updatedStale);
        assertTrue(s.dim);

        // the stream dropped and the snapshot has no time: keep the last good time
        s = f.update(false, 0, 20_000);
        assertEquals("Reconnecting", s.label);
        assertEquals(StatusFreshness.STALE, s.kind);
        assertEquals("Updated 10s ago", s.updated);
        assertFalse("offline is not dimmed on the car", s.dim);
    }

    @Test
    public void freshnessWithNothingEverSeenIsNoVehicleData() {
        StatusFreshness.State s = new StatusFreshness().update(false, 0, 5_000);
        assertEquals("No vehicle data", s.label);
        assertEquals(StatusFreshness.OFFLINE, s.kind);
        assertEquals("No data yet", s.updated);
        assertTrue(s.updatedStale);
    }

    @Test
    public void resetNeedsTwoTapsWithinFourSeconds() {
        StatusResetArming r = new StatusResetArming();
        assertEquals("Reset totals", r.label(0));
        assertFalse(r.tap(1_000));
        assertTrue(r.armed(1_000));
        assertEquals("Tap again to reset", r.label(4_999));
        assertTrue(r.tap(4_999));
        assertFalse(r.armed(5_000));
        assertEquals("Reset totals", r.label(5_000));
    }

    @Test
    public void resetArmingExpiresAfterFourSeconds() {
        StatusResetArming r = new StatusResetArming();
        assertFalse(r.tap(1_000));
        assertFalse(r.armed(5_000));
        assertEquals("Reset totals", r.label(5_000));
        assertFalse("a late second tap only arms again", r.tap(5_000));
        assertTrue(r.armed(5_001));
    }
}

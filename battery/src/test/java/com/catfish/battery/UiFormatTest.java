package com.catfish.battery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Shared number formats of the native pages. */
public class UiFormatTest {
    @Test
    public void durationUnderAnHourIsMinutes() {
        assertEquals("0 min", UiFormat.duration(0));
        assertEquals("59 min", UiFormat.duration(59));
    }

    @Test
    public void durationFromAnHourIsHoursAndMinutes() {
        assertEquals("1 h 0 min", UiFormat.duration(60));
        assertEquals("11 h 55 min", UiFormat.duration(715));
    }

    @Test
    public void durationRoundsFractionalMinutes() {
        assertEquals("1 h 0 min", UiFormat.duration(59.6));
    }

    @Test
    public void missingValuesAreDashes() {
        assertEquals("--", UiFormat.duration(Double.NaN));
        assertEquals("--", UiFormat.fixed(Double.NaN, 1, " V"));
        assertEquals("--", UiFormat.percent2(null));
    }

    @Test
    public void sohShowsTwoDecimals() {
        assertEquals("96.50 %", UiFormat.percent2(96.5));
        assertEquals("96.44 %", UiFormat.percent2(96.44));
    }

    @Test
    public void fixedUsesUnicodeMinusAndDotDecimal() {
        assertEquals("391.8 V", UiFormat.fixed(391.75, 1, " V"));
        assertEquals("\u22122.7 A", UiFormat.fixed(-2.7, 1, " A"));
    }

    @Test
    public void powerSwitchesFromWattsToKilowatts() {
        assertEquals("640 W", UiFormat.power(640));
        assertEquals("1.1 kW", UiFormat.power(1100));
        assertEquals("\u22121.1 kW", UiFormat.power(-1100));
        assertEquals("120 kW", UiFormat.power(120000));
        assertEquals("--", UiFormat.power(Double.NaN));
    }

    @Test public void dateAndDateTimeForTheBuildStamp() {
        java.util.TimeZone bkk = java.util.TimeZone.getTimeZone("Asia/Bangkok");
        long t = 1795002360000L; // 2026-11-18 11:46 UTC = 18:46 in Bangkok
        assertEquals("18 Nov 2026", UiFormat.date(t, bkk));
        assertEquals("18 Nov 2026, 18:46", UiFormat.dateTime(t, bkk));
        assertEquals("1 Jan 2027, 06:05", UiFormat.dateTime(1798758300000L, bkk));
    }
}

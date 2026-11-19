package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;

/** Battery health page: SOH trend against time, distance and cell spread. */
public class HealthModelTest {
    private static final long DAY = 86_400_000L;
    private static final long T0 = 1794960000000L;      // 2026-11-18
    private static final TimeZone BANGKOK = TimeZone.getTimeZone("Asia/Bangkok");

    private static SohRecorder.Entry e(long ts, double pct, double km, double diff) {
        return new SohRecorder.Entry(ts, pct, SohRecorder.BECM, km, diff);
    }

    @Test public void leastSquaresFitsALineAndReportsR2() {
        HealthModel.Fit f = HealthModel.fit(new double[]{0, 1, 2, 3}, new double[]{1, 3, 5, 7});
        assertEquals(2, f.slope, 1e-12);
        assertEquals(1, f.intercept, 1e-12);
        assertEquals(1, f.r2, 1e-12);
        assertEquals(4, f.n);
        assertTrue(Double.isNaN(HealthModel.fit(new double[]{5, 5}, new double[]{1, 2}).slope)); // no spread in x
    }

    @Test public void ratesComeFromTheTrendNotFirstMinusLast() {
        List<SohRecorder.Entry> rows = new ArrayList<>();
        // 120 days, 2,080 km a month, SOH falls 1 % in steps; one noisy reading must not swing the rate
        for (int d = 0; d <= 120; d++) rows.add(e(T0 + d * DAY, 97.5 - (d / 30) * 0.25, 20000 + d * 69.3, 3));
        rows.set(60, e(T0 + 60 * DAY, 99.0, 20000 + 60 * 69.3, 3));
        HealthModel m = HealthModel.from(rows, T0 + 120 * DAY, BANGKOK);
        assertTrue(m.enough);
        assertTrue(m.perYear < -2.5 && m.perYear > -3.5);
        assertTrue(m.per10kKm < -0.9 && m.per10kKm > -1.5);
        assertTrue(m.per10kText.matches("about \u2212[01]\\.[0-9] % per 10,000 km"));
        assertTrue(m.km90 > m.latestKm);
        assertTrue(m.projection.startsWith("At this rate 90 % at about "));
    }

    @Test public void tooShortOrUnchangedHistoryIsCollectingWithASinceNewEstimate() {
        List<SohRecorder.Entry> rows = new ArrayList<>();
        rows.add(e(T0, 96.44, 29161, 3));
        rows.add(e(T0 + 3 * DAY, 96.44, 29400, 3));
        HealthModel m = HealthModel.from(rows, T0 + 3 * DAY, BANGKOK);
        assertFalse(m.enough);
        assertEquals("2 of 30 days · 2 readings", m.collecting); // calendar days with a reading, not elapsed days
        // since new, assuming 100 % at 0 km: (96.44 - 100) / 29,400 km * 10,000
        assertEquals("about \u22121.2 % per 10,000 km since new", m.sinceNew);
        assertEquals("", m.projection);

        List<SohRecorder.Entry> flat = new ArrayList<>();
        for (int d = 0; d <= 60; d++) flat.add(e(T0 + d * DAY, 96.44, 29161 + d * 70, 3));
        assertFalse(HealthModel.from(flat, T0 + 60 * DAY, BANGKOK).enough); // SOH never changed: no rate yet
    }

    @Test public void trendAndDaysUseOnlyTheBecm() {
        // the car's history: canspi 96.5 (0.5 % steps), then two BECM 96.44 readings on 18 and 19 Nov
        List<SohRecorder.Entry> rows = new ArrayList<>();
        rows.add(new SohRecorder.Entry(T0, 96.5, SohRecorder.CANSPI, Double.NaN, Double.NaN));
        rows.add(e(T0 + 5 * 3_600_000L, 96.44, 29161, Double.NaN));
        rows.add(e(T0 + DAY, 96.44, 29190, 3));
        HealthModel m = HealthModel.from(rows, T0 + DAY + 1000, BANGKOK);
        assertEquals("2 of 30 days · 2 readings", m.collecting);
        assertEquals(2, m.days.length);           // the canspi point is not on the trend charts
        assertEquals(96.44, m.pctsAll[0], 0);
        assertEquals(0, m.byTime.slope, 1e-12);   // flat: no -16 %/year from mixing sources
        assertEquals(3, m.table.size());          // but it stays in the table
        assertEquals("canspi", m.table.get(2)[2]);

        // 40 days of BECM readings that never move, plus one stray canspi: SOH has not changed, no rate
        List<SohRecorder.Entry> mixed = new ArrayList<>();
        mixed.add(new SohRecorder.Entry(T0, 97.0, SohRecorder.CANSPI, Double.NaN, Double.NaN));
        for (int d = 1; d <= 40; d++) mixed.add(e(T0 + d * DAY, 96.44, 29161 + d * 70, 3));
        assertFalse(HealthModel.from(mixed, T0 + 40 * DAY, BANGKOK).enough);
    }

    @Test public void canspiOnlyHistoryStillCounts() {
        // before the BECM read existed (or where it is unavailable) canspi is the only source
        List<SohRecorder.Entry> rows = new ArrayList<>();
        rows.add(new SohRecorder.Entry(T0, 96.5, SohRecorder.CANSPI, Double.NaN, Double.NaN));
        HealthModel m = HealthModel.from(rows, T0, BANGKOK);
        assertEquals("1 of 30 days · 1 reading", m.collecting);
    }

    @Test public void verdictBands() {
        assertEquals("Healthy", HealthModel.verdict(96.44));
        assertEquals("Healthy", HealthModel.verdict(90));
        assertEquals("Normal ageing", HealthModel.verdict(85));
        assertEquals("Check", HealthModel.verdict(79.9));
        assertEquals("--", HealthModel.verdict(Double.NaN));
    }

    @Test public void emptyHistoryShowsDashesNotZeros() {
        HealthModel m = HealthModel.from(new ArrayList<>(), T0, BANGKOK);
        assertEquals("--", m.sohText);
        assertEquals("--", m.kmText);
        assertFalse(m.enough);
        assertEquals("", m.sinceNew);
        assertEquals("0 of 30 days · 0 readings", m.collecting);
    }

    @Test public void tableIsNewestFirstWithSourcePrecision() {
        List<SohRecorder.Entry> rows = new ArrayList<>();
        rows.add(new SohRecorder.Entry(T0, 96.5, SohRecorder.CANSPI, Double.NaN, Double.NaN));
        rows.add(e(T0 + DAY, 96.44, 29161, 3));
        HealthModel m = HealthModel.from(rows, T0 + DAY, BANGKOK);
        assertEquals(2, m.table.size());
        assertEquals("96.44 %", m.table.get(0)[1]);
        assertEquals("BECM", m.table.get(0)[2]);
        assertEquals("29,161 km", m.table.get(0)[3]);
        assertEquals("3 mV", m.table.get(0)[4]);
        assertEquals("96.5 %", m.table.get(1)[1]);
        assertEquals("canspi", m.table.get(1)[2]);
        assertEquals("--", m.table.get(1)[3]);
    }
}

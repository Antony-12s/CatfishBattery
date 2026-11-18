package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The HV PACK sparkline: its window follows the car's state, its axis never exaggerates a tiny change. */
public class SocTrendTest {
    private static final TimeZone BANGKOK = TimeZone.getTimeZone("Asia/Bangkok");
    private static final long MIN = 60_000L, HOUR = 60 * MIN;
    private static final long NOW = 1794708000000L + 8 * HOUR; // 2026-11-14 17:00 +07

    /** Points every 10 min over the last 24 h; soc(t) given by the caller. */
    private static JSONObject history(java.util.function.LongToDoubleFunction soc, JSONObject live) throws Exception {
        JSONArray points = new JSONArray();
        for (long t = NOW - 24 * HOUR; t <= NOW; t += 10 * MIN) {
            points.put(new JSONArray().put(t).put(soc.applyAsDouble(t)).put(0).put(30).put(3));
        }
        return new JSONObject().put("points", points).put("live", live == null ? JSONObject.NULL : live);
    }

    private static JSONObject data(Integer gear, long leftParkMs, double target) throws Exception {
        JSONObject flow = new JSONObject();
        if (Double.isFinite(target)) flow.put("targetSoc", target);
        JSONObject d = new JSONObject().put("streamConnected", true).put("leftParkMs", leftParkMs).put("flow", flow);
        if (gear != null) d.put("gear", gear);
        return d;
    }

    @Test public void chargingShowsThisChargeWithTheTarget() throws Exception {
        long start = NOW - 125 * MIN;                       // plugged 14:55
        JSONObject live = new JSONObject().put("start", start);
        SocTrend t = SocTrend.of(history(ts -> ts < start ? 61 : 61 + (ts - start) / (double) MIN * 0.176, live),
                data(4, 0, 100), NOW, BANGKOK);
        assertEquals("SOC · THIS CHARGE", t.label);
        assertEquals("plugged 14:55", t.fromText);
        assertEquals(start, t.fromMs);
        assertEquals(100, t.target, 0);
        assertEquals(100, t.hi, 0);                         // the target fits, the axis stops at 100 %
        assertTrue(t.delta.startsWith("+"));
        assertTrue(t.delta.endsWith(" · 2 h 5 min"));
    }

    @Test public void drivingShowsThisTripSinceLeavingPark() throws Exception {
        long left = NOW - 38 * MIN;                         // left P 16:22
        SocTrend t = SocTrend.of(history(ts -> ts < left ? 88.4 : 88.4 - (ts - left) / (double) MIN * 0.142, null),
                data(1, left, Double.NaN), NOW, BANGKOK);
        assertEquals("SOC · THIS TRIP", t.label);
        assertEquals("left P 16:22", t.fromText);
        assertTrue(t.delta.startsWith("\u2212"));
        assertTrue(t.delta.endsWith(" · 38 min"));
        assertTrue(Double.isNaN(t.target));
        for (double ts : t.ts) assertTrue(ts >= left);
    }

    @Test public void drivingWithAnUnknownStartFallsBackToTheLastHour() throws Exception {
        SocTrend t = SocTrend.of(history(ts -> 80, null), data(1, 0, Double.NaN), NOW, BANGKOK);
        assertEquals("SOC · 1 H", t.label);
        assertEquals(NOW - HOUR, t.fromMs);
    }

    @Test public void parkedOrUnknownGearShowsTheLastDay() throws Exception {
        assertEquals("SOC · 24 H", SocTrend.of(history(ts -> 83, null), data(4, 0, Double.NaN), NOW, BANGKOK).label);
        SocTrend unknown = SocTrend.of(history(ts -> 83, null), data(null, 0, Double.NaN), NOW, BANGKOK);
        assertEquals("SOC · 24 H", unknown.label);
        assertEquals("\u221224 h", unknown.fromText);
    }

    @Test public void windowIsAtLeastFifteenMinutes() throws Exception {
        SocTrend t = SocTrend.of(history(ts -> 80, null), data(1, NOW - 2 * MIN, Double.NaN), NOW, BANGKOK);
        assertEquals(NOW - 15 * MIN, t.fromMs);
    }

    @Test public void axisIsAtLeastFivePercentTallSoAFlatParkStaysFlat() throws Exception {
        SocTrend t = SocTrend.of(history(ts -> 83 + (NOW - ts) / (double) HOUR * 0.4 / 24, null),
                data(4, 0, Double.NaN), NOW, BANGKOK);
        assertTrue(t.hi - t.lo >= 5);
        assertTrue(t.lo <= 83 && t.hi >= 83.4);
        assertEquals("\u22120.4 %", t.delta);
    }

    @Test public void tooFewPointsIsEmptyNotFake() throws Exception {
        JSONObject h = new JSONObject().put("points", new JSONArray()
                .put(new JSONArray().put(NOW).put(80).put(0).put(30).put(3))).put("live", JSONObject.NULL);
        SocTrend t = SocTrend.of(h, data(4, 0, Double.NaN), NOW, BANGKOK);
        assertTrue(t.empty());
        assertEquals("", t.delta);
        assertFalse(SocTrend.of(history(ts -> 80, null), data(4, 0, Double.NaN), NOW, BANGKOK).empty());
    }

    @Test public void missingSocValuesAreSkipped() throws Exception {
        JSONArray points = new JSONArray()
                .put(new JSONArray().put(NOW - 30 * MIN).put(81).put(0).put(30).put(3))
                .put(new JSONArray().put(NOW - 20 * MIN).put(JSONObject.NULL).put(0).put(30).put(3))
                .put(new JSONArray().put(NOW - 10 * MIN).put(80).put(0).put(30).put(3));
        SocTrend t = SocTrend.of(new JSONObject().put("points", points), data(4, 0, Double.NaN), NOW, BANGKOK);
        assertEquals(2, t.soc.length);
    }
}

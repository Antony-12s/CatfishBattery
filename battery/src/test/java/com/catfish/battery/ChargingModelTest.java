package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/** Text of the Charging page: range, totals tiles, live charge panel and recent charges (WebView chargingScript). */
public class ChargingModelTest {
    static final long WED = ChargingCostTest.WED_NOON;

    @Test
    public void rangeFallsBackToTheLastDay() {
        assertEquals("7d", ChargingModel.range("7d"));
        assertEquals("24h", ChargingModel.range("2y"));
        assertEquals("24h", ChargingModel.range(null));
        assertEquals("Last 6 hours", ChargingModel.rangeLabel("6h"));
    }

    @Test
    public void kickerAddsTheDatesWhenThereArePoints() throws Exception {
        JSONObject h = new JSONObject("{\"points\":[[" + (WED - 86400000L) + ",50,1,25,3],[" + WED + ",60,2,25,3]]}");
        assertEquals("Last 24 hours · Tue 17 Nov – Wed 18 Nov",
                ChargingModel.kicker("24h", h.getJSONArray("points"), ChargingCostTest.BKK));
        assertEquals("Last hour", ChargingModel.kicker("1h", new JSONObject("{\"points\":[]}").getJSONArray("points"),
                ChargingCostTest.BKK));
    }

    @Test
    public void statsTilesShowTheRangeTotalsAndTheirCost() throws Exception {
        JSONObject st = new JSONObject("{\"kwhOut\":20,\"kwhIn\":5.25,\"kwhRegen\":2,\"socMin\":41.6,\"socMax\":80.2,\"cellDiffMax\":12.4}");
        List<ChargingModel.Tile> t = ChargingModel.statsTiles(st, ChargingCostTest.cost());
        assertEquals(6, t.size());
        assertEquals("Used", t.get(0).label);
        assertEquals("20.0 kWh", t.get(0).value);
        assertEquals("pack → loads", t.get(0).sub);
        assertEquals("5.3 kWh", t.get(1).value);
        assertEquals("good", t.get(2).tone);
        assertEquals("฿50.0", t.get(3).value); // (20-2)/0.9*2.5
        assertEquals("2.50 ฿/kWh · used − regen", t.get(3).sub);
        assertEquals("42–80%", t.get(4).value);
        assertEquals("12 mV", t.get(5).value);
    }

    @Test
    public void statsTilesShowDashesWithoutData() throws Exception {
        JSONObject st = new JSONObject("{\"kwhOut\":null,\"kwhIn\":null,\"kwhRegen\":null,\"socMin\":null,\"socMax\":null}");
        List<ChargingModel.Tile> t = ChargingModel.statsTiles(st, new ChargingCost(null, ChargingCostTest.BKK));
        for (ChargingModel.Tile tile : t) assertEquals(tile.label, "--", tile.value);
        assertEquals("set prices in Settings", t.get(3).sub);
    }

    @Test
    public void liveFactsUseHoursForLongEtas() throws Exception {
        JSONObject live = new JSONObject("{\"start\":" + WED + ",\"end\":" + WED + ",\"type\":\"ac\",\"socStart\":40,"
                + "\"socEnd\":62.4,\"kwNow\":-1.13,\"kwhIn\":3.6,\"kwhAdded\":null,\"peakKw\":7.04,"
                + "\"etaTo80Min\":45.2,\"etaTo100Min\":715,\"parked\":false,\"charger\":null}");
        ChargingModel.Live f = ChargingModel.live(live, ChargingCostTest.cost());
        assertEquals("1.1 kW", f.kwNow);
        assertEquals("62% → 80%", f.soc);
        assertEquals("3.6 kWh", f.kwhIn);
        assertEquals("≈ ฿24.0", f.cost); // 3.6/0.9*6 on-peak
        assertEquals("7.0 kW", f.peak);
        assertEquals("45 min", f.eta80);
        assertEquals("11 h 55 min", f.eta100);
    }

    @Test
    public void liveFactsWithoutReadingsShowDashes() throws Exception {
        JSONObject live = new JSONObject("{\"start\":" + WED + ",\"end\":" + WED + ",\"socEnd\":85,\"kwNow\":null,"
                + "\"kwhIn\":null,\"peakKw\":null,\"etaTo80Min\":null,\"etaTo100Min\":null}");
        ChargingModel.Live f = ChargingModel.live(live, new ChargingCost(null, ChargingCostTest.BKK));
        assertEquals("--", f.kwNow);
        assertEquals("85% → 100%", f.soc);
        assertEquals("--", f.eta80);
        assertEquals("", f.cost);
    }

    @Test
    public void recentChargesAreTheLastThreeNewestFirstOrTheLastOneWhileCharging() throws Exception {
        JSONObject h = new JSONObject("{\"sessions\":["
                + sess(WED - 4 * 86400000L, 30, false) + "," + sess(WED - 3 * 86400000L, 31, false) + ","
                + sess(WED - 2 * 86400000L, 32, false) + "," + sess(WED - 86400000L, 33, true) + "]}");
        List<ChargingModel.Row> rows = ChargingModel.recent(h.getJSONArray("sessions"), false,
                ChargingCostTest.cost(), ChargingCostTest.BKK);
        assertEquals(3, rows.size());
        ChargingModel.Row r = rows.get(0);
        assertEquals("AC", r.tag);
        assertEquals("Tue 17 Nov · 12:00", r.when);
        assertEquals("parked · until 13:30", r.sub);
        assertEquals("33 → 80 %", r.meta.get(0));
        assertEquals("+12.0 kWh", r.meta.get(1));
        assertEquals("11 kW peak", r.meta.get(2));
        assertEquals(33, r.trackFrom, 0);
        assertEquals(80, r.trackTo, 0);
        assertEquals("1 h 30 min", rows.get(1).sub);
        assertEquals(1, ChargingModel.recent(h.getJSONArray("sessions"), true, ChargingCostTest.cost(),
                ChargingCostTest.BKK).size());
    }

    @Test
    public void recentRowWithoutTypeHasADash() throws Exception {
        JSONObject s = new JSONObject("{\"start\":" + WED + ",\"end\":" + (WED + 600000) + ",\"socStart\":null,"
                + "\"socEnd\":null,\"kwhIn\":null,\"kwhAdded\":null,\"peakKw\":null,\"type\":null,\"parked\":false}");
        ChargingModel.Row r = ChargingModel.recent(new org.json.JSONArray().put(s), false,
                new ChargingCost(null, ChargingCostTest.BKK), ChargingCostTest.BKK).get(0);
        assertEquals("–", r.tag);
        assertEquals("--", r.cost);
        assertEquals("-- → --", r.meta.get(0));
        assertEquals(2, r.meta.size());
        assertFalse(r.dc);
        assertTrue(r.trackTo >= r.trackFrom);
    }

    static String sess(long start, double socStart, boolean parked) {
        return "{\"start\":" + start + ",\"end\":" + (start + 5400000L) + ",\"socStart\":" + socStart + ",\"socEnd\":80,"
                + "\"kwhIn\":11.5,\"kwhAdded\":12,\"peakKw\":11.2,\"type\":\"ac\",\"parked\":" + parked + ",\"charger\":null}";
    }
}

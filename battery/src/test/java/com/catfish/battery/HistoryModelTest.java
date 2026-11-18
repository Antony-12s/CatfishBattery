package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/** Text of the Charge history page: KPIs, month blocks, rows and the charger picker (WebView chargeHistoryScript). */
public class HistoryModelTest {
    static final long WED = ChargingCostTest.WED_NOON;
    static final long DAY = 86400000L;

    static JSONObject data(String live) throws Exception {
        return new JSONObject("{\"sessions\":["
                + row(WED - 40 * DAY, "ac", "null", false) + ","          // 9 Oct
                + row(WED - 2 * DAY, "dc", "null", false) + ","           // Mon 16 Nov
                + row(WED - DAY, "ac", "\"volta\"", true) + ","           // Tue 17 Nov, parked
                + "{\"start\":\"bad\"}],\"live\":" + live + "}");
    }

    static String row(long start, String type, String charger, boolean parked) {
        return "{\"start\":" + start + ",\"end\":" + (start + (parked ? DAY + 3600000L : 3900000L)) + ",\"socStart\":40,"
                + "\"socEnd\":80,\"kwhIn\":19,\"kwhAdded\":20,\"peakKw\":" + ("dc".equals(type) ? 98.6 : 7.1)
                + ",\"type\":\"" + type + "\",\"parked\":" + parked + ",\"charger\":" + charger + "}";
    }

    static HistoryModel model(String live) throws Exception {
        return new HistoryModel(data(live), ChargingCostTest.cost(), WED, ChargingCostTest.BKK);
    }

    @Test
    public void kpisShowTheMonthAgainstTheBudgetAndAllTime() throws Exception {
        List<HistoryModel.Kpi> k = model("null").kpis();
        assertEquals(3, k.size());
        // this month: dc 20/0.95*7.5 = 157.89 + parked ac on volta 20/0.9*7.5 = 166.67 -> 324.56
        assertEquals("This month · November", k.get(0).label);
        assertEquals("฿325", k.get(0).value);
        assertEquals("of ฿1,500", k.get(0).unit);
        assertEquals(22, k.get(0).barPercent);
        assertEquals("", k.get(0).barTone);
        assertEquals("2 charges · 40.0 kWh · 22 % of budget", k.get(0).sub);
        // october ac on home, Fri 9 Oct 12:00 on-peak 20/0.9*6 = 133.33; all = 457.89 over 60 kWh
        assertEquals("Average price", k.get(1).label);
        assertEquals("฿7.6", k.get(1).value);
        assertEquals("/kWh", k.get(1).unit);
        assertEquals("with charging losses, all charges", k.get(1).sub);
        assertEquals("60.0 kWh", k.get(2).value);
        assertEquals("3 charges", k.get(2).unit);
        assertEquals("฿458 · since Fri 9 Oct", k.get(2).sub);
    }

    @Test
    public void kpisWithoutChargesOrPrices() throws Exception {
        HistoryModel m = new HistoryModel(new JSONObject("{\"sessions\":[],\"live\":null}"),
                new ChargingCost(null, ChargingCostTest.BKK), WED, ChargingCostTest.BKK);
        List<HistoryModel.Kpi> k = m.kpis();
        assertEquals("--", k.get(0).value);
        assertEquals(-1, k.get(0).barPercent);
        assertEquals("0 charges · --", k.get(0).sub);
        assertEquals("--", k.get(1).value);
        assertEquals("", k.get(1).sub);
        assertEquals("--", k.get(2).value);
        assertEquals("none yet", k.get(2).sub);
        assertTrue(m.months().isEmpty());
        assertTrue(m.isEmpty());
    }

    @Test
    public void monthsAreNewestFirstWithTheirTotals() throws Exception {
        List<HistoryModel.Month> months = model("null").months();
        assertEquals(2, months.size());
        assertEquals("November 2026", months.get(0).label);
        assertEquals("2 charges · 40.0 kWh · ฿325", months.get(0).summary);
        assertEquals(2, months.get(0).rows.size());
        assertEquals(WED - DAY, months.get(0).rows.get(0).start);
        assertEquals("October 2026", months.get(1).label);
    }

    @Test
    public void rowsCarryWhenSocEnergyChargerAndCost() throws Exception {
        HistoryModel.Row parked = model("null").months().get(0).rows.get(0);
        assertEquals("Tue 17 Nov · 12:00", parked.when);
        assertEquals("parked · until Wed 18 Nov 13:00", parked.sub);
        assertEquals("40 → 80 %", parked.soc);
        assertEquals("+20.0 kWh", parked.kwh);
        assertEquals("7 kW peak", parked.peak);
        assertEquals("PEA VOLTA", parked.charger);
        assertFalse(parked.chargerDefault);
        assertEquals("฿167", parked.cost);
        HistoryModel.Row dc = model("null").months().get(0).rows.get(1);
        assertEquals("DC", dc.tag);
        assertEquals("1 h 5 min", dc.sub);
        assertEquals("PEA VOLTA", dc.charger);
        assertTrue(dc.chargerDefault);
    }

    @Test
    public void liveChargeLeadsTheList() throws Exception {
        HistoryModel m = model("{\"start\":" + WED + ",\"end\":" + (WED + 600000) + ",\"socStart\":50,\"socEnd\":55,"
                + "\"kwhIn\":1,\"kwhAdded\":null,\"peakKw\":6.6,\"type\":\"ac\",\"parked\":false,\"charger\":null}");
        assertTrue(m.charging());
        HistoryModel.Row live = m.liveRow();
        assertTrue(live.live);
        assertEquals("charging now", live.sub);
        assertEquals("+1.0 kWh", live.kwh);
        assertNull(model("null").liveRow());
    }

    @Test
    public void budgetBarTurnsAmberFromEightyPercentAndRedPastIt() {
        assertEquals("", HistoryModel.budgetTone(0.79));
        assertEquals("warn", HistoryModel.budgetTone(0.8));
        assertEquals("danger", HistoryModel.budgetTone(1.01));
    }

    @Test
    public void pickerOffersTheDefaultThenEveryCharger() throws Exception {
        HistoryModel m = model("null");
        HistoryModel.Picker p = m.picker(WED - DAY);
        assertEquals("Charger for Tue 17 Nov · 12:00", p.title);
        assertEquals(3, p.options.size());
        assertEquals("", p.options.get(0).id);
        assertEquals("Default · Home", p.options.get(0).name);
        assertEquals("AC", p.options.get(0).detail);
        assertFalse(p.options.get(0).on);
        assertEquals("2.50 ฿/kWh – 6.00 ฿/kWh", p.options.get(1).detail);
        assertTrue(p.options.get(2).on);
        assertEquals("Default · PEA VOLTA", m.picker(WED - 2 * DAY).options.get(0).name);
        assertNull(m.picker(12345L));
    }

    @Test
    public void kwhTextDropsTheDecimalFromAHundred() {
        assertEquals("--", HistoryModel.kwhText(null));
        assertEquals("99.9 kWh", HistoryModel.kwhText(99.94));
        assertEquals("120 kWh", HistoryModel.kwhText(120.4));
    }
}

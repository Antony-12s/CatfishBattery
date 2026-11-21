package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

import java.util.TimeZone;

/** Prices and costs shared by the Charging and History pages (the WebView's commonScript money / chargeCost). */
public class ChargingCostTest {
    static final TimeZone BKK = TimeZone.getTimeZone("Asia/Bangkok");
    // Wed 18 Nov 2026 12:00 +07 (on-peak) and Sat 21 Nov 2026 12:00 +07 (weekend, off-peak)
    static final long WED_NOON = 1794978000000L;
    static final long SAT_NOON = WED_NOON + 3 * 86400000L;

    static JSONObject settings() throws Exception {
        return new JSONObject("{\"currency\":\"THB\",\"chargers\":["
                + "{\"id\":\"home\",\"name\":\"Home\",\"price\":4,\"tou\":true,\"peak\":6,\"offPeak\":2.5},"
                + "{\"id\":\"volta\",\"name\":\"PEA VOLTA\",\"price\":7.5,\"tou\":false,\"peak\":0,\"offPeak\":0}],"
                + "\"ac\":\"home\",\"dc\":\"volta\",\"lossAc\":10,\"lossDc\":5,\"budget\":1500,\"alertSoc\":0}");
    }

    static ChargingCost cost() throws Exception { return new ChargingCost(settings(), BKK); }

    @Test
    public void moneyHasOneDecimalUnderAHundredThenGroupedWholeNumbers() throws Exception {
        ChargingCost c = cost();
        assertEquals("฿12.3", c.money(12.34));
        assertEquals("฿1,235", c.money(1234.6));
        assertEquals("--", c.money(null));
        assertEquals("--", c.money(Double.NaN));
    }

    @Test
    public void currencySymbolFollowsSettingsAndDefaultsToBaht() throws Exception {
        assertEquals("$", new ChargingCost(new JSONObject("{\"currency\":\"USD\",\"chargers\":[]}"), BKK).symbol());
        assertEquals("฿", new ChargingCost(null, BKK).symbol());
        assertEquals("4.50 ฿/kWh", cost().rateText(4.5));
    }

    @Test
    public void chargerIsThePickElseTheDefaultForTheKind() throws Exception {
        ChargingCost c = cost();
        assertEquals("home", c.chargerOf(new JSONObject("{\"type\":\"ac\"}")).getString("id"));
        assertEquals("volta", c.chargerOf(new JSONObject("{\"type\":\"dc\"}")).getString("id"));
        assertEquals("volta", c.chargerOf(new JSONObject("{\"type\":\"ac\",\"charger\":\"volta\"}")).getString("id"));
        assertEquals("home", c.chargerOf(new JSONObject("{\"type\":null,\"charger\":null}")).getString("id"));
        assertEquals("home", c.chargerOf(new JSONObject("{\"charger\":\"gone\"}")).getString("id"));
        assertNull(new ChargingCost(null, BKK).chargerOf(new JSONObject("{}")));
    }

    @Test
    public void touRateIsOnPeakOnWeekdayDaytimeOnly() throws Exception {
        ChargingCost c = cost();
        JSONObject home = c.chargerOf(new JSONObject("{}"));
        assertEquals(6, c.rateOf(home, session(WED_NOON, false)), 0);
        assertEquals(2.5, c.rateOf(home, session(SAT_NOON, false)), 0);
        assertEquals(2.5, c.rateOf(home, session(WED_NOON - 4 * 3600000L, false)), 0); // 08:00
        assertEquals(2.5, c.rateOf(home, session(WED_NOON, true)), 0); // parked: time unknown
    }

    @Test
    public void chargeCostAddsTheLossOfItsKind() throws Exception {
        ChargingCost c = cost();
        JSONObject dc = new JSONObject("{\"type\":\"dc\",\"start\":" + WED_NOON + ",\"end\":" + WED_NOON + "}");
        assertEquals(10 / 0.95 * 7.5, c.chargeCost(dc, 10.0), 1e-9);
        assertEquals(9 / 0.9 * 6, c.chargeCost(session(WED_NOON, false), 9.0), 1e-9);
        assertNull(c.chargeCost(dc, null));
        assertNull(new ChargingCost(null, BKK).chargeCost(dc, 10.0));
    }

    @Test
    public void drivingIsPricedAtTheAcChargerOffPeak() throws Exception {
        ChargingCost c = cost();
        assertEquals(2.5, c.homeRate(), 0);
        assertEquals(9 / 0.9 * 2.5, c.driveCost(9.0), 1e-9);
        assertNull(c.driveCost(null));
    }

    @Test
    public void energyOfAChargeIsThePackFigureElseThePowerSum() throws Exception {
        assertEquals(12.5, ChargingCost.kwhOf(new JSONObject("{\"kwhAdded\":12.5,\"kwhIn\":11}")), 0);
        assertEquals(11, ChargingCost.kwhOf(new JSONObject("{\"kwhAdded\":null,\"kwhIn\":11}")), 0);
        assertNull(ChargingCost.kwhOf(new JSONObject("{\"kwhAdded\":null,\"kwhIn\":null}")));
    }

    static JSONObject session(long mid, boolean parked) throws Exception {
        return new JSONObject("{\"type\":\"ac\",\"start\":" + mid + ",\"end\":" + mid + ",\"parked\":" + parked + "}");
    }

    @Test
    public void subunitRatesAndSmallCostsFormatAccurately() throws Exception {
        JSONObject audSub = new JSONObject("{\"currency\":\"AUD\",\"priceUnit\":\"sub\",\"chargers\":["
                + "{\"id\":\"home\",\"name\":\"Home\",\"price\":0.05,\"tou\":false,\"peak\":0,\"offPeak\":0}],"
                + "\"ac\":\"home\",\"dc\":\"home\",\"lossAc\":0,\"lossDc\":0}");
        ChargingCost c = new ChargingCost(audSub, BKK);
        assertEquals("5 c/kWh", c.rateText(0.05));
        assertEquals("5.5 c/kWh", c.rateText(0.055));
        assertEquals("29.11 c/kWh", c.rateText(0.2911));
        assertEquals("5.6 c", c.avgPriceValue(0.0556));
        assertEquals("A$0.50", c.money(c.chargeCost(session(WED_NOON, false), 10.0)));
        assertEquals("A$0.05", c.money(0.05));
        assertEquals("A$0.0", c.money(0.0));

        ChargingCost usdSub = new ChargingCost(new JSONObject("{\"currency\":\"USD\",\"priceUnit\":\"sub\",\"chargers\":[]}"), BKK);
        assertEquals("5 \u00a2/kWh", usdSub.rateText(0.05));
        ChargingCost gbpSub = new ChargingCost(new JSONObject("{\"currency\":\"GBP\",\"priceUnit\":\"sub\",\"chargers\":[]}"), BKK);
        assertEquals("7 p/kWh", gbpSub.rateText(0.07));
    }
}

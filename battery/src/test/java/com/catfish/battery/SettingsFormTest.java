package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;

/** The Settings form: settings JSON in, typed text kept as typed, JSON out for BatterySettings to clean. */
public class SettingsFormTest {
    static final String SAVED = "{\"currency\":\"USD\",\"chargers\":["
            + "{\"id\":\"home\",\"name\":\"Home\",\"price\":4.5,\"tou\":true,\"peak\":5.8,\"offPeak\":2.6},"
            + "{\"id\":\"c1\",\"name\":\"PEA VOLTA\",\"price\":7,\"tou\":false,\"peak\":0,\"offPeak\":0}],"
            + "\"ac\":\"home\",\"dc\":\"c1\",\"lossAc\":10,\"lossDc\":0,\"budget\":1500,\"alertSoc\":80,"
            + "\"resetMode\":\"months\",\"resetDay\":31,\"resetMonths\":3,\"resetAnchor\":5}";

    @Test
    public void readsEverySettingIntoTheForm() throws Exception {
        SettingsForm f = SettingsForm.from(new JSONObject(SAVED));
        assertEquals("USD", f.currency);
        assertEquals(2, f.chargers.size());
        assertEquals("Home", f.chargers.get(0).name);
        assertEquals("4.5", f.chargers.get(0).price);
        assertTrue(f.chargers.get(0).tou);
        assertEquals("2.6", f.chargers.get(0).offPeak);
        assertEquals("7", f.chargers.get(1).price);
        assertEquals("", f.chargers.get(1).peak);
        assertEquals("home", f.ac);
        assertEquals("c1", f.dc);
        assertEquals("10", f.lossAc);
        assertEquals("", f.lossDc);
        assertEquals("1500", f.budget);
        assertEquals(80, f.alertSoc);
        assertEquals("months", f.resetMode);
        assertEquals(31, f.resetDay);
        assertEquals(3, f.resetMonths);
    }

    @Test
    public void roundTripsThroughBatterySettingsUnchanged() throws Exception {
        JSONObject out = SettingsForm.from(new JSONObject(SAVED)).toJson();
        JSONObject clean = new JSONObject(BatterySettings.clean(out.toString()));
        JSONObject saved = new JSONObject(BatterySettings.clean(SAVED));
        saved.remove("resetAnchor");
        clean.remove("resetAnchor");
        assertEquals(saved.toString(), clean.toString());
    }

    @Test
    public void monthsOnlyCountInTheEveryNMonthsModeAndShowAtLeastTwo() throws Exception {
        SettingsForm f = SettingsForm.from(new JSONObject(SAVED.replace("\"months\",", "\"monthly\",")
                .replace("\"resetMonths\":3", "\"resetMonths\":1")));
        assertEquals(2, f.resetMonths);
        assertEquals(1, f.toJson().getInt("resetMonths"));
    }

    @Test
    public void alertOutsideTheOfferedStepsReadsAsOff() throws Exception {
        assertEquals(0, SettingsForm.from(new JSONObject(SAVED.replace("\"alertSoc\":80", "\"alertSoc\":83"))).alertSoc);
        assertEquals(0, SettingsForm.from(new JSONObject(SAVED.replace("\"alertSoc\":80", "\"alertSoc\":45"))).alertSoc);
    }

    @Test
    public void typedPricesUseTheFirstNonDigitAsThePoint() {
        assertEquals("4.50", SettingsForm.priceText("4*50"));
        assertEquals("5.9", SettingsForm.priceText("5 *9"));
        assertEquals("1.25", SettingsForm.priceText("1.2.5"));
        assertEquals(4.5, SettingsForm.priceOf("4.50"), 0);
        assertEquals(0, SettingsForm.priceOf(""), 0);
        assertEquals(0, SettingsForm.priceOf("."), 0);
    }

    @Test
    public void addingAndRemovingChargersKeepsTheDefaultsValid() throws Exception {
        SettingsForm f = SettingsForm.from(new JSONObject(SAVED));
        SettingsForm.Charger added = f.addCharger(1_700_000_000_000L);
        assertTrue(added.id.matches("[a-z0-9]{1,24}"));
        assertEquals(3, f.chargers.size());
        assertTrue(f.remove("c1"));
        assertEquals("home", f.dc);
        assertTrue(f.remove("home"));
        assertEquals(added.id, f.ac);
        assertEquals(added.id, f.dc);
        assertFalse(f.remove(added.id)); // keep at least one
        assertEquals(1, f.chargers.size());
    }

    @Test
    public void newChargerIdsAreUnique() throws Exception {
        SettingsForm f = SettingsForm.from(new JSONObject(SAVED));
        String a = f.addCharger(1L).id, b = f.addCharger(1L).id;
        assertFalse(a.equals(b));
    }

    @Test
    public void issuesNameFieldsBatterySettingsWouldChange() throws Exception {
        SettingsForm f = SettingsForm.from(new JSONObject(SAVED));
        f.lossAc = "80";
        f.chargers.get(1).price = "9999999";
        Map<String, String> issues = f.issues();
        assertEquals("Saved as 50", issues.get("lossAc"));
        assertEquals("Saved as 1000000", issues.get("charger.c1.price"));
        assertEquals(2, issues.size());
    }

    @Test
    public void noIssuesForAValidForm() throws Exception {
        assertTrue(SettingsForm.from(new JSONObject(SAVED)).issues().isEmpty());
    }

    @Test
    public void emptyNamesSaveAsNumberedChargers() throws Exception {
        SettingsForm f = SettingsForm.from(new JSONObject(SAVED));
        f.chargers.get(1).name = "  ";
        assertEquals("Saved as Charger 2", f.issues().get("charger.c1.name"));
        JSONArray c = f.toJson().getJSONArray("chargers");
        assertEquals("", c.getJSONObject(1).getString("name"));
    }

    @Test
    public void nextResetLine() throws Exception {
        JSONObject saved = new JSONObject(SAVED);
        SettingsForm f = SettingsForm.from(saved);
        java.util.TimeZone tz = ChargingCostTest.BKK;
        assertEquals("Next: 31 Jan 2027", f.nextResetText(saved, 1801328400000L, tz));
        assertEquals("Scheduled", f.nextResetText(saved, null, tz));
        f.resetDay = 15;
        assertEquals("Next date shows after Save", f.nextResetText(saved, 1801328400000L, tz));
        f.resetMode = "off";
        assertEquals("Off", f.nextResetText(saved, null, tz));
    }

    @Test
    public void summariesShowEachSectionAtAGlance() throws Exception {
        SettingsForm f = SettingsForm.from(new JSONObject(SAVED));
        assertEquals("2 chargers \u00b7 AC Home \u00b7 DC PEA VOLTA", f.chargersSummary());
        assertEquals("USD \u00b7 loss 10 / 0 % \u00b7 alert 80 %", f.prefsSummary());
        assertEquals("Auto reset every 3 months, last day", f.totalsSummary());

        f.dc = "home";
        f.chargers.remove(1);
        f.chargers.get(0).name = " ";
        f.alertSoc = 0;
        f.resetMode = "monthly";
        f.resetDay = 1;
        assertEquals("1 charger \u00b7 Unnamed for AC and DC", f.chargersSummary());
        assertEquals("USD \u00b7 loss 10 / 0 % \u00b7 alert off", f.prefsSummary());
        assertEquals("Auto reset monthly, day 1", f.totalsSummary());
        f.resetMode = "off";
        assertEquals("Auto reset off", f.totalsSummary());
    }

    @Test
    public void subunitModeEntersCentsAndSavesMajorUnits() throws Exception {
        JSONObject saved = new JSONObject("{\"currency\":\"AUD\",\"priceUnit\":\"sub\",\"chargers\":["
                + "{\"id\":\"home\",\"name\":\"Home\",\"price\":0.05,\"tou\":true,\"peak\":0.355,\"offPeak\":0.08}],"
                + "\"ac\":\"home\",\"dc\":\"home\",\"lossAc\":0,\"lossDc\":0,\"budget\":100,\"alertSoc\":0}");
        SettingsForm f = SettingsForm.from(saved);
        assertTrue(f.inSubunit());
        assertEquals("5", f.chargers.get(0).price);
        assertEquals("8", f.chargers.get(0).offPeak);
        assertEquals("35.5", f.chargers.get(0).peak);
        assertEquals("100", f.budget); // budget stays in major units

        f.chargers.get(0).price = "5";
        f.chargers.get(0).offPeak = "5.5";
        f.chargers.get(0).peak = "64.9";
        JSONObject out = f.toJson();
        assertEquals("sub", out.getString("priceUnit"));
        JSONObject home = out.getJSONArray("chargers").getJSONObject(0);
        assertEquals(0.05, home.getDouble("price"), 1e-12);
        assertEquals(0.055, home.getDouble("offPeak"), 1e-12);
        assertEquals(0.649, home.getDouble("peak"), 1e-12);
    }

    @Test
    public void switchingPriceUnitAndCurrencyConvertsPricesWithoutDrift() throws Exception {
        SettingsForm f = SettingsForm.from(new JSONObject("{\"currency\":\"USD\",\"chargers\":["
                + "{\"id\":\"home\",\"name\":\"Home\",\"price\":0.05,\"tou\":false,\"peak\":0,\"offPeak\":0},"
                + "{\"id\":\"dc\",\"name\":\"Fast\",\"price\":0.649,\"tou\":false,\"peak\":0,\"offPeak\":0}]}"));
        assertFalse(f.inSubunit());
        assertTrue(f.setPriceUnit("sub"));
        assertTrue(f.inSubunit());
        assertEquals("5", f.chargers.get(0).price);
        assertEquals("64.9", f.chargers.get(1).price);
        assertEquals("", f.chargers.get(0).peak);

        assertTrue(f.setPriceUnit("main"));
        assertFalse(f.inSubunit());
        assertEquals("0.05", f.chargers.get(0).price);
        assertEquals("0.649", f.chargers.get(1).price);

        assertTrue(f.setPriceUnit("sub"));
        assertTrue(f.setCurrency("THB")); // no subunit -> drops back to main
        assertFalse(f.inSubunit());
        assertEquals("0.05", f.chargers.get(0).price);
        assertFalse(f.setPriceUnit("sub"));
    }

    @Test
    public void issuesReportClampedPricesInTheActiveUnit() throws Exception {
        SettingsForm f = SettingsForm.from(new JSONObject("{\"currency\":\"USD\",\"priceUnit\":\"sub\",\"chargers\":["
                + "{\"id\":\"home\",\"name\":\"Home\",\"price\":0.05,\"tou\":false,\"peak\":0,\"offPeak\":0}]}"));
        f.chargers.get(0).price = "999999999";
        assertEquals("Saved as 100000000", f.issues().get("charger.home.price"));
    }
}

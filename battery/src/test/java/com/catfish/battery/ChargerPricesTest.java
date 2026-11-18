package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Public charger prices: the bundled file, what parse keeps from an untrusted file, and the cost to a target SOC. */
public class ChargerPricesTest {
    @Test
    public void bundledFileHasThaiPricesWithSourceAndDate() throws Exception {
        String json = new String(Files.readAllBytes(Paths.get("src/main/assets/charger-prices.json")), StandardCharsets.UTF_8);
        ChargerPrices p = ChargerPrices.parse(json);
        assertNotNull(p);
        for (String c : new String[] { "THB", "USD", "EUR", "CNY" }) assertNotNull(c, p.forCurrency(c));
        for (ChargerPrices.Region r : p.regions.values()) {
            for (ChargerPrices.Network n : r.networks) {
                assertFalse(n.brand, n.source.isEmpty());
                assertFalse(n.brand, n.date.isEmpty());
                assertTrue(n.brand, n.url.startsWith("https://"));
            }
        }
        ChargerPrices.Network pluz = p.forCurrency("THB").networks.get(0);
        assertEquals("EV Station PluZ", pluz.brand);
        assertTrue(pluz.tou);
        assertEquals(7.40, pluz.peak, 1e-9);
        assertEquals(6.10, pluz.offPeak, 1e-9);
    }

    @Test
    public void parseDropsWhatItCannotTrust() {
        String json = "{\"version\":1,\"updated\":\"x\",\"regions\":["
                + "{\"currency\":\"XXX\",\"networks\":[{\"brand\":\"A\",\"price\":1}]},"
                + "{\"currency\":\"THB\",\"name\":\"Thai\\u0007land\",\"networks\":["
                + "{\"brand\":\"\",\"price\":1},"
                + "{\"brand\":\"Free\",\"price\":0},"
                + "{\"brand\":\"Huge\",\"price\":1e9},"
                + "{\"brand\":\"Half TOU\",\"peak\":8,\"price\":7,\"url\":\"http://x\"},"
                + "{\"brand\":\"TOU\",\"peak\":8,\"offPeak\":6,\"url\":\"https://ok\",\"date\":\"2026-01-02\"}]},"
                + "{\"currency\":\"THB\",\"networks\":[{\"brand\":\"Dup\",\"price\":1}]}]}";
        ChargerPrices p = ChargerPrices.parse(json);
        assertNotNull(p);
        assertEquals("", p.updated);
        assertEquals(1, p.regions.size());
        ChargerPrices.Region th = p.forCurrency("THB");
        assertEquals("Thai land", th.name);
        assertEquals(2, th.networks.size());
        ChargerPrices.Network half = th.networks.get(0);
        assertFalse(half.tou);
        assertEquals(7, half.price, 1e-9);
        assertEquals("", half.url);
        assertTrue(th.networks.get(1).tou);
        assertEquals("2026-01-02", th.networks.get(1).date);
    }

    @Test
    public void parseRefusesBrokenOrOtherVersions() {
        assertNull(ChargerPrices.parse(null));
        assertNull(ChargerPrices.parse("not json"));
        assertNull(ChargerPrices.parse("{\"version\":2,\"regions\":[{\"currency\":\"THB\",\"networks\":[{\"brand\":\"A\",\"price\":1}]}]}"));
        assertNull(ChargerPrices.parse("{\"version\":1,\"regions\":[]}"));
    }

    @Test
    public void chargerNameFitsTheSettingsLimit() {
        ChargerPrices.Network n = new ChargerPrices.Network("EV Station PluZ", "DC 180-480 kW (EV HUB / Ultra-Fast)",
                0, 7.9, 6.6, false, "s", "", "");
        assertTrue(n.chargerName().length() <= 40);
        assertTrue(n.chargerName().startsWith("EV Station PluZ DC 180"));
    }

    @Test
    public void kwhToTargetUsesThePackSizeFromTheCar() {
        // 30 % of a 60 kWh pack is 18 000 Wh left; 30 -> 80 % is half the pack
        assertEquals(30.0, ChargerPrices.kwhTo(30.0, 18000.0, 80), 1e-9);
        assertEquals(0.0, ChargerPrices.kwhTo(85.0, 51000.0, 80), 1e-9);
        assertNull(ChargerPrices.kwhTo(null, 18000.0, 80));
        assertNull(ChargerPrices.kwhTo(30.0, null, 80));
        assertNull(ChargerPrices.kwhTo(2.0, 1200.0, 80));      // SOC too low to size the pack
        assertNull(ChargerPrices.kwhTo(50.0, 2000.0, 80));     // 4 kWh is no car pack
    }

    @Test
    public void kwhToFromAPollNeedsALiveStream() throws Exception {
        String poll = "{\"streamConnected\":%s,\"soc\":30,\"energyWh\":18000}";
        assertEquals(30.0, ChargerPrices.kwhTo(new org.json.JSONObject(String.format(poll, "true")), 80), 1e-9);
        assertNull(ChargerPrices.kwhTo(new org.json.JSONObject(String.format(poll, "false")), 80));
        assertNull(ChargerPrices.kwhTo((org.json.JSONObject) null, 80));
        assertNull(ChargerPrices.kwhTo(new org.json.JSONObject("{\"streamConnected\":true,\"soc\":null,\"energyWh\":18000}"), 80));
    }

    @Test
    public void dateTextIsShort() {
        assertEquals("1 Sep 2026", ChargerPrices.dateText("2026-09-01"));
        assertEquals("", ChargerPrices.dateText("2026-13-01"));
        assertEquals("", ChargerPrices.dateText(""));
    }

    @Test
    public void costAddsTheChargingLoss() {
        assertEquals(30 * 7.4, ChargerPrices.cost(30.0, 7.4, 0), 1e-9);
        assertEquals(30 / 0.9 * 7.4, ChargerPrices.cost(30.0, 7.4, 10), 1e-9);
        assertNull(ChargerPrices.cost(null, 7.4, 0));
        assertNull(ChargerPrices.cost(30.0, 0, 0));
    }

    @Test
    public void regionChoicesCarryTheirCurrencyAndOtherCurrenciesFollow() throws Exception {
        String json = new String(Files.readAllBytes(Paths.get("src/main/assets/charger-prices.json")), StandardCharsets.UTF_8);
        ChargerPrices p = ChargerPrices.parse(json);
        java.util.List<String[]> choices = ChargerPrices.regionChoices(p);
        assertEquals("THB", choices.get(0)[0]);
        assertEquals("Thailand \u00b7 THB", choices.get(0)[1]);
        assertEquals(ChargingCost.CURRENCY.size(), choices.size()); // every currency once, listed regions first
        String[] last = choices.get(choices.size() - 1);
        assertNull(p.forCurrency(last[0]));
        assertEquals("Other \u00b7 " + last[0], last[1]);
        int n = p.forCurrency("THB").networks.size();
        assertEquals("Thailand \u00b7 THB \u00b7 " + n + " public networks", ChargerPrices.regionSummary(p, "THB"));
        assertEquals("Other \u00b7 " + last[0] + " \u00b7 no network list", ChargerPrices.regionSummary(p, last[0]));
        assertEquals("Other \u00b7 THB \u00b7 no network list", ChargerPrices.regionSummary(null, "THB"));
        assertEquals(ChargingCost.CURRENCY.size(), ChargerPrices.regionChoices(null).size());
    }
}

package com.catfish.battery;

import static org.junit.Assert.assertEquals;

import java.util.TimeZone;

import org.json.JSONObject;
import org.junit.Test;

/** Facts rows and Pack header counters from BatteryDataHub.dataJson() and settingsJson(). */
public class StatusFactsTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static StatusFacts facts(String data, String settings) throws Exception {
        return StatusFacts.from(new JSONObject(data), settings == null ? null : new JSONObject(settings), UTC);
    }

    @Test
    public void offlineSnapshotShowsDashesNotZeros() throws Exception {
        StatusFacts f = facts("{\"streamConnected\":false,\"cells\":[]}", null);
        assertEquals("--", f.cellMax);
        assertEquals("--", f.cellMin);
        assertEquals("--", f.cellDelta);
        assertEquals(StatusFormat.GOOD, f.deltaLevel);
        assertEquals("--", f.tempMax);
        assertEquals("--", f.tempMin);
        assertEquals("--", f.tempSpread);
        assertEquals("--", f.used);
        assertEquals("--", f.charged);
        assertEquals("--", f.regen);
        assertEquals(false, f.regenReal); // a missing total is never green
        assertEquals("--", f.cost);
        assertEquals("no price set", f.costNote);
        assertEquals("from boot", f.since);
        assertEquals("0/118 cells", f.cellCount);
        assertEquals("0/12 sensors", f.sensorCount);
        assertEquals("--", f.legendVmin);
        assertEquals("--", f.legendTmax);
    }

    @Test
    public void cellExtremesComeFromTheSamples() throws Exception {
        StatusFacts f = facts("{\"streamConnected\":true,\"cellMaxV\":3.9,\"cellMinV\":3.1,\"cells\":["
                + "{\"cell\":1,\"millivolts\":3700},{\"cell\":2,\"millivolts\":3725},{\"cell\":3,\"millivolts\":3690},"
                + "{\"cell\":200,\"millivolts\":1},{\"cell\":4,\"millivolts\":9000}]}", null);
        assertEquals("3725 mV", f.cellMax);
        assertEquals("3690 mV", f.cellMin);
        assertEquals("35 mV", f.cellDelta);
        assertEquals(StatusFormat.WARN, f.deltaLevel);
        assertEquals("3/118 cells", f.cellCount);
        assertEquals("3690 mV", f.legendVmin);
        assertEquals("3725 mV", f.legendVmax);
    }

    @Test
    public void cellExtremesFallBackToTheBmsMaxMinWhileLive() throws Exception {
        StatusFacts f = facts("{\"streamConnected\":true,\"cellMaxV\":3.712,\"cellMinV\":3.65,\"cells\":[]}", null);
        assertEquals("3712 mV", f.cellMax);
        assertEquals("3650 mV", f.cellMin);
        assertEquals("62 mV", f.cellDelta);
        assertEquals(StatusFormat.DANGER, f.deltaLevel);
        f = facts("{\"streamConnected\":false,\"cellMaxV\":3.712,\"cellMinV\":3.65,\"cells\":[]}", null);
        assertEquals("--", f.cellMax);
    }

    @Test
    public void sensorsOnlyWhileLive() throws Exception {
        String temps = "\"cellTemps\":[{\"sensor\":1,\"tempC\":31},{\"sensor\":5,\"tempC\":27},{\"sensor\":13,\"tempC\":20}]";
        StatusFacts f = facts("{\"streamConnected\":true,\"cells\":[]," + temps + "}", null);
        assertEquals("31 \u00b0C", f.tempMax);
        assertEquals("27 \u00b0C", f.tempMin);
        assertEquals("4 \u00b0C", f.tempSpread);
        assertEquals("2/12 sensors", f.sensorCount);
        assertEquals("27 \u00b0C", f.legendTmin);
        f = facts("{\"streamConnected\":false,\"cells\":[]," + temps + "}", null);
        assertEquals("--", f.tempMax);
        assertEquals("0/12 sensors", f.sensorCount);
    }

    @Test
    public void totalsAndCostAtTheHomeRate() throws Exception {
        String data = "{\"streamConnected\":true,\"cells\":[],\"totals\":{\"since\":0,\"usedWh\":12345,"
                + "\"chargedWh\":10000,\"regenWh\":2345}}";
        String settings = "{\"saved\":true,\"settings\":{\"currency\":\"USD\",\"ac\":\"home\",\"lossAc\":10,"
                + "\"chargers\":[{\"id\":\"fast\",\"price\":9,\"tou\":false},{\"id\":\"home\",\"price\":0.3,\"tou\":false}]}}";
        StatusFacts f = facts(data, settings);
        assertEquals("12.35 kWh", f.used);
        assertEquals("10.00 kWh", f.charged);
        assertEquals("2.35 kWh", f.regen);
        assertEquals(true, f.regenReal);
        // (12.345 - 2.345) kWh / 0.9 * 0.3 = 3.33
        assertEquals("$3.3", f.cost);
        assertEquals("0.30 $/kWh", f.costNote);
        assertEquals("from boot", f.since);
    }

    @Test
    public void touChargerUsesItsOffPeakRate() throws Exception {
        String data = "{\"streamConnected\":true,\"cells\":[],\"totals\":{\"since\":1,\"usedWh\":1000,"
                + "\"chargedWh\":null,\"regenWh\":0}}";
        String settings = "{\"settings\":{\"currency\":\"THB\",\"ac\":\"home\",\"lossAc\":0,"
                + "\"chargers\":[{\"id\":\"home\",\"price\":9,\"tou\":true,\"peak\":5,\"offPeak\":2.5}]}}";
        StatusFacts f = facts(data, settings);
        assertEquals("--", f.charged);
        assertEquals(false, f.regenReal); // 0 Wh of regen stays plain
        assertEquals("\u0e3f2.5", f.cost);
        assertEquals("2.50 \u0e3f/kWh", f.costNote);
        assertEquals("since 1 Jan 00:00", f.since);
    }

    @Test
    public void zeroPriceMeansNoCost() throws Exception {
        String data = "{\"streamConnected\":true,\"cells\":[],\"totals\":{\"since\":0,\"usedWh\":1000,\"regenWh\":0}}";
        String settings = "{\"settings\":{\"currency\":\"THB\",\"ac\":\"home\",\"lossAc\":0,"
                + "\"chargers\":[{\"id\":\"home\",\"price\":0,\"tou\":false}]}}";
        StatusFacts f = facts(data, settings);
        assertEquals("--", f.cost);
        assertEquals("no price set", f.costNote);
    }
}

package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The swipe card's pages: range and energy, pack power, extremes. Missing readings show "--", never 0. */
public class SchematicDataTest {
    private static JSONObject live() throws Exception {
        JSONArray cells = new JSONArray();
        for (int c = 1; c <= 118; c++) cells.put(new JSONObject().put("cell", c).put("millivolts", c == 47 ? 3312 : c == 101 ? 3297 : 3305));
        JSONArray temps = new JSONArray();
        int[] t = {33, 33, 32, 34, 30, 28, 34, 33, 33, 32, 36, 31};
        for (int s = 1; s <= 12; s++) temps.put(new JSONObject().put("sensor", s).put("tempC", t[s - 1]));
        return new JSONObject().put("streamConnected", true).put("soc", 74.5).put("rangeKm", 368).put("energyWh", 40820)
                .put("battVoltageV", 380.5).put("battCurrentA", 96.7).put("cells", cells).put("cellTemps", temps);
    }

    @Test public void rangePageUsesTheCarsRangeAndEnergy() throws Exception {
        SchematicData d = new SchematicData(live());
        assertEquals("368", d.rangeKm);
        assertEquals("40.8 kWh", d.energyLeft);
        assertEquals(.745, d.energyShare, 1e-9);
    }

    @Test public void powerPageSignsTheFlowAndNamesTheState() throws Exception {
        SchematicData d = new SchematicData(live());
        assertEquals(36.79, d.packKw, .01);
        assertEquals("+36.8", d.packKwText);
        assertEquals("In use", d.powerState);
        assertEquals("In use \u00b7 36.8 kW", d.loadsTitle); // the Loads card's pill: the card title already says Loads
        SchematicData regen = new SchematicData(live().put("battCurrentA", -40));
        assertEquals("\u221215.2", regen.packKwText);
        assertEquals("Regen", regen.powerState);
        SchematicData idle = new SchematicData(live().put("battCurrentA", 0.2));
        assertEquals("Idle", idle.powerState);
    }

    @Test public void extremesPageNamesTheCellAndTheSensors() throws Exception {
        SchematicData d = new SchematicData(live());
        assertEquals(47, d.highCell);
        assertEquals("3.312 V", d.highCellV);
        assertEquals(11, d.hotSensor);   // BMS board 36 °C
        assertEquals(6, d.coolSensor);   // coolant in 28 °C
    }

    @Test public void noDataShowsDashes() throws Exception {
        SchematicData d = new SchematicData(new JSONObject().put("streamConnected", false));
        assertEquals("--", d.rangeKm);
        assertEquals("--", d.energyLeft);
        assertTrue(Double.isNaN(d.energyShare));
        assertEquals("--", d.packKwText);
        assertEquals("", d.powerState);
        assertEquals(0, d.highCell);
        assertEquals("--", d.highCellV);
        assertEquals(0, d.hotSensor);
        assertEquals(0, d.coolSensor);
    }
}

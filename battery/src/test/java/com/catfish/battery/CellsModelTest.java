package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.json.JSONObject;
import org.junit.Test;

/** The native Cells view: 118 cell tiles in four modules and 12 sensor tiles, banded like the WebView Cells view. */
public class CellsModelTest {
    private static CellsModel model(String json) throws Exception {
        return CellsModel.from(new JSONObject(json));
    }

    private static CellsModel.Tile cell(CellsModel m, int n) {
        for (CellsModel.Module mod : m.modules) {
            for (CellsModel.Tile t : mod.tiles) if (t.label.equals(String.valueOf(n))) return t;
        }
        throw new AssertionError("no cell " + n);
    }

    @Test
    public void cellTilesCarryTheirNumberAndDeviation() throws Exception {
        CellsModel m = model("{\"streamConnected\":true,\"cells\":[{\"cell\":1,\"millivolts\":3280},"
                + "{\"cell\":2,\"millivolts\":3282},{\"cell\":3,\"millivolts\":3295}]}");
        assertEquals(1, cell(m, 1).cell);
        assertEquals(-2, cell(m, 1).deviation, 1e-9); // median 3282
        assertEquals(13, cell(m, 3).deviation, 1e-9);
        org.junit.Assert.assertTrue(Double.isNaN(cell(m, 4).deviation));
        assertEquals(0, m.sensors.get(0).cell);
    }

    @Test
    public void modulesInTheWebViewOrder() throws Exception {
        CellsModel m = model("{\"streamConnected\":false,\"cells\":[]}");
        assertEquals(4, m.modules.size());
        String[] names = { "P1", "P4", "P2", "P3" };
        int[] sizes = { 30, 29, 30, 29 };
        int total = 0;
        for (int i = 0; i < 4; i++) {
            assertEquals(names[i], m.modules.get(i).name);
            assertEquals(sizes[i], m.modules.get(i).tiles.size());
            total += sizes[i];
        }
        assertEquals(118, total);
        assertEquals("cells 1\u201330", m.modules.get(0).head);
        assertEquals("cells 90\u2013118", m.modules.get(1).head);
        assertEquals(12, m.sensors.size());
        assertEquals("S1 \u00b7 P1", m.sensors.get(0).label);
        assertEquals("S8 \u00b7 B\u2212", m.sensors.get(7).label);
        assertEquals("S12 \u00b7 Air", m.sensors.get(11).label);
        for (CellsModel.Tile t : m.sensors) {
            assertEquals("--", t.text);
            assertEquals(CellsModel.WAIT, t.band);
        }
        assertEquals("--", cell(m, 1).text);
        assertEquals(CellsModel.WAIT, cell(m, 1).band);
    }

    @Test
    public void cellBandsByDistanceFromTheMedian() throws Exception {
        // LFP cells: median of 3300,3304,3305,3312,3320 is 3305
        CellsModel m = model("{\"streamConnected\":true,\"cells\":[{\"cell\":1,\"millivolts\":3300},"
                + "{\"cell\":2,\"millivolts\":3304},{\"cell\":3,\"millivolts\":3305},{\"cell\":4,\"millivolts\":3312},"
                + "{\"cell\":95,\"millivolts\":3320}]}");
        assertEquals(CellsModel.WARN, cell(m, 1).band);
        assertEquals("3300", cell(m, 1).text);
        assertEquals(CellsModel.GOOD, cell(m, 2).band);
        assertEquals(CellsModel.GOOD, cell(m, 3).band);
        assertEquals(CellsModel.WARN, cell(m, 4).band);
        assertEquals(CellsModel.DANGER, cell(m, 95).band);
        assertEquals("\u25bc", cell(m, 1).mark);
        assertEquals("\u25b2", cell(m, 95).mark);
        assertEquals("", cell(m, 3).mark);
        assertEquals("cells 1\u201330 \u00b7 3300\u20133312 mV \u00b7 avg 3305", m.modules.get(0).head);
        assertEquals("cells 90\u2013118 \u00b7 3320\u20133320 mV \u00b7 avg 3320", m.modules.get(1).head);
    }

    @Test
    public void cellAboveTheLfpLimitIsDangerEvenAtTheMedian() throws Exception {
        CellsModel m = model("{\"streamConnected\":true,\"cells\":[{\"cell\":1,\"millivolts\":3660},"
                + "{\"cell\":2,\"millivolts\":3660}]}");
        assertEquals(CellsModel.DANGER, cell(m, 1).band);
    }

    @Test
    public void outOfBoundsCellIsDanger() throws Exception {
        CellsModel m = model("{\"streamConnected\":true,\"cells\":[{\"cell\":1,\"millivolts\":2700},"
                + "{\"cell\":2,\"millivolts\":2700},{\"cell\":3,\"millivolts\":2700}]}");
        assertEquals(CellsModel.DANGER, cell(m, 2).band);
        assertEquals("no spread, no markers", "", cell(m, 1).mark);
    }

    @Test
    public void firstExtremeInModuleOrderGetsTheMarker() throws Exception {
        // P4 (90-118) is drawn before P2 (31-60), so cell 90 is the "first" highest
        CellsModel m = model("{\"streamConnected\":true,\"cells\":[{\"cell\":31,\"millivolts\":3310},"
                + "{\"cell\":90,\"millivolts\":3310},{\"cell\":5,\"millivolts\":3300},{\"cell\":6,\"millivolts\":3300}]}");
        assertEquals("\u25b2", cell(m, 90).mark);
        assertEquals("", cell(m, 31).mark);
        assertEquals("\u25bc", cell(m, 5).mark);
        assertEquals("", cell(m, 6).mark);
    }

    @Test
    public void sensorBandsAndMarkersWhileLive() throws Exception {
        CellsModel m = model("{\"streamConnected\":true,\"cells\":[],\"cellTemps\":[{\"sensor\":1,\"tempC\":34},"
                + "{\"sensor\":2,\"tempC\":35},{\"sensor\":12,\"tempC\":43}]}");
        CellsModel.Tile s1 = m.sensors.get(0), s2 = m.sensors.get(1), s12 = m.sensors.get(11);
        assertNotNull(s1);
        assertEquals("34 \u00b0C", s1.text);
        assertEquals(CellsModel.GOOD, s1.band);
        assertEquals(CellsModel.WARN, s2.band);
        assertEquals(CellsModel.DANGER, s12.band);
        assertEquals("\u25bc", s1.mark);
        assertEquals("\u25b2", s12.mark);
        assertEquals(CellsModel.WAIT, m.sensors.get(4).band);

        m = model("{\"streamConnected\":false,\"cells\":[],\"cellTemps\":[{\"sensor\":1,\"tempC\":34}]}");
        assertEquals("--", m.sensors.get(0).text);
    }
}

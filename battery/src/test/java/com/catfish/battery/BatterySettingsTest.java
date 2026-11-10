package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class BatterySettingsTest {

    private static JSONObject clean(String raw) throws Exception {
        return new JSONObject(BatterySettings.clean(raw));
    }

    @Test
    public void keepsWhatThePageSentWhenItIsValid() throws Exception {
        JSONObject s = clean("{\"currency\":\"USD\",\"chargers\":["
                + "{\"id\":\"home\",\"name\":\" บ้าน \",\"price\":4.5,\"tou\":true,\"peak\":5.8,\"offPeak\":2.6},"
                + "{\"id\":\"c1\",\"name\":\"PEA VOLTA\",\"price\":7.5}],"
                + "\"ac\":\"home\",\"dc\":\"c1\",\"lossAc\":10,\"lossDc\":5,\"budget\":1500,\"alertSoc\":80}");
        assertEquals("USD", s.getString("currency"));
        JSONArray chargers = s.getJSONArray("chargers");
        assertEquals(2, chargers.length());
        assertEquals("บ้าน", chargers.getJSONObject(0).getString("name"));
        assertEquals(true, chargers.getJSONObject(0).getBoolean("tou"));
        assertEquals(2.6, chargers.getJSONObject(0).getDouble("offPeak"), 0);
        assertEquals(7.5, chargers.getJSONObject(1).getDouble("price"), 0);
        assertEquals("c1", s.getString("dc"));
        assertEquals(10, s.getDouble("lossAc"), 0);
        assertEquals(1500, s.getDouble("budget"), 0);
        assertEquals(80, s.getInt("alertSoc"));
    }

    @Test
    public void rebuildsAnythingBrokenOrOutOfRange() throws Exception {
        JSONObject s = clean("{\"currency\":\"XYZ\",\"chargers\":["
                + "{\"id\":\"BAD ID\",\"name\":\"x\"},{\"id\":\"a\",\"name\":\"\",\"price\":-3},{\"id\":\"a\",\"name\":\"dup\"},"
                + "{\"id\":\"b\",\"name\":\"" + new String(new char[60]).replace('\0', 'n') + "\",\"price\":\"7.25\"}],"
                + "\"ac\":\"gone\",\"dc\":\"b\",\"lossAc\":80,\"lossDc\":-1,\"budget\":\"lots\",\"alertSoc\":250,\"extra\":1}");
        assertEquals("THB", s.getString("currency"));
        JSONArray chargers = s.getJSONArray("chargers");
        assertEquals(2, chargers.length());
        assertEquals("Charger 1", chargers.getJSONObject(0).getString("name"));
        assertEquals(0, chargers.getJSONObject(0).getDouble("price"), 0);
        assertEquals(40, chargers.getJSONObject(1).getString("name").length());
        assertEquals(7.25, chargers.getJSONObject(1).getDouble("price"), 0);
        // a default that points nowhere falls back on the first charger
        assertEquals("a", s.getString("ac"));
        assertEquals("b", s.getString("dc"));
        assertEquals(50, s.getDouble("lossAc"), 0);
        assertEquals(0, s.getDouble("lossDc"), 0);
        assertEquals(0, s.getDouble("budget"), 0);
        assertEquals(100, s.getInt("alertSoc"));
        assertFalse(s.has("extra"));
    }

    @Test
    public void nothingOrGarbageGivesOneFreeHomeCharger() throws Exception {
        for (String raw : new String[]{null, "", "not json", "{\"chargers\":[]}"}) {
            JSONObject s = clean(raw);
            assertEquals(1, s.getJSONArray("chargers").length());
            assertEquals("home", s.getString("ac"));
            assertEquals("home", s.getString("dc"));
            assertEquals(0, s.getInt("alertSoc"));
        }
        assertEquals(80, BatterySettings.alertSoc(BatterySettings.clean("{\"alertSoc\":80}")));
    }
}

package com.catfish.battery;

import org.json.JSONObject;
import org.junit.Test;

import static com.catfish.battery.SchematicReadings.Band.*;
import static org.junit.Assert.*;

public class SchematicReadingsTest {
    @Test public void medianBandsAndSpreadUseInclusiveThresholds() {
        SchematicReadings.Cells good = SchematicReadings.Cells.of(new double[]{3300,3302,Double.NaN});
        assertEquals(3301,good.median(),0);
        assertEquals(-1,good.minimum());
        assertEquals(-1,good.maximum());
        SchematicReadings.Cells spread = SchematicReadings.Cells.of(new double[]{3303,3300,3303,3300});
        assertEquals(3301.5,spread.median(),0);
        assertEquals(1,spread.minimum());
        assertEquals(0,spread.maximum());
        assertEquals(GOOD,SchematicReadings.cellBand(3302,3300));
        assertEquals(WARN,SchematicReadings.cellBand(3292,3300));
        assertEquals(DANGER,SchematicReadings.cellBand(3309,3300));
        assertEquals(DANGER,SchematicReadings.cellBand(2799,2799));
        assertEquals(WAIT,SchematicReadings.cellBand(Double.NaN,3300));
        assertEquals(GOOD,SchematicReadings.sensorBand(34));
        assertEquals(WARN,SchematicReadings.sensorBand(35));
        assertEquals(WARN,SchematicReadings.sensorBand(42));
        assertEquals(DANGER,SchematicReadings.sensorBand(43));
        assertEquals(0xff258360,SchematicReadings.shade(0xff3ddba0,.6f));
    }

    @Test public void formatsTooltipsAndUnavailableValues() {
        assertEquals("Cell 118 · 3.301 V",SchematicReadings.cellTip(118,3301));
        assertEquals("Cell 1 · waiting",SchematicReadings.cellTip(1,Double.NaN));
        assertEquals("T5 · Coolant out · −2 °C",SchematicReadings.sensorTip(5,"Coolant out",-2));
        assertEquals("T12 · Air · not seen yet",SchematicReadings.sensorTip(12,"Air",Double.NaN));
        assertEquals("--",SchematicReadings.soh(Double.NaN,"becm"));
        assertEquals("96.44 %",SchematicReadings.soh(96.44,"becm"));   // BECM DID 496D: 0.01 % steps
        assertEquals("96.5 %",SchematicReadings.soh(96.5,"canspi"));   // canspi EgyCdn: 0.5 % steps
        assertEquals("96.5 %",SchematicReadings.soh(96.5,null));
        assertEquals("3.6 kWh in · 11 h 55 min left",SchematicReadings.chargeNote(3.6,715));
    }

    @Test public void systemUsesLiveReadingsAndKeepsRealZero() throws Exception {
        JSONObject reading = new JSONObject("""
                {"streamConnected":true,"soc":0,"soh":{"pct":96.5},"battVoltageV":400,"battCurrentA":-2.75,
                 "cellTemps":[{"sensor":1,"tempC":0}],"cells":[{"cell":1,"millivolts":3300}],
                 "flow":{"obcHandle":2,"chargeKwh":3.6,"chargeEtaMin":715}}
                """);
        SchematicData data = new SchematicData(reading);
        assertEquals("0.0%",data.soc);
        assertEquals("1.1 kW IN",data.packPower);
        assertEquals("1.1 kW",data.chargePower);
        assertEquals("3.6 kWh in · 11 h 55 min left",data.chargeNote);
        assertTrue(data.charging);
        assertFalse(data.hvOn);
        assertEquals(0,data.temperatures[0],0);
        assertEquals(3300,data.cells[0],0);
        assertTrue(Double.isNaN(data.cells[1]));

        SchematicData offline = new SchematicData(reading.put("streamConnected",false));
        assertEquals("--",offline.soc);
        assertEquals("-- · --",offline.packVA);
        assertEquals("96.5 %",offline.soh);
        assertFalse(offline.sohReading);
        assertTrue(new SchematicData(new JSONObject("{\"soh\":{\"pct\":null,\"state\":\"reading\"}}")).sohReading);
        assertFalse(offline.plugged);
        assertEquals(3300,offline.cells[0],0);
        assertTrue(Double.isNaN(offline.temperatures[0]));
    }

    @Test public void powerLineReadsVoltsTimesAmpsEqualsPower() {
        assertEquals("391.5 V \u00d7 2.7 A = 1.1 kW OUT",SchematicReadings.powerLine(391.5,2.7));
        assertEquals("391.5 V \u00d7 17.9 A = 7.0 kW IN",SchematicReadings.powerLine(391.5,-17.9));
        assertEquals("-- \u00d7 -- = --",SchematicReadings.powerLine(Double.NaN,Double.NaN));
    }

    @Test public void chargingCardShowsTargetEtaAndAddedEnergy() throws Exception {
        JSONObject reading = new JSONObject("""
                {"streamConnected":true,"soc":83,"battVoltageV":391.5,"battCurrentA":-17.9,
                 "flow":{"obcHandle":2,"obcV":230,"obcA":30.4,"chargeKwh":3.6,"chargeEtaMin":715,"targetSoc":100}}
                """);
        SchematicData d = new SchematicData(reading);
        assertTrue(d.charging);
        assertEquals("Charging \u00b7 AC 7.0 kW",d.chargeTitle);
        assertEquals("Full in 11 h 55 min",d.chargeEta);
        assertEquals("83 % \u2192 100 %",d.socTarget);
        assertEquals(.83,d.socProgress,1e-9);
        assertEquals("3.6 kWh",d.chargeAdded);
        assertEquals(SchematicReadings.INLET_AC,d.inlet);
        assertEquals("AC Type 2",d.inletName);
        assertEquals("7.0 kW",d.inletValue);
        reading.getJSONObject("flow").put("targetSoc",80).put("dcHandle",2).remove("obcHandle");
        SchematicData dc = new SchematicData(reading);
        assertEquals("To 80 % in 11 h 55 min",dc.chargeEta);
        assertEquals(SchematicReadings.INLET_DC,dc.inlet);
        assertEquals("CCS2",dc.inletName);
        SchematicData off = new SchematicData(null);
        assertEquals("",off.chargeEta);
        assertEquals("--",off.chargeAdded);
        assertEquals("HV PACK \u00b7 400 V",off.kicker);
    }

    @Test public void inletShowsOnlyWhileEnergyFlowsAndPowerOutIsV2l() throws Exception {
        JSONObject plugIdle = new JSONObject("""
                {"streamConnected":true,"battVoltageV":391,"battCurrentA":0.2,"flow":{"obcHandle":2}}
                """);
        assertEquals(SchematicReadings.INLET_NONE,new SchematicData(plugIdle).inlet); // plugged, full: no icon
        assertEquals(SchematicReadings.INLET_NONE,new SchematicData(new JSONObject("{\"streamConnected\":true}")).inlet);
        JSONObject v2l = new JSONObject("""
                {"streamConnected":true,"battVoltageV":391,"battCurrentA":4.8,
                 "flow":{"obcHandle":2,"v2lV":230,"v2lA":7.8}}
                """);
        SchematicData out = new SchematicData(v2l);
        assertEquals(SchematicReadings.INLET_V2L,out.inlet);
        assertEquals("AC Type 2",out.inletName);
        assertEquals("1.8 kW out",out.inletValue);
        assertEquals("Power out \u00b7 V2L 1.8 kW",out.loadsTitle);
        assertFalse(out.charging);
        assertEquals(SchematicReadings.INLET_V2L,SchematicReadings.inlet(v2l));
    }
}

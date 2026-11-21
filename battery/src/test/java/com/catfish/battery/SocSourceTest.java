package com.catfish.battery;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class SocSourceTest {
    private static final int HV_BATT_SOC = 0x126005BA, EV_PERCENT = 0x2160730E, DISPLAYED = 0x21608057;

    private static int pick(Map<Integer, Double> values) {
        return VehicleBatteryReader.pickSoc(values::get);
    }

    @Test public void bmsSocWinsOverTheWholeNumberVhalPercentages() {
        // build 244520, 2026-11-21: VHAL 80.0 / displayed 80.0 / HvBattSoc 80.95
        Map<Integer, Double> car = new HashMap<>();
        car.put(EV_PERCENT, 80.0); car.put(DISPLAYED, 80.0); car.put(HV_BATT_SOC, 80.95);
        int at = pick(car);
        assertEquals(HV_BATT_SOC, VehicleBatteryReader.SOC_IDS[at]);
        assertEquals("HvBattSoc", VehicleBatteryReader.SOC_SOURCES[at]);
    }

    @Test public void vhalPercentagesAreTheFallbackWhileApvpIsDown() {
        Map<Integer, Double> car = new HashMap<>();
        car.put(EV_PERCENT, 80.0); car.put(DISPLAYED, 79.0);
        assertEquals("TYPE_EV_BATTERY_PERCENTAGE", VehicleBatteryReader.SOC_SOURCES[pick(car)]);
        car.remove(EV_PERCENT);
        assertEquals("DispHvBattLvlOfChrg", VehicleBatteryReader.SOC_SOURCES[pick(car)]);
        car.clear();
        assertEquals(-1, pick(car));
    }
}

package com.catfish.battery;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class FlowGeometryTest {
    @Test public void mappingFitsBothAxesAndKeepsOuterRoutesVisible() {
        float scale = FlowGeometry.scale(2780, 470);
        assertEquals(1, scale, 0);
        assertEquals(795, FlowGeometry.originX(2780, scale), 0);
        scale = FlowGeometry.scale(1390, 940);
        assertEquals(100, FlowGeometry.originX(1390, scale), 0);
        assertEquals(235, FlowGeometry.originY(940, scale), 0);
    }

    @Test public void dotsTravelAlongTheStaircaseWires() {
        float[] point = new float[2];
        FlowGeometry.position(FlowGeometry.DC, 0.5f, point);
        assertArrayEquals(new float[]{642, 358}, point, 0);
        FlowGeometry.position(FlowGeometry.HV, 1, point);
        assertArrayEquals(new float[]{930, 192}, point, 0);
        FlowGeometry.position(FlowGeometry.LINK, 0, point);
        assertArrayEquals(new float[]{1105, 108}, point, 0);
    }

    @Test public void chargingAlsoRunsThePackWireAndLinkTowardsThePack() {
        int charging = FlowGeometry.activeRoutes(-17.9, true, true);
        int drawn = FlowGeometry.drawn(charging);
        assertEquals((1 << FlowGeometry.HV) | (1 << FlowGeometry.DC) | (1 << FlowGeometry.LV)
                | (1 << FlowGeometry.CHARGER) | (1 << FlowGeometry.LINK), drawn);
        assertEquals(1 << FlowGeometry.LINK | 1 << FlowGeometry.HV,
                FlowGeometry.drawn(FlowGeometry.activeRoutes(5, false, false)));
        assertEquals(0, FlowGeometry.drawn(0));
    }

    @Test public void routingUsesOriginalThresholdsAndPlugHandles() {
        assertEquals(0, FlowGeometry.activeRoutes(0.5, false, false));
        assertEquals(0, FlowGeometry.activeRoutes(-1001, true, false));
        assertEquals(6, FlowGeometry.activeRoutes(Double.NaN, false, true));
        assertEquals(1, FlowGeometry.activeRoutes(-5, FlowGeometry.plugged(4, 0), false));
        assertEquals(8, FlowGeometry.activeRoutes(-5, FlowGeometry.plugged(Double.NaN, 3), false));
        assertEquals(7, FlowGeometry.activeRoutes(5, true, true));
    }
}

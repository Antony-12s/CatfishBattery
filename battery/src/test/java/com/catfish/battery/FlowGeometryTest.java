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

    @Test public void dotsTravelAtConstantSpeedAcrossUnequalSegments() {
        float[] point = new float[2];
        FlowGeometry.position(FlowGeometry.LV, 0.5f, point);
        assertArrayEquals(new float[]{1272, 383}, point, 0);
        FlowGeometry.position(FlowGeometry.HV, 1, point);
        assertArrayEquals(new float[]{900, 210}, point, 0);
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

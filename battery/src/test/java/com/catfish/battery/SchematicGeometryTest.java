package com.catfish.battery;

import org.junit.Test;

import static org.junit.Assert.*;

public class SchematicGeometryTest {
    @Test public void fitsBothAxesAndInvertsFlowCoordinates() {
        SchematicGeometry.Fit wide = SchematicGeometry.fit(3000,940);
        assertEquals(2f,wide.scale(),0f);
        assertEquals(110f,wide.x(-100),0f);
        assertEquals(2890f,wide.x(1290),0f);
        assertEquals(0f,wide.y(0),0f);
        SchematicGeometry.Fit tall = SchematicGeometry.fit(695,1000);
        assertEquals(.5f,tall.scale(),0f);
        assertEquals(382.5f,tall.y(0),0f);
        assertEquals(865f,tall.designX(tall.x(865)),0f);
        assertEquals(341f,tall.designY(tall.y(341)),0f);
        // 2026-11-19 layout: Loads and 12 V on x 884, DC-DC 574-856, inlet ends at 856
        assertArrayEquals(new float[]{764,192,884,192},SchematicGeometry.flowPath("hv"),0f);
        assertArrayEquals(new float[]{430,392,574,392},SchematicGeometry.flowPath("dc"),0f);
        assertArrayEquals(new float[]{856,392,884,392},SchematicGeometry.flowPath("lv"),0f);
        assertArrayEquals(new float[]{856,298,884,298},SchematicGeometry.flowPath("chg"),0f);
    }

    @Test public void projectsOriginalFacesAndAllCellIds() {
        assertEquals(119,SchematicGeometry.solids().size());
        assertEquals(119,SchematicGeometry.solids().stream().map(SchematicGeometry.Box::cell).distinct().count());

        SchematicGeometry.Box first = SchematicGeometry.solids().stream().filter(b -> b.cell() == 1).findFirst().get();
        assertEquals(231.2f,first.top().a().x(),.01f);
        assertEquals(236.11f,first.top().a().y(),.01f);
        SchematicGeometry.Point center = SchematicGeometry.iso(64,43,44);
        assertTrue(first.top().contains(center.x(),center.y()));
        assertFalse(first.top().contains(center.x()+15,center.y()));
    }

    @Test public void keepsTheSensorAnchorsWithTheOwnersPortOrder() {
        // ports on the BMS face, top first: coolant out (5), coolant in (6), DC+ (9), DC− (10)
        float[][] anchors = {{358.8f,217.5f},{462.2f,275.8f},{631.6f,180.3f},{528.2f,122f},
                {234.5f,337.3f},{256.5f,349.7f},{335.7f,283.8f},{323.6f,296.9f},
                {300.5f,374.5f},{324.7f,388.2f},{214.7f,257.7f},{490.8f,218.9f}};
        var sensors = SchematicGeometry.sensors();
        assertEquals(12,sensors.stream().map(SchematicGeometry.Sensor::number).distinct().count());
        for (var sensor : sensors) {
            float[] expected = anchors[sensor.number()-1];
            assertEquals(expected[0],sensor.anchor().x(),.05f);
            assertEquals(expected[1],sensor.anchor().y(),.05f);
        }
    }

    @Test public void legendGroupsOwnTheRowsUnderThem() {
        // BMS SYSTEM, HV BUS, COOLANT, DC CHARGE: header 22 above its first row, 26 between rows, 34 to the next header
        int[] order = {11, 7, 8, 5, 6, 9, 10};
        float[] rowY = {180, 228, 254, 302, 328, 376, 402};
        var sensors = SchematicGeometry.sensors();
        for (int i = 0; i < order.length; i++) {
            int n = order[i];
            var s = sensors.stream().filter(x -> x.number() == n).findFirst().get();
            assertFalse(s.tag());
            assertEquals(rowY[i],s.labelAt().y(),0f);
        }
        assertArrayEquals(new float[]{158,206,280,354},SchematicGeometry.LEGEND_HEADER_Y,0f);
        for (int g = 0; g < 4; g++) {
            float header = SchematicGeometry.LEGEND_HEADER_Y[g];
            assertTrue(header > SchematicGeometry.LEGEND_HEADER_Y[Math.max(0,g-1)] - 1);
        }
    }

    @Test public void tagsHangStraightAboveOrBelowTheirAnchor() {
        for (var s : SchematicGeometry.sensors()) {
            if (!s.tag()) continue;
            assertEquals(s.anchor().x(),s.labelAt().x(),0f);
            float dy = s.labelAt().y()-s.anchor().y();
            boolean behindTheBus = s.number() == 1 || s.number() == 4;
            assertEquals(behindTheBus ? -38 : 38,dy,.01f);
        }
    }

    @Test public void legendTracesNeverCross() {
        // each row runs right, along its own lane, then into its anchor; lanes are ordered so no two cross
        var rows = SchematicGeometry.sensors().stream().filter(s -> !s.tag()).collect(java.util.stream.Collectors.toList());
        for (var a : rows) for (var b : rows) {
            if (a == b) continue;
            float[] pa = SchematicGeometry.legendTrace(a), pb = SchematicGeometry.legendTrace(b);
            for (int i = 0; i + 3 < pa.length; i += 2) for (int j = 0; j + 3 < pb.length; j += 2) {
                assertFalse(a.name()+" crosses "+b.name(),crosses(pa[i],pa[i+1],pa[i+2],pa[i+3],pb[j],pb[j+1],pb[j+2],pb[j+3]));
            }
        }
    }

    private static boolean crosses(float ax,float ay,float bx,float by,float cx,float cy,float dx,float dy) {
        float d1 = side(cx,cy,dx,dy,ax,ay), d2 = side(cx,cy,dx,dy,bx,by), d3 = side(ax,ay,bx,by,cx,cy), d4 = side(ax,ay,bx,by,dx,dy);
        return d1*d2 < -1e-3f && d3*d4 < -1e-3f;
    }

    private static float side(float ax,float ay,float bx,float by,float px,float py) { return (bx-ax)*(py-ay)-(by-ay)*(px-ax); }

    @Test public void bevelsSensorTraceCorners() {
        assertArrayEquals(new float[]{0,0,15,0,20,5,20,20},
                SchematicGeometry.trace(new SchematicGeometry.Point(0,0),
                        new SchematicGeometry.Point(20,0),new SchematicGeometry.Point(20,20)),0f);
    }
}

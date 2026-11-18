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
        assertArrayEquals(new float[]{-61,110,-80,110,-80,462,865,462,865,210,900,210},
                SchematicGeometry.flowPath("hv"),0f);
        assertArrayEquals(new float[]{865,341,900,341},SchematicGeometry.flowPath("dc"),0f);
        assertArrayEquals(new float[]{1258,341,1272,341,1272,425,1258,425},
                SchematicGeometry.flowPath("lv"),0f);
        assertArrayEquals(new float[]{-60,425,-70,425,-70,201,-64,201},
                SchematicGeometry.flowPath("chg"),0f);
    }

    @Test public void projectsOriginalFacesAndAllCellIds() {
        assertEquals(119,SchematicGeometry.solids().size());
        assertEquals(119,SchematicGeometry.solids().stream().map(SchematicGeometry.Box::cell).distinct().count());

        SchematicGeometry.Box first = SchematicGeometry.solids().stream().filter(b -> b.cell() == 1).findFirst().get();
        assertEquals(301.2f,first.top().a().x(),.01f);
        assertEquals(236.11f,first.top().a().y(),.01f);
        SchematicGeometry.Point center = SchematicGeometry.iso(64,43,44);
        assertTrue(first.top().contains(center.x(),center.y()));
        assertFalse(first.top().contains(center.x()+15,center.y()));
    }

    @Test public void preservesAllTwelveSensorAnchors() {
        float[][] anchors = {{428.8f,217.5f},{532.2f,275.8f},{701.6f,180.3f},{598.2f,122f},
                {370.5f,374.5f},{394.7f,388.2f},{405.7f,283.8f},{393.6f,296.9f},
                {304.5f,337.3f},{326.5f,349.7f},{284.7f,257.7f},{560.8f,218.9f}};
        var sensors = SchematicGeometry.sensors();
        assertEquals(12,sensors.stream().map(SchematicGeometry.Sensor::number).distinct().count());
        for (var sensor : sensors) {
            float[] expected = anchors[sensor.number()-1];
            assertEquals(expected[0],sensor.anchor().x(),.05f);
            assertEquals(expected[1],sensor.anchor().y(),.05f);
        }
    }

    @Test public void bevelsSensorTraceCorners() {
        assertArrayEquals(new float[]{0,0,15,0,20,5,20,20},
                SchematicGeometry.trace(new SchematicGeometry.Point(0,0),
                        new SchematicGeometry.Point(20,0),new SchematicGeometry.Point(20,20)),0f);
    }
}

package com.catfish.battery;

import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChartGeometryTest {
    private static final ChartGeometry.Plot PLOT = new ChartGeometry.Plot(10, 20, 110, 120);

    @Test public void axisTicksUseLocalTimeAndOriginalRangeSteps() {
        long tenMinutes = ChartGeometry.tickStep(3_600_000);
        assertEquals(600_000, tenMinutes);
        assertEquals(10_800_000, ChartGeometry.tickStep(86_400_000));
        assertEquals(432_000_000, ChartGeometry.tickStep(2_592_000_000L));
        assertEquals(1_800_000, ChartGeometry.tickStep(3_600_000, 2));
        int offsetHalfHour = 1_800_000;
        assertEquals(1_800_000, ChartGeometry.firstTick(100, 3_600_000, offsetHalfHour));
        assertEquals(0, ChartGeometry.firstTick(0, tenMinutes, offsetHalfHour));
        assertEquals(60, ChartGeometry.powerLimit(List.of(sample(0, 50, -53))), 0);
    }

    @Test public void strokesBreakAcrossSleepAndChangeColorAtOriginalPowerThreshold() {
        List<ChartGeometry.Sample> samples = List.of(
                sample(0, 60, 5), sample(60_000, 59, -0.5), sample(120_000, 59, -1),
                sample(180_000, 60, -2), sample(1_200_000, 70, -3), sample(1_260_000, 71, -4));
        var power = ChartGeometry.strokes(samples, PLOT, true, 10);
        assertEquals(3, power.size());
        assertFalse(power.get(0).input);
        assertTrue(power.get(1).input);
        assertEquals(power.get(0).points.get(2), power.get(1).points.get(0));
        assertEquals(2, power.get(2).points.size());
        var soc = ChartGeometry.strokes(samples, PLOT, false, 100);
        assertEquals(2, soc.size());
        assertVertex(10, 60, soc.get(0).points.get(0));
    }

    @Test public void unavailableValuesNeverBecomeZeroSegments() {
        var samples = List.of(sample(0, Double.NaN, Double.NaN), sample(60_000, Double.NaN, Double.NaN));
        assertFalse(ChartGeometry.usable(samples));
        assertTrue(ChartGeometry.strokes(samples, PLOT, false, 100).isEmpty());
        assertTrue(ChartGeometry.strokes(samples, PLOT, true, 10).isEmpty());
    }

    @Test public void curveUsesOnlyNegativePowerWithSocInsideInclusiveSession() {
        var samples = List.of(sample(0, 20, -8), sample(1, 50, -3), sample(2, Double.NaN, -9),
                sample(3, 75, 4), sample(4, 50, -6), sample(5, 90, -9));
        var curve = ChartGeometry.chargeCurve(samples, new ChartGeometry.Band(1, 4));
        assertEquals(2, curve.samples.size());
        assertEquals(6, curve.peakKw, 0);
        var points = curve.vertices(PLOT);
        assertVertex(60, 70, points.get(0));
        assertVertex(60, 20, points.get(1));
        assertTrue(ChartGeometry.chargeCurve(samples, null).samples.isEmpty());
    }

    @Test public void nearestSampleHandlesBoundsTiesAndDuplicates() {
        float[] xs = {10, 20, 20, 40};
        assertEquals(-1, ChartGeometry.nearest(new float[0], 5));
        assertEquals(0, ChartGeometry.nearest(xs, 5));
        assertEquals(0, ChartGeometry.nearest(xs, 15));
        assertEquals(1, ChartGeometry.nearest(xs, 20));
        assertEquals(1, ChartGeometry.nearest(xs, 30));
        assertEquals(3, ChartGeometry.nearest(xs, 80));
    }

    private static void assertVertex(float x, float y, ChartGeometry.Vertex point) {
        assertEquals(x, point.x, 0);
        assertEquals(y, point.y, 0);
    }

    private static ChartGeometry.Sample sample(long time, double soc, double kw) {
        return new ChartGeometry.Sample(time, soc, kw, Double.NaN, Double.NaN);
    }
}

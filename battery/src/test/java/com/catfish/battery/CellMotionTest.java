package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The schematic's cell lift: green stays flat, high cells spring up, red ones lift fully and bob. */
public class CellMotionTest {
    private static final int GREEN = 0xff3ddba0, RED = 0xfff2616b;

    /** Steps the motion at 60 fps for the given time. */
    private static void run(CellMotion m, long fromMs, long forMs) {
        for (long t = fromMs; t <= fromMs + forMs; t += 16) m.step(t);
    }

    @Test public void liftFollowsTheDeviationBands() {
        assertEquals(0, CellMotion.lift(SchematicReadings.Band.GOOD, 1.5), 0);
        assertEquals(0, CellMotion.lift(SchematicReadings.Band.WAIT, Double.NaN), 0);
        float warm = CellMotion.lift(SchematicReadings.Band.WARN, 5);
        assertTrue(warm > 0 && warm < CellMotion.MAX_LIFT / 2);
        assertEquals(CellMotion.MAX_LIFT, CellMotion.lift(SchematicReadings.Band.DANGER, 20), 1e-3);
        assertEquals(-CellMotion.MAX_SINK, CellMotion.lift(SchematicReadings.Band.DANGER, -12), 1e-3);
        // out of the LFP bounds is danger even near the median: full lift or full sink by its side
        assertEquals(CellMotion.MAX_LIFT, CellMotion.lift(SchematicReadings.Band.DANGER, 1), 1e-3);
        assertEquals(-CellMotion.MAX_SINK, CellMotion.lift(SchematicReadings.Band.DANGER, -1), 1e-3);
    }

    @Test public void aRedCellSpringsPastItsTargetThenSettles() {
        CellMotion m = new CellMotion(3, true);
        m.retarget(new float[]{0, CellMotion.MAX_LIFT, 0}, new int[]{GREEN, RED, GREEN},
                new boolean[]{false, false, false}, 1, 0);
        float peak = 0;
        for (long t = 0; t <= 2000; t += 16) {
            m.step(t);
            peak = Math.max(peak, m.offset(1, t));
        }
        assertTrue("bounce overshoots", peak > CellMotion.MAX_LIFT * 1.05f);
        assertEquals(CellMotion.MAX_LIFT, m.offset(1, 2000), 1e-3);
        assertEquals(0, m.offset(0, 2000), 0);
        assertFalse(m.moving());
    }

    @Test public void neighboursFollowInARippleFromTheHotCell() {
        CellMotion m = new CellMotion(118, true);
        float[] lift = new float[118];
        java.util.Arrays.fill(lift, 10);
        int[] colors = new int[118];
        java.util.Arrays.fill(colors, GREEN);
        m.retarget(lift, colors, new boolean[118], 0, 0);
        run(m, 0, 60);
        assertTrue(m.offset(0, 60) > 0);
        assertEquals(0, m.offset(100, 60), 0);
    }

    @Test public void colourBlendsInsteadOfSwitching() {
        CellMotion m = new CellMotion(1, true);
        m.retarget(new float[]{0}, new int[]{GREEN}, new boolean[]{false}, 0, 0);
        run(m, 0, 2000);
        assertEquals(GREEN, m.color(0));
        m.retarget(new float[]{0}, new int[]{RED}, new boolean[]{false}, 0, 3000);
        m.step(3000);
        m.step(3100);
        int mid = m.color(0);
        assertTrue(mid != GREEN && mid != RED);
        run(m, 3100, 2000);
        assertEquals(RED, m.color(0));
    }

    @Test public void onlyDangerCellsKeepBobbing() {
        CellMotion m = new CellMotion(2, true);
        m.retarget(new float[]{CellMotion.MAX_LIFT, 0}, new int[]{RED, GREEN}, new boolean[]{true, false}, 0, 0);
        run(m, 0, 3000);
        assertFalse(m.moving());
        assertTrue(m.hovering());
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (long t = 3000; t < 6000; t += 16) {
            m.step(t);
            lo = Math.min(lo, m.offset(0, t));
            hi = Math.max(hi, m.offset(0, t));
        }
        assertTrue(hi - lo > 2 && hi - lo <= 2 * CellMotion.BOB + 1e-3);
        assertEquals(0, m.offset(1, 6000), 0);
    }

    @Test public void animationsOffSnapsAndNeverBobs() {
        CellMotion m = new CellMotion(1, false);
        m.retarget(new float[]{CellMotion.MAX_LIFT}, new int[]{RED}, new boolean[]{true}, 0, 0);
        assertEquals(CellMotion.MAX_LIFT, m.offset(0, 0), 0);
        assertEquals(RED, m.color(0));
        assertFalse(m.moving());
        assertFalse(m.hovering());
    }
}

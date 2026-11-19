package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The swipe card: follows the finger, turns on a quarter-width drag or a flick, springs to the page, clamps at the ends. */
public class SwipePagerTest {
    private static void settle(SwipePager p, long from) {
        for (long t = from; t < from + 3000; t += 16) p.step(t);
    }

    @Test public void aQuarterDragTurnsThePage() {
        SwipePager p = new SwipePager(3, 400);
        p.down(100, 0);
        p.move(-60, 50);
        assertEquals(-160, p.offset(), 0);
        p.up(-60, 500);            // 160 > 100 (a quarter) after a slow drag
        settle(p, 500);
        assertEquals(1, p.page());
        assertEquals(-400, p.offset(), .01);
        assertFalse(p.moving());
    }

    @Test public void aShortSlowDragSpringsBack() {
        SwipePager p = new SwipePager(3, 400);
        p.down(100, 0);
        p.move(60, 400);
        p.up(60, 800);
        settle(p, 800);
        assertEquals(0, p.page());
        assertEquals(0, p.offset(), .01);
    }

    @Test public void aFlickIsEnough() {
        SwipePager p = new SwipePager(3, 400);
        p.down(100, 0);
        p.up(60, 40);              // 40 units in 40 ms
        settle(p, 40);
        assertEquals(1, p.page());
    }

    @Test public void theEndsRubberBandAndClamp() {
        SwipePager p = new SwipePager(3, 400);
        p.down(100, 0);
        p.move(300, 50);           // past page 0
        assertTrue(p.offset() > 0 && p.offset() < 200);
        p.up(300, 60);
        settle(p, 60);
        assertEquals(0, p.page());
        p.go(5, 4000);
        settle(p, 4000);
        assertEquals(2, p.page());
        assertEquals(-800, p.offset(), .01);
    }

    @Test public void theSpringOvershootsALittle() {
        SwipePager p = new SwipePager(3, 400);
        p.go(1, 0);
        float lowest = 0;
        for (long t = 0; t < 2000; t += 16) { p.step(t); lowest = Math.min(lowest, p.offset()); }
        assertTrue(lowest < -400 && lowest > -440);
    }

    @Test public void animationsOffSnaps() {
        SwipePager p = new SwipePager(3, 400, false);
        p.go(2, 0);
        assertEquals(-800, p.offset(), 0);
        assertFalse(p.moving());
    }
}

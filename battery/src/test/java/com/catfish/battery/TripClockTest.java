package com.catfish.battery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TripClockTest {
    @Test public void startsWhenTheGearLeavesParkAndClearsOnPark() {
        TripClock c = new TripClock();
        c.observe(1, 100);                 // first seen already driving: start unknown
        assertEquals(0, c.leftParkMs());
        c.observe(4, 200);
        c.observe(null, 250);              // unknown reads change nothing
        c.observe(1, 300);
        assertEquals(300, c.leftParkMs());
        c.observe(2, 400);                 // D -> R keeps the trip start
        assertEquals(300, c.leftParkMs());
        c.observe(4, 500);
        assertEquals(0, c.leftParkMs());
    }
}

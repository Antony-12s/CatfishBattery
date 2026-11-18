package com.catfish.battery;

/**
 * When the car last left Park, for the "this trip" sparkline. Fed by the SOH recorder's 60 s tick (runs while the
 * service holds the hub) and by the page's 1 s poll. 0 while parked, when the gear is unknown, or when the app first
 * sees the car already driving (the trip start was not observed).
 */
final class TripClock {
    private Integer lastGear;
    private long leftParkMs;

    synchronized void observe(Integer gear, long now) {
        if (gear == null) return;
        if (gear == SohRecorder.GEAR_PARK) leftParkMs = 0;
        else if (lastGear != null && lastGear == SohRecorder.GEAR_PARK) leftParkMs = now;
        lastGear = gear;
    }

    synchronized long leftParkMs() { return leftParkMs; }
}

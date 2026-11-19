package com.catfish.battery;

import static com.catfish.battery.BecmSoh.CURRENT_GEAR;
import static com.catfish.battery.BecmSoh.GEAR_SELECTION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** Event-driven Park guard: both gears must be Park, and any trip out of Park voids the read for good. */
public class ParkGuardTest {
    private final AtomicLong now = new AtomicLong();
    private final BecmSoh.ParkGuard guard = new BecmSoh.ParkGuard(now::get, TimeUnit.SECONDS.toNanos(15));

    private int parked() throws Exception {
        guard.onGear(GEAR_SELECTION, 4);
        guard.onGear(CURRENT_GEAR, 4);
        return guard.awaitPark(10);
    }

    @Test public void bothGearsInParkGiveAToken() throws Exception {
        guard.check(parked());
    }

    @Test public void oneGearIsNotEnough() {
        guard.onGear(GEAR_SELECTION, 4);
        assertThrows(IOException.class, () -> guard.awaitPark(10));
    }

    @Test public void leavingParkVoidsTheTokenEvenAfterComingBack() throws Exception {
        int token = parked();
        guard.onGear(GEAR_SELECTION, 8); // D
        assertThrows(IOException.class, () -> guard.check(token));
        guard.onGear(GEAR_SELECTION, 4);
        assertThrows(IOException.class, () -> guard.check(token));
        guard.check(guard.awaitPark(10)); // a new read needs a new token
    }

    @Test public void unknownOrErrorGearIsUnsafe() throws Exception {
        int token = parked();
        guard.onGear(CURRENT_GEAR, null);
        assertThrows(IOException.class, () -> guard.check(token));
    }

    @Test public void otherPropertiesAreIgnored() throws Exception {
        int token = parked();
        guard.onGear(0x11600308, null);
        guard.check(token);
    }

    @Test public void seedNeverOverridesAnEvent() throws Exception {
        guard.onGear(GEAR_SELECTION, 8);
        guard.seed(GEAR_SELECTION, 4);   // an older read must not turn D back into P
        guard.seed(CURRENT_GEAR, 4);
        assertThrows(IOException.class, () -> guard.awaitPark(10));
    }

    @Test public void theWholeReadHasADeadline() throws Exception {
        int token = parked();
        now.addAndGet(TimeUnit.SECONDS.toNanos(16));
        IOException e = assertThrows(IOException.class, () -> guard.check(token));
        assertEquals("BECM read took too long", e.getMessage());
    }

    @Test public void closeRunsTheUnregister() {
        int[] closed = {0};
        guard.closer = () -> closed[0]++;
        guard.close();
        assertEquals(1, closed[0]);
    }
}

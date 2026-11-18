package com.catfish.battery;

/** Two taps, so a stray touch cannot wipe the totals: the first tap arms Reset totals for 4 s. */
final class StatusResetArming {
    static final long ARM_MS = 4000;
    static final String IDLE = "Reset totals", ARMED = "Tap again to reset";

    private long armedAt = -1;

    boolean armed(long now) {
        return armedAt >= 0 && now - armedAt < ARM_MS;
    }

    /** True when this tap confirms the reset; false when it only armed the button. */
    boolean tap(long now) {
        if (armed(now)) {
            armedAt = -1;
            return true;
        }
        armedAt = now;
        return false;
    }

    String label(long now) {
        return armed(now) ? ARMED : IDLE;
    }
}

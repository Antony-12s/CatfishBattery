package com.catfish.battery;

/**
 * Data freshness for the Pack header badges, as the WebView drawFreshness(): Live = stream up and data arriving; Stale = up
 * but frozen; Reconnecting = dropped with a known last value; otherwise Connecting / No vehicle data. Remembers the last
 * good reading time across polls.
 */
final class StatusFreshness {
    static final int LIVE = 0, STALE = 1, OFFLINE = 2;
    static final long STALE_MS = 6000;

    /** What the badges show for one poll. */
    static final class State {
        final int kind;
        final String label;
        final String updated;
        final boolean updatedStale;
        /** Connected but frozen: the pack drawing is dimmed so a stale value is not passed off as current. */
        final boolean dim;

        State(int kind, String label, String updated, boolean updatedStale, boolean dim) {
            this.kind = kind;
            this.label = label;
            this.updated = updated;
            this.updatedStale = updatedStale;
            this.dim = dim;
        }
    }

    private long lastGood = -1;

    State update(boolean connected, long lastUpdatedMs, long now) {
        boolean have = lastUpdatedMs > 0;
        if (have) lastGood = lastUpdatedMs;
        long age = lastGood >= 0 ? now - lastGood : -1;
        boolean fresh = connected && have && age < STALE_MS;
        int kind;
        String label;
        if (fresh) { kind = LIVE; label = "Live"; }
        else if (connected && lastGood < 0) { kind = OFFLINE; label = "Connecting"; }
        else if (connected) { kind = STALE; label = "Stale"; }
        else if (lastGood >= 0) { kind = STALE; label = "Reconnecting"; }
        else { kind = OFFLINE; label = "No vehicle data"; }
        String updated = lastGood >= 0 ? "Updated " + StatusFormat.relAge(Math.max(0, age)) : "No data yet";
        return new State(kind, label, updated, !fresh, connected && !fresh && lastGood >= 0);
    }
}

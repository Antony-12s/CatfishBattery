package com.catfish.battery;

/**
 * Spring motion for the schematic's 118 cells, in design units of height (pure Java, unit tested).
 * Green cells sit flat; a cell above the pack median springs up with a small overshoot, one below it sinks a little;
 * danger cells lift fully and keep bobbing gently. Colours blend toward the new band colour instead of switching.
 * A retarget ripples outward from the most deviating cell. With system animations off, everything snaps.
 */
final class CellMotion {
    static final float MAX_LIFT = 22, MAX_SINK = 4, BOB = 1.6f;
    private static final float STIFFNESS = 150, DAMPING = 13; // underdamped: about 14 % overshoot, settles in ~1 s
    private static final float COLOR_RATE = 7, RIPPLE_MS = 7, MAX_DT = .05f;

    private final boolean animate;
    private final float[] z, velocity, target, hover;
    private final boolean[] danger;
    private final long[] startMs;
    private final int[] color, targetColor;
    private final float[] r, g, b; // colour channels being blended
    private long lastMs = -1;
    private boolean moving, colored;

    CellMotion(int cells, boolean animate) {
        this.animate = animate;
        z = new float[cells];
        velocity = new float[cells];
        target = new float[cells];
        hover = new float[cells];
        danger = new boolean[cells];
        startMs = new long[cells];
        color = new int[cells];
        targetColor = new int[cells];
        r = new float[cells];
        g = new float[cells];
        b = new float[cells];
    }

    /** Target height for a cell's band and its distance from the pack median (mV, signed). */
    static float lift(SchematicReadings.Band band, double deviation) {
        if (band == SchematicReadings.Band.GOOD || band == SchematicReadings.Band.WAIT || !Double.isFinite(deviation)) return 0;
        if (band == SchematicReadings.Band.DANGER) return deviation >= 0 ? MAX_LIFT : -MAX_SINK; // red: all the way
        if (deviation >= 0) return MAX_LIFT * (float) Math.pow(smooth(2, 16, deviation), .85);
        return -MAX_SINK * (float) smooth(2, 10, -deviation);
    }

    /** New targets; {@code hot} is the most deviating cell, where the ripple starts. */
    void retarget(float[] lifts, int[] colors, boolean[] dangers, int hot, long nowMs) {
        boolean first = !colored;
        for (int i = 0; i < z.length; i++) {
            boolean changed = target[i] != lifts[i] || targetColor[i] != colors[i] || danger[i] != dangers[i];
            target[i] = lifts[i];
            targetColor[i] = colors[i];
            danger[i] = dangers[i];
            if (first || !animate) setColor(i, colors[i]);
            if (!animate) {
                z[i] = lifts[i];
                velocity[i] = 0;
            } else if (changed) {
                startMs[i] = nowMs + (long) (Math.abs(i - hot) * RIPPLE_MS);
                moving = true;
            }
        }
        colored = true;
    }

    /** Advances the springs to {@code nowMs}. */
    void step(long nowMs) {
        float dt = lastMs < 0 ? 0 : Math.min(MAX_DT, (nowMs - lastMs) / 1000f);
        lastMs = nowMs;
        if (!animate || dt <= 0) return;
        float blend = 1 - (float) Math.exp(-dt * COLOR_RATE);
        boolean still = true;
        for (int i = 0; i < z.length; i++) {
            float wantHover = danger[i] ? 1 : 0;
            hover[i] += (wantHover - hover[i]) * blend;
            if (Math.abs(hover[i] - wantHover) < .01f) hover[i] = wantHover;
            if (nowMs < startMs[i]) {
                if (z[i] != target[i] || color[i] != targetColor[i]) still = false;
                continue;
            }
            velocity[i] += (STIFFNESS * (target[i] - z[i]) - DAMPING * velocity[i]) * dt;
            z[i] += velocity[i] * dt;
            if (Math.abs(target[i] - z[i]) < .05f && Math.abs(velocity[i]) < .05f) {
                z[i] = target[i];
                velocity[i] = 0;
            } else still = false;
            if (color[i] != targetColor[i]) {
                int t = targetColor[i];
                r[i] += (((t >> 16) & 255) - r[i]) * blend;
                g[i] += (((t >> 8) & 255) - g[i]) * blend;
                b[i] += ((t & 255) - b[i]) * blend;
                int next = (t & 0xff000000) | Math.round(r[i]) << 16 | Math.round(g[i]) << 8 | Math.round(b[i]);
                if (Math.abs(((t >> 16) & 255) - r[i]) < .5f && Math.abs(((t >> 8) & 255) - g[i]) < .5f
                        && Math.abs((t & 255) - b[i]) < .5f) next = t;
                color[i] = next;
                if (next != t) still = false;
            }
        }
        moving = !still;
    }

    /** Height offset of cell {@code i} (index 0 = cell 1), bob included. */
    float offset(int i, long nowMs) {
        if (hover[i] == 0) return z[i];
        return z[i] + hover[i] * BOB * (float) Math.sin(nowMs / 520.0 + i * .9);
    }

    int color(int i) { return color[i]; }

    /** Still springing or blending: keep drawing every frame. */
    boolean moving() { return moving; }

    /** A danger cell is bobbing: keep drawing, even when everything else has settled. */
    boolean hovering() {
        if (!animate) return false;
        for (float h : hover) if (h > 0) return true;
        return false;
    }

    private void setColor(int i, int c) {
        color[i] = c;
        r[i] = (c >> 16) & 255;
        g[i] = (c >> 8) & 255;
        b[i] = c & 255;
    }

    private static double smooth(double from, double to, double v) {
        double t = Math.max(0, Math.min(1, (v - from) / (to - from)));
        return t * t * (3 - 2 * t);
    }
}

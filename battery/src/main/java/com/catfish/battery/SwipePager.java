package com.catfish.battery;

/**
 * Horizontal paging for the schematic's swipe card (pure Java, unit tested). Offsets are in design units: page i sits
 * at -i * width. The strip follows the finger while dragging (rubber band past the ends), then a quarter-width drag
 * or a flick turns the page and a slightly underdamped spring settles it, like the cell lift.
 */
final class SwipePager {
    private static final float STIFFNESS = 170, DAMPING = 18, FLICK = .5f, RUBBER = .35f, MAX_DT = .05f;

    private final int pages;
    private final float width;
    private final boolean animate;
    private float offset, velocity, downX, downOffset;
    private long downMs, lastMs = -1;
    private int page;
    private boolean dragging, moving;

    SwipePager(int pages, float width) { this(pages, width, true); }

    SwipePager(int pages, float width, boolean animate) {
        this.pages = pages;
        this.width = width;
        this.animate = animate;
    }

    int page() { return page; }
    float offset() { return offset; }
    boolean dragging() { return dragging; }
    /** Still springing: keep drawing every frame. */
    boolean moving() { return moving; }

    void down(float x, long nowMs) {
        dragging = true;
        moving = false;
        downX = x;
        downOffset = offset;
        downMs = nowMs;
    }

    void move(float x, long nowMs) {
        if (!dragging) return;
        float o = downOffset + (x - downX), min = -(pages - 1) * width;
        if (o > 0) o *= RUBBER;
        if (o < min) o = min + (o - min) * RUBBER;
        offset = o;
    }

    void up(float x, long nowMs) {
        if (!dragging) return;
        move(x, nowMs);
        dragging = false;
        float moved = x - downX, speed = moved / Math.max(1, nowMs - downMs);
        if (moved < -width / 4 || speed < -FLICK) go(page + 1, nowMs);
        else if (moved > width / 4 || speed > FLICK) go(page - 1, nowMs);
        else go(page, nowMs);
    }

    void go(int to, long nowMs) {
        page = Math.max(0, Math.min(pages - 1, to));
        lastMs = nowMs;
        if (!animate) {
            offset = -page * width;
            velocity = 0;
            moving = false;
            return;
        }
        moving = true;
    }

    void step(long nowMs) {
        if (!moving || dragging) { lastMs = nowMs; return; }
        float dt = lastMs < 0 ? 0 : Math.min(MAX_DT, (nowMs - lastMs) / 1000f);
        lastMs = nowMs;
        float target = -page * width;
        velocity += (STIFFNESS * (target - offset) - DAMPING * velocity) * dt;
        offset += velocity * dt;
        if (Math.abs(target - offset) < .2f && Math.abs(velocity) < .2f) {
            offset = target;
            velocity = 0;
            moving = false;
        }
    }
}

package com.catfish.battery;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.View;

import java.util.List;

/**
 * The Status page's Cells view on one Canvas: the four modules (rows P1 P4 / P2 P3, the BMS strip at the front) with 30 or
 * 29 cell tiles each in 10 x 3 grids, then the 12 sensor tiles. Same bands, labels and ▼ / ▲ markers as the WebView Cells
 * view (BatteryWebPages drawPacks / drawSensorTiles); the numbers come from {@link CellsModel}.
 * Cell tiles move like the schematic's cells ({@link CellMotion}): green stays flat, a high cell lifts as a raised key
 * with a 3D edge and a shadow, a red one lifts fully and bobs with a glow, a low one sinks, and a reading that changes
 * anything sends a small wave across the grid from the most deviating cell.
 */
final class CellsView extends View {
    private final StatusStyle s;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG), value = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            mark = new TextPaint(Paint.ANTI_ALIAS_FLAG), head = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            small = new TextPaint(Paint.ANTI_ALIAS_FLAG), front = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final DashPathEffect dash;
    private final RectF r = new RectF(), r2 = new RectF(), tileBox = new RectF(), inset = new RectF();
    private CellsModel model;
    private final CellMotion motion = new CellMotion(CellsModel.CELLS, ValueAnimator.areAnimatorsEnabled(), true);
    private final float[] lifts = new float[CellsModel.CELLS];
    private final int[] liftColors = new int[CellsModel.CELLS];
    private final boolean[] dangers = new boolean[CellsModel.CELLS];
    private long now;

    CellsView(Context context) {
        super(context);
        s = new StatusStyle(context);
        stroke.setStyle(Paint.Style.STROKE);
        dash = new DashPathEffect(new float[] { s.px(5), s.px(4) }, 0);
        label.setTypeface(StatusStyle.weight(600));
        value.setTypeface(StatusStyle.weight(700));
        mark.setTypeface(StatusStyle.weight(700));
        mark.setTextAlign(Paint.Align.RIGHT);
        head.setTypeface(StatusStyle.weight(700));
        small.setTypeface(StatusStyle.weight(400));
        front.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        front.setTextAlign(Paint.Align.CENTER);
        front.setLetterSpacing(0.14f);
        setContentDescription("118 cell voltages in four modules and 12 temperature sensors");
    }

    void setModel(CellsModel m) {
        model = m;
        int hot = 0;
        double worst = -1;
        for (CellsModel.Module mod : m.modules) {
            for (CellsModel.Tile t : mod.tiles) {
                int i = t.cell - 1;
                lifts[i] = CellMotion.lift(band(t.band), t.deviation);
                liftColors[i] = valueColor(t.band);
                dangers[i] = t.band == CellsModel.DANGER;
                if (Double.isFinite(t.deviation) && Math.abs(t.deviation) > worst) { worst = Math.abs(t.deviation); hot = i; }
            }
        }
        motion.retarget(lifts, liftColors, dangers, hot, SystemClock.uptimeMillis());
        invalidate();
    }

    private static SchematicReadings.Band band(int band) {
        return band == CellsModel.DANGER ? SchematicReadings.Band.DANGER : band == CellsModel.WARN ? SchematicReadings.Band.WARN
                : band == CellsModel.GOOD ? SchematicReadings.Band.GOOD : SchematicReadings.Band.WAIT;
    }

    private int valueColor(int band) {
        return band == CellsModel.WAIT ? s.muted2 : band == CellsModel.GOOD ? s.text : s.band(band);
    }

    @Override protected void onDraw(Canvas c) {
        if (model == null) return;
        now = SystemClock.uptimeMillis();
        motion.step(now);
        float w = getWidth(), h = getHeight();
        float tileH = s.px(76), headH = s.px(32), gap = s.px(8), rowGap = s.px(18);
        float sensorTop = h - tileH;
        float infoTop = sensorTop - gap - headH;
        float modsBottom = infoTop - s.px(12);

        // the BMS strip on the front edge
        float stripW = s.px(36);
        r.set(0, 0, stripW, modsBottom);
        box(c, r, s.px(8), s.surface2, s.line, 1, false);
        front.setTextSize(s.px(16));
        front.setColor(s.muted2);
        c.save();
        c.rotate(-90, stripW / 2, modsBottom / 2);
        c.drawText("BMS \u00b7 FRONT", stripW / 2, modsBottom / 2 + s.px(6), front);
        c.restore();

        float x0 = stripW + s.px(16), blockW = (w - x0 - rowGap) / 2, blockH = (modsBottom - rowGap) / 2;
        List<CellsModel.Module> mods = model.modules;
        for (int i = 0; i < mods.size(); i++) {
            float bx = x0 + (i % 2) * (blockW + rowGap), by = (i / 2) * (blockH + rowGap);
            drawModule(c, mods.get(i), bx, by, blockW, blockH);
        }

        head.setTextSize(s.px(22));
        head.setColor(s.text);
        float base = infoTop + headH - s.px(8);
        c.drawText("Sensor temperatures", 0, base, head);
        small.setTextSize(s.px(19));
        small.setColor(s.muted2);
        String note = "Collected passively as the vehicle cycles its sensors.";
        c.drawText(note, w - small.measureText(note), base, small);
        float tw = (w - gap * 11) / 12;
        for (int i = 0; i < model.sensors.size(); i++) {
            r.set(i * (tw + gap), sensorTop, i * (tw + gap) + tw, h);
            tile(c, model.sensors.get(i), r, true);
        }
        if (motion.moving() || motion.hovering()) postInvalidateOnAnimation();
    }

    private void drawModule(Canvas c, CellsModel.Module m, float x, float y, float w, float h) {
        r.set(x, y, x + w, y + h);
        box(c, r, s.px(20), s.bgDeep, s.line, 1, false);
        float padX = s.px(18), padY = s.px(16), gap = s.px(8);
        head.setTextSize(s.px(28));
        head.setColor(s.text);
        float base = y + padY + s.px(26);
        c.drawText(m.name, x + padX, base, head);
        small.setTextSize(s.px(19));
        small.setColor(s.muted2);
        float hx = x + padX + head.measureText(m.name) + s.px(18);
        c.drawText(TextUtils.ellipsize(m.head, small, x + w - padX - hx, TextUtils.TruncateAt.END).toString(), hx, base, small);
        float gy = base + s.px(8) + s.px(10), gw = w - 2 * padX, gh = y + h - padY - gy;
        float tw = (gw - gap * 9) / 10, th = (gh - gap * 2) / 3;
        for (int pass = 0; pass < 2; pass++) { // raised tiles last, so they sit over their neighbours
            for (int i = 0; i < m.tiles.size(); i++) {
                CellsModel.Tile t = m.tiles.get(i);
                if ((motion.offset(t.cell - 1, now) > LIFTED) != (pass == 1)) continue;
                float tx = x + padX + (i % 10) * (tw + gap), ty = gy + (i / 10) * (th + gap);
                r.set(tx, ty, tx + tw, ty + th);
                movingTile(c, t, r);
            }
        }
    }

    private static final float LIFTED = .3f;

    /** A cell tile at its lift (1 = full): raised key with an edge and shadow, or sunk with a darker face. */
    private void movingTile(Canvas c, CellsModel.Tile t, RectF box) {
        int i = t.cell - 1;
        float u = motion.offset(i, now) / CellMotion.MAX_LIFT;
        float dy = u > 0 ? -u * s.px(16) : -u * s.px(5) / (CellMotion.MAX_SINK / CellMotion.MAX_LIFT);
        dy += motion.wave(i, now) * s.px(6);
        float scale = u > 0 ? 1 + .05f * u * (dangers[i] ? 1 : .5f) : 1;
        c.save();
        c.translate(0, dy);
        c.scale(scale, scale, box.centerX(), box.centerY());
        float radius = s.px(12);
        if (u > .015f) {
            float thick = u * s.px(10);
            if (dangers[i]) { // glow
                r2.set(box.left - s.px(5), box.top - s.px(5), box.right + s.px(5), box.bottom + s.px(5) + thick);
                fill.setColor(StatusStyle.alpha(s.danger, .22f));
                c.drawRoundRect(r2, radius + s.px(5), radius + s.px(5), fill);
            }
            r2.set(box.left, box.top + thick, box.right, box.bottom + thick);
            fill.setShadowLayer(u * s.px(16), 0, u * s.px(10), StatusStyle.alpha(0xff000000, .35f + .3f * u));
            fill.setColor(SchematicReadings.shade(motion.color(i), .45f) | 0xff000000);
            c.drawRoundRect(r2, radius, radius, fill); // the key's edge
            fill.clearShadowLayer();
        }
        tile(c, t, box, false);
        if (u < -.01f) {
            fill.setColor(StatusStyle.alpha(0xff000000, Math.min(.35f, -u * .35f / (CellMotion.MAX_SINK / CellMotion.MAX_LIFT))));
            c.drawRoundRect(box, radius, radius, fill);
        }
        c.restore();
    }

    private void tile(Canvas c, CellsModel.Tile t, RectF box, boolean sensor) {
        int band = t.band;
        int bg = sensor ? s.bgDeep : s.surface;
        int border = band == CellsModel.WARN ? s.warn : band == CellsModel.DANGER ? s.danger : s.line;
        RectF b = tileBox;
        b.set(box);
        if (band == CellsModel.DANGER) {
            box(c, b, s.px(12), bg, 0, 0, false);
            box(c, b, s.px(12), StatusStyle.alpha(s.danger, 0.14f), s.danger, 2, false);
        } else {
            box(c, b, s.px(12), bg, border, 1, band == CellsModel.WAIT);
        }
        float padX = s.px(10), inner = b.width() - 2 * padX;
        float valueSize = Math.min(s.px(28), b.height() * 0.4f), labelSize = Math.min(s.px(18), b.height() * 0.26f);
        label.setTextSize(labelSize);
        label.setColor(s.muted2);
        value.setTextSize(valueSize);
        value.setColor(sensor ? valueColor(band) : motion.color(t.cell - 1)); // cells blend toward the new band colour
        float block = labelSize + s.px(4) + valueSize;
        float top = b.centerY() - block / 2;
        c.drawText(TextUtils.ellipsize(t.label, label, inner, TextUtils.TruncateAt.END).toString(),
                b.left + padX, top + labelSize * 0.8f, label);
        c.drawText(TextUtils.ellipsize(t.text, value, inner, TextUtils.TruncateAt.END).toString(),
                b.left + padX, top + labelSize + s.px(4) + valueSize * 0.8f, value);
        if (!t.mark.isEmpty()) {
            mark.setTextSize(Math.min(s.px(17), b.height() * 0.24f));
            mark.setColor(s.text);
            c.drawText(t.mark, b.right - padX, b.top + s.px(8) + mark.getTextSize() * 0.8f, mark);
        }
    }

    private void box(Canvas c, RectF b, float radius, int bg, int border, float strokeDp, boolean dashed) {
        fill.setColor(bg);
        c.drawRoundRect(b, radius, radius, fill);
        if (strokeDp <= 0) return;
        float sw = Math.max(1, s.px(strokeDp));
        stroke.setStrokeWidth(sw);
        stroke.setColor(border);
        stroke.setPathEffect(dashed ? dash : null);
        inset.set(b.left + sw / 2, b.top + sw / 2, b.right - sw / 2, b.bottom - sw / 2);
        c.drawRoundRect(inset, radius, radius, stroke);
    }
}

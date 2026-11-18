package com.catfish.battery;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.PathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** SOC, signed pack power and charging sessions on the same history timeline. */
final class HistoryChartView extends View {
    private static final String DESCRIPTION = "SOC and power history";
    private static final String EMPTY = "Not enough samples in this range";
    private static final int[] SOC_TICKS = {0, 25, 50, 75, 100};

    private static final class Label {
        final String text;
        final float x;
        final float y;
        final Paint.Align align;
        final boolean power;

        Label(String text, float x, float y, Paint.Align align, boolean power) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.align = align;
            this.power = power;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private final PathEffect zeroDashes;
    private final Path grid = new Path(), zero = new Path(), soc = new Path(), socFill = new Path();
    private final Path powerIn = new Path(), powerOut = new Path();
    private final List<RectF> bands = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private List<ChartGeometry.Sample> samples = List.of();
    private List<ChartGeometry.Band> sessions = List.of();
    private ChartGeometry.Plot plot;
    private ChartGeometry.Vertex lastSoc, lastPower;
    private float[] xs = new float[0];
    private UiPalette palette;
    private int selected = -1;
    private String tooltipDate, tooltipReadings;

    HistoryChartView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        zeroDashes = new DashPathEffect(new float[]{3 * dp, 4 * dp}, 0);
        paint.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        palette = UiPalette.of(context);
        setContentDescription(EMPTY);
    }

    void setData(JSONObject historyData) {
        samples = ChartData.samples(historyData);
        sessions = ChartData.bands(historyData);
        clearSelection();
        rebuild();
        invalidate();
    }

    void setNight(boolean night) {
        palette = UiPalette.of(night);
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        clearSelection();
        rebuild();
    }

    private void rebuild() {
        plot = null;
        xs = new float[0];
        grid.rewind();
        zero.rewind();
        soc.rewind();
        socFill.rewind();
        powerIn.rewind();
        powerOut.rewind();
        labels.clear();
        bands.clear();
        lastSoc = null;
        lastPower = null;
        float textSize = paint.getTextSize();
        float left = 3 * textSize, top = textSize, right = getWidth() - 3.5f * textSize;
        float bottom = getHeight() - 1.8f * textSize;
        if (left >= right || top >= bottom || !ChartGeometry.usable(samples)) {
            setContentDescription(EMPTY);
            return;
        }
        setContentDescription(DESCRIPTION);
        plot = new ChartGeometry.Plot(left, top, right, bottom);
        long start = samples.get(0).time, end = samples.get(samples.size() - 1).time;
        long span = Math.max(1, end - start);
        double limit = ChartGeometry.powerLimit(samples);
        addAxes(start, end, span, limit);

        for (ChartGeometry.Band session : sessions) {
            long from = Math.max(start, session.start), to = Math.min(end, session.end);
            if (to <= from) continue;
            float x1 = plot.x(from, start, start + span), x2 = plot.x(to, start, start + span);
            bands.add(new RectF(x1, top, Math.min(right, Math.max(x1 + 2 * dp, x2)), bottom));
        }

        for (ChartGeometry.Stroke stroke : ChartGeometry.strokes(samples, plot, false, 100)) {
            List<ChartGeometry.Vertex> points = stroke.points;
            Path line = ChartDrawing.path(points);
            soc.addPath(line);
            line.lineTo(points.get(points.size() - 1).x, bottom);
            line.lineTo(points.get(0).x, bottom);
            line.close();
            socFill.addPath(line);
        }
        for (ChartGeometry.Stroke stroke : ChartGeometry.strokes(samples, plot, true, limit)) {
            (stroke.input ? powerIn : powerOut).addPath(ChartDrawing.path(stroke.points));
        }

        xs = new float[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            ChartGeometry.Sample p = samples.get(i);
            float x = plot.x(p.time, start, start + span);
            xs[i] = x;
            if (Double.isFinite(p.soc)) lastSoc = new ChartGeometry.Vertex(x, plot.y(p.soc, 0, 100));
            if (Double.isFinite(p.kw)) lastPower = new ChartGeometry.Vertex(x, plot.y(p.kw, -limit, limit));
        }
    }

    private void addAxes(long start, long end, long span, double limit) {
        float left = plot.left, top = plot.top, right = plot.right, bottom = plot.bottom;
        float baseline = paint.getTextSize() / 3;
        for (int tick : SOC_TICKS) {
            float y = plot.y(tick, 0, 100);
            line(grid, left, y, right, y);
            labels.add(new Label(UiFormat.fixed(tick, 0, ""), left - 6 * dp, y + baseline, Paint.Align.RIGHT, false));
        }
        for (double tick : new double[]{limit, 0, -limit}) {
            float y = plot.y(tick, -limit, limit);
            labels.add(new Label((tick > 0 ? "+" : "") + UiFormat.fixed(tick, 0, ""),
                    right + 6 * dp, y + baseline, Paint.Align.LEFT, true));
        }
        float middle = plot.y(0, -limit, limit);
        line(zero, left, middle, right, middle);

        SimpleDateFormat format = new SimpleDateFormat(span > 172_800_000 ? "d MMM" : "HH:mm", Locale.getDefault());
        // Limit tick density for narrow views and unusually long timestamp spans.
        long maxTicks = Math.max(1, (long) ((right - left) / (4 * paint.getTextSize())));
        long step = ChartGeometry.tickStep(span, maxTicks);
        long tick = ChartGeometry.firstTick(start, step, format.getTimeZone().getOffset(start));
        for (; tick <= end; tick += step) {
            float x = plot.x(tick, start, start + span);
            line(grid, x, top, x, bottom);
            Paint.Align align = x < left + 18 * dp ? Paint.Align.LEFT
                    : x > right - 18 * dp ? Paint.Align.RIGHT : Paint.Align.CENTER;
            labels.add(new Label(format.format(new Date(tick)), x, getHeight() - 8 * dp, align, false));
        }
    }

    private static void line(Path path, float x1, float y1, float x2, float y2) {
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(palette.surface);
        if (plot == null) {
            ChartDrawing.text(canvas, paint, EMPTY, getWidth() / 2f, getHeight() / 2f,
                    palette.muted, Paint.Align.CENTER);
            return;
        }
        ChartDrawing.stroke(canvas, paint, grid, palette.line, dp);
        paint.setPathEffect(zeroDashes);
        ChartDrawing.stroke(canvas, paint, zero, palette.muted, dp);
        paint.setPathEffect(null);
        for (Label label : labels) {
            ChartDrawing.text(canvas, paint, label.text, label.x, label.y,
                    label.power ? palette.warn : palette.muted, label.align);
        }
        paint.setColor(ChartDrawing.alpha(palette.good, 36));
        for (RectF band : bands) canvas.drawRect(band, paint);
        paint.setColor(ChartDrawing.alpha(palette.accent, 18));
        canvas.drawPath(socFill, paint);
        ChartDrawing.stroke(canvas, paint, soc, palette.accent, 2 * dp);
        ChartDrawing.stroke(canvas, paint, powerOut, palette.warn, 1.5f * dp);
        ChartDrawing.stroke(canvas, paint, powerIn, palette.good, 1.5f * dp);
        dot(canvas, lastPower, palette.warn, 3.5f);
        dot(canvas, lastSoc, palette.accent, 4);
        if (selected >= 0) drawTooltip(canvas);
    }

    private void dot(Canvas canvas, ChartGeometry.Vertex point, int color, float radius) {
        if (point == null) return;
        paint.setColor(color);
        canvas.drawCircle(point.x, point.y, radius * dp, paint);
    }

    private void drawTooltip(Canvas canvas) {
        float x = xs[selected];
        paint.setColor(palette.muted);
        paint.setStrokeWidth(dp);
        canvas.drawLine(x, plot.top, x, plot.bottom, paint);
        float pad = 8 * dp, text = paint.getTextSize();
        float width = Math.min(getWidth(), Math.max(paint.measureText(tooltipDate), paint.measureText(tooltipReadings)) + 2 * pad);
        float left = x > getWidth() * 0.6f ? x - width - pad : x + pad;
        left = Math.max(0, Math.min(left, getWidth() - width));
        float top = plot.top + pad;
        paint.setColor(palette.bgDeep);
        canvas.drawRoundRect(left, top, left + width, top + 2 * text + 2 * pad, pad, pad, paint);
        canvas.save();
        canvas.clipRect(left, top, left + width, top + 2 * text + 2 * pad);
        ChartDrawing.text(canvas, paint, tooltipDate, left + pad, top + pad + text * .85f, palette.text, Paint.Align.LEFT);
        ChartDrawing.text(canvas, paint, tooltipReadings, left + pad, top + pad + text * 1.85f, palette.text, Paint.Align.LEFT);
        canvas.restore();
    }

    private void select(float x) {
        int hit = ChartGeometry.nearest(xs, x);
        if (hit < 0 || hit == selected) return;
        selected = hit;
        ChartGeometry.Sample p = samples.get(hit);
        tooltipDate = new SimpleDateFormat("EEE d MMM '·' HH:mm", Locale.getDefault()).format(new Date(p.time));
        tooltipReadings = "SOC " + UiFormat.fixed(p.soc, 1, "%") + " · " + UiFormat.fixed(p.kw, 1, " kW")
                + " · " + UiFormat.fixed(p.temp, 1, "°C") + " · Diff " + UiFormat.fixed(p.diff, 0, " mV");
        invalidate();
    }

    private void clearSelection() {
        selected = -1;
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (plot == null) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                select(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
                select(event.getX());
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                clearSelection();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        if (selected >= 0) announceForAccessibility(tooltipDate + " · " + tooltipReadings);
        return true;
    }

    @Override public boolean onHoverEvent(MotionEvent event) {
        if (plot != null) {
            if (event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) select(event.getX());
            if (event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) clearSelection();
        }
        return super.onHoverEvent(event);
    }
}

package com.catfish.battery;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.json.JSONObject;

import java.util.List;

/** Charge input power versus SOC for the live or selected session. */
final class ChargeCurveView extends View {
    private static final String EMPTY = "No charge curve for this session";
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private UiPalette palette;
    private ChartGeometry.Curve curve = ChartGeometry.chargeCurve(List.of(), null);
    private Path path;
    private ChartGeometry.Vertex end;
    private String lowSoc, highSoc, peak;

    ChargeCurveView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        paint.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
        paint.setStrokeJoin(Paint.Join.ROUND);
        palette = UiPalette.of(context);
        setContentDescription(EMPTY);
    }

    /** History JSON with its live session, or with the chosen session under "session". */
    void setData(JSONObject curveData) {
        JSONObject session = curveData == null ? null : curveData.optJSONObject(
                curveData.has("session") ? "session" : "live");
        curve = ChartGeometry.chargeCurve(ChartData.samples(curveData), ChartData.band(session));
        if (curve.samples.size() >= 2) {
            lowSoc = UiFormat.fixed(curve.lowSoc, 0, "%");
            highSoc = UiFormat.fixed(curve.highSoc, 0, "%");
            peak = UiFormat.fixed(curve.peakKw, 1, " kW");
            setContentDescription("Charge power versus SOC, " + lowSoc + " to " + highSoc + ", peak " + peak);
        } else {
            setContentDescription(EMPTY);
        }
        rebuild();
        invalidate();
    }

    void setNight(boolean night) {
        palette = UiPalette.of(night);
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        rebuild();
    }

    private void rebuild() {
        path = null;
        if (curve.samples.size() < 2 || getWidth() <= 16 * dp || getHeight() <= 2 * paint.getTextSize()) return;
        List<ChartGeometry.Vertex> vertices = curve.vertices(new ChartGeometry.Plot(
                8 * dp, paint.getTextSize() + 4 * dp, getWidth() - 8 * dp, getHeight() - paint.getTextSize()));
        path = ChartDrawing.path(vertices);
        end = vertices.get(vertices.size() - 1);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(palette.surface);
        if (path == null) {
            ChartDrawing.text(canvas, paint, EMPTY, 12 * dp, getHeight() / 2f, palette.muted, Paint.Align.LEFT);
            return;
        }
        ChartDrawing.stroke(canvas, paint, path, palette.warn, 1.5f * dp);
        canvas.drawCircle(end.x, end.y, 3 * dp, paint);
        float bottom = getHeight() - 3 * dp;
        ChartDrawing.text(canvas, paint, lowSoc, 6 * dp, bottom, palette.muted, Paint.Align.LEFT);
        ChartDrawing.text(canvas, paint, highSoc, getWidth() - 6 * dp, bottom, palette.muted, Paint.Align.RIGHT);
        ChartDrawing.text(canvas, paint, peak, 6 * dp, paint.getTextSize(), palette.muted, Paint.Align.LEFT);
    }
}

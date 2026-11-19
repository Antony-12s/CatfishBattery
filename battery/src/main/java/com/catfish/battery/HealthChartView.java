package com.catfish.battery;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/** Health page charts (built in code only, so no XML constructors): SOH points against x (days, km or mV) with the least-squares trend line, slope, R² and n. */
@android.annotation.SuppressLint("ViewConstructor")
final class HealthChartView extends View {
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF plot = new RectF();
    private DashPathEffect dash;
    private String stats = "";
    private final ChargingUi ui;
    private final String title, unit;
    private String note;
    private final double slopeScale; // slope per x unit -> slope per shown unit (per 10,000 km, per year)
    private final String slopeUnit;
    private double[] x = new double[0], y = new double[0];
    private HealthModel.Fit fit;
    private String xMinText = "", xMaxText = "";

    HealthChartView(Context context, ChargingUi ui, String title, String unit, double slopeScale, String slopeUnit,
                    String note) {
        super(context);
        this.ui = ui;
        this.title = title;
        this.unit = unit;
        this.slopeScale = slopeScale;
        this.slopeUnit = slopeUnit;
        this.note = note;
        setContentDescription(title);
    }

    void setNote(String note) {
        this.note = note;
        invalidate();
    }

    void setData(double[] x, double[] y, HealthModel.Fit fit, String xMinText, String xMaxText) {
        this.x = x;
        this.y = y;
        this.fit = fit;
        this.xMinText = xMinText;
        this.xMaxText = xMaxText;
        stats = fit == null || Double.isNaN(fit.slope) ? "n " + x.length
                : String.format(Locale.ROOT, "%s%.2f %s · R² %.2f · n %d", fit.slope < 0 ? "\u2212" : "+",
                        Math.abs(fit.slope * slopeScale), slopeUnit, fit.r2, fit.n);
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), pad = ui.dp(22);
        text(c, title, pad, pad + ui.dp(22), 22, ui.text, true, Paint.Align.LEFT);
        text(c, stats, w - pad, pad + ui.dp(22), 19, ui.muted, false, Paint.Align.RIGHT);
        if (!note.isEmpty()) text(c, note, w - pad, pad + ui.dp(48), 17, ui.warn, false, Paint.Align.RIGHT);
        plot.set(pad + ui.dp(70), pad + ui.dp(66), w - pad, h - pad - ui.dp(34));
        if (x.length < 2) {
            text(c, "collecting data", plot.centerX(), plot.centerY(), 22, ui.muted, false, Paint.Align.CENTER);
            return;
        }
        double x0 = min(x), x1 = max(x), y0 = Math.floor(min(y) - 0.5), y1 = Math.ceil(max(y) + 0.5);
        if (x1 == x0) x1 = x0 + 1;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ui.dp(1));
        paint.setColor(ui.night ? 0x244CC9F0 : ui.lineSoft);
        for (int i = 0; i <= 2; i++) {
            float gy = plot.top + plot.height() * i / 2f;
            c.drawLine(plot.left, gy, plot.right, gy, paint);
            text(c, UiFormat.fixed(y1 - (y1 - y0) * i / 2, 1, " %"), plot.left - ui.dp(10), gy + ui.dp(7),
                    18, ui.muted, false, Paint.Align.RIGHT);
        }
        text(c, xMinText, plot.left, plot.bottom + ui.dp(28), 18, ui.muted, false, Paint.Align.LEFT);
        text(c, xMaxText, plot.right, plot.bottom + ui.dp(28), 18, ui.muted, false, Paint.Align.RIGHT);
        text(c, unit, plot.centerX(), plot.bottom + ui.dp(28), 18, ui.muted, false, Paint.Align.CENTER);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ui.accent);
        for (int i = 0; i < x.length; i++) {
            c.drawCircle(px(x[i], x0, x1, plot), py(y[i], y0, y1, plot), ui.dp(5), paint);
        }
        if (fit != null && Double.isFinite(fit.slope)) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(3));
            paint.setColor(ui.good);
            if (dash == null) dash = new DashPathEffect(new float[]{ui.dp(10), ui.dp(7)}, 0);
            paint.setPathEffect(dash);
            c.drawLine(px(x0, x0, x1, plot), py(fit.slope * x0 + fit.intercept, y0, y1, plot),
                    px(x1, x0, x1, plot), py(fit.slope * x1 + fit.intercept, y0, y1, plot), paint);
            paint.setPathEffect(null);
        }
    }

    private static float px(double v, double lo, double hi, RectF r) { return r.left + (float) ((v - lo) / (hi - lo)) * r.width(); }

    private static float py(double v, double lo, double hi, RectF r) { return r.bottom - (float) ((v - lo) / (hi - lo)) * r.height(); }

    private void text(Canvas c, String s, float x, float y, float sp, int color, boolean bold, Paint.Align align) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(ui.dp(sp));
        paint.setTypeface(bold ? MEDIUM : Typeface.DEFAULT);
        paint.setTextAlign(align);
        c.drawText(s, x, y, paint);
    }

    private static double min(double[] v) { double m = Double.POSITIVE_INFINITY; for (double d : v) m = Math.min(m, d); return m; }

    private static double max(double[] v) { double m = Double.NEGATIVE_INFINITY; for (double d : v) m = Math.max(m, d); return m; }

    /** The SOH ring of the Simple view: an arc filled to the SOH, coloured by the verdict. */
    @android.annotation.SuppressLint("ViewConstructor")
    static final class Ring extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final ChargingUi ui;
        private double pct = Double.NaN;

        Ring(Context context, ChargingUi ui) {
            super(context);
            this.ui = ui;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setPct(double pct) {
            this.pct = pct;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float stroke = ui.dp(22), size = Math.min(getWidth(), getHeight()) - stroke;
            r.set((getWidth() - size) / 2, (getHeight() - size) / 2, (getWidth() + size) / 2, (getHeight() + size) / 2);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(ui.track);
            c.drawArc(r, 135, 270, false, paint);
            if (Double.isNaN(pct)) return;
            paint.setColor(pct >= 90 ? ui.good : pct >= 80 ? ui.warn : ui.danger);
            c.drawArc(r, 135, (float) (270 * Math.max(0, Math.min(100, pct)) / 100), false, paint);
        }
    }
}

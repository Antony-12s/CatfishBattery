package com.catfish.battery;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The Health page from soh.csv: SOH against time, distance and cell spread. Rates come from a least-squares trend over
 * every reading (the BMS moves SOH in steps, so first-minus-last would swing). A rate is only shown once readings
 * span 30 days and the SOH has changed at least once; before that the page says it is collecting, and offers a
 * "since new" estimate that assumes 100 % at 0 km (clearly labelled as such).
 */
final class HealthModel {
    static final int MIN_DAYS = 30;
    private static final double DAY = 86_400_000d, YEAR_DAYS = 365.2425;

    /** y = slope * x + intercept; NaN slope when x has no spread or fewer than 2 points. */
    static final class Fit {
        final double slope, intercept, r2;
        final int n;

        Fit(double slope, double intercept, double r2, int n) {
            this.slope = slope;
            this.intercept = intercept;
            this.r2 = r2;
            this.n = n;
        }
    }

    final boolean enough;
    final double latest, latestKm, perYear, per10kKm, km90;
    final String sohText, verdict, source, kmText, readAt, per10kText, perYearText, projection, collecting, sinceNew;
    final Fit byTime, byKm, byDiff;
    final double[] days, kms, diffs, pctsAll, pctsKm, pctsDiff;
    final long firstTs;
    final int collectDays; // whole days of readings so far, capped at MIN_DAYS
    final List<String[]> table = new ArrayList<>(); // date, SOH, source, km, V diff; newest first

    private HealthModel(List<SohRecorder.Entry> rows, long now, TimeZone zone) {
        int n = rows.size();
        SohRecorder.Entry last = n == 0 ? null : rows.get(n - 1);
        latest = last == null ? Double.NaN : last.pct;
        double km = Double.NaN;
        for (int i = n - 1; i >= 0 && Double.isNaN(km); i--) km = rows.get(i).km;
        latestKm = km;
        sohText = last == null ? UiFormat.MISSING : SchematicReadings.soh(last.pct, last.source);
        verdict = verdict(latest);
        source = last == null ? "" : SohRecorder.BECM.equals(last.source) ? "BECM" : "canspi";
        kmText = km(latestKm);
        readAt = last == null ? "" : "read " + date(last.ts, zone, "d MMM yyyy");

        firstTs = n == 0 ? now : rows.get(0).ts;
        days = new double[n];
        pctsAll = new double[n];
        List<double[]> withKm = new ArrayList<>(), withDiff = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            SohRecorder.Entry e = rows.get(i);
            days[i] = (e.ts - firstTs) / DAY;
            pctsAll[i] = e.pct;
            if (e.pct != rows.get(0).pct) changed = true;
            if (Double.isFinite(e.km)) withKm.add(new double[]{e.km, e.pct});
            if (Double.isFinite(e.cellDiffMv)) withDiff.add(new double[]{e.cellDiffMv, e.pct});
        }
        kms = column(withKm, 0);
        pctsKm = column(withKm, 1);
        diffs = column(withDiff, 0);
        pctsDiff = column(withDiff, 1);
        byTime = fit(days, pctsAll);
        byKm = fit(kms, pctsKm);
        byDiff = fit(diffs, pctsDiff);

        double span = n == 0 ? 0 : (now - firstTs) / DAY;
        enough = n >= 3 && span >= MIN_DAYS && changed;
        perYear = enough ? byTime.slope * YEAR_DAYS : Double.NaN;
        per10kKm = enough ? byKm.slope * 10_000 : Double.NaN;
        per10kText = Double.isFinite(per10kKm) ? "about " + signed(per10kKm) + " % per 10,000 km" : "";
        perYearText = Double.isFinite(perYear) ? "about " + signed(perYear) + " % per year" : "";
        if (Double.isFinite(per10kKm) && per10kKm < 0 && latest > 90 && Double.isFinite(latestKm)) {
            km90 = latestKm + (90 - latest) / byKm.slope;
            String year = Double.isFinite(perYear) && perYear < 0
                    ? " (" + date(now + (long) ((90 - latest) / (perYear / YEAR_DAYS) * DAY), zone, "yyyy") + ")" : "";
            projection = "At this rate 90 % at about " + km(Math.round(km90 / 1000) * 1000d) + year;
        } else {
            km90 = Double.NaN;
            projection = "";
        }
        collectDays = Math.min(MIN_DAYS, (int) Math.floor(span));
        collecting = collectDays + " of " + MIN_DAYS + " days · " + n
                + (n == 1 ? " reading" : " readings");
        sinceNew = last != null && Double.isFinite(latestKm) && latestKm > 1000 && latest < 100
                ? "about " + signed((latest - 100) / latestKm * 10_000) + " % per 10,000 km since new" : "";

        for (int i = n - 1; i >= 0; i--) {
            SohRecorder.Entry e = rows.get(i);
            table.add(new String[]{date(e.ts, zone, "d MMM HH:mm"), SchematicReadings.soh(e.pct, e.source),
                    SohRecorder.BECM.equals(e.source) ? "BECM" : "canspi", km(e.km),
                    UiFormat.fixed(e.cellDiffMv, 0, " mV")});
        }
    }

    static HealthModel from(List<SohRecorder.Entry> rows, long now, TimeZone zone) {
        return new HealthModel(rows, now, zone);
    }

    static Fit fit(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        if (n < 2) return new Fit(Double.NaN, Double.NaN, Double.NaN, n);
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += x[i]; my += y[i]; }
        mx /= n;
        my /= n;
        double sxx = 0, sxy = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            sxx += (x[i] - mx) * (x[i] - mx);
            sxy += (x[i] - mx) * (y[i] - my);
            syy += (y[i] - my) * (y[i] - my);
        }
        if (sxx == 0) return new Fit(Double.NaN, Double.NaN, Double.NaN, n);
        double slope = sxy / sxx;
        return new Fit(slope, my - slope * mx, syy == 0 ? 1 : sxy * sxy / (sxx * syy), n);
    }

    static String verdict(double pct) {
        if (Double.isNaN(pct)) return UiFormat.MISSING;
        return pct >= 90 ? "Healthy" : pct >= 80 ? "Normal ageing" : "Check";
    }

    private static double[] column(List<double[]> rows, int i) {
        double[] out = new double[rows.size()];
        for (int r = 0; r < out.length; r++) out[r] = rows.get(r)[i];
        return out;
    }

    private static String signed(double v) {
        return (v < 0 ? "\u2212" : "+") + String.format(Locale.ROOT, "%.1f", Math.abs(v));
    }

    private static String km(double km) {
        return Double.isFinite(km) ? String.format(Locale.US, "%,.0f km", km) : UiFormat.MISSING;
    }

    private static String date(long ms, TimeZone zone, String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(zone);
        return f.format(new Date(ms));
    }
}

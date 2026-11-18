package com.catfish.battery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ChartGeometry {
    static final class Sample {
        final long time;
        final double soc;
        final double kw;
        final double temp;
        final double diff;

        Sample(long time, double soc, double kw, double temp, double diff) {
            this.time = time;
            this.soc = soc;
            this.kw = kw;
            this.temp = temp;
            this.diff = diff;
        }
    }

    static final class Band {
        final long start;
        final long end;

        Band(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    static final class Plot {
        final float left;
        final float top;
        final float right;
        final float bottom;

        Plot(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        float x(double v, double min, double max) {
            return max == min ? (left + right) / 2 : left + (float) ((v - min) / (max - min) * (right - left));
        }

        float y(double v, double min, double max) {
            return bottom - (float) ((v - min) / (max - min) * (bottom - top));
        }
    }

    static final class Vertex {
        final float x;
        final float y;

        Vertex(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    static final class Stroke {
        final List<Vertex> points;
        final boolean input;

        Stroke(List<Vertex> points, boolean input) {
            this.points = points;
            this.input = input;
        }
    }

    static boolean usable(List<Sample> samples) {
        return samples.size() >= 2 && samples.stream().anyMatch(
                p -> Double.isFinite(p.soc) || Double.isFinite(p.kw));
    }

    static double powerLimit(List<Sample> samples) {
        double max = 0;
        for (Sample p : samples) if (Double.isFinite(p.kw)) max = Math.max(max, Math.abs(p.kw));
        if (max <= 10) return 10;
        if (max <= 20) return 20;
        int step = max <= 50 ? 10 : 20;
        return Math.ceil(max / step) * step;
    }

    static long tickStep(long span) {
        if (span <= 5_400_000) return 600_000;
        if (span <= 21_600_000) return 3_600_000;
        if (span <= 129_600_000) return 10_800_000;
        return span <= 691_200_000 ? 86_400_000 : 432_000_000;
    }

    static long tickStep(long span, long maxTicks) {
        long step = tickStep(span);
        return step * Math.max(1, (span / step + maxTicks - 1) / maxTicks);
    }

    static long firstTick(long time, long step, int utcOffsetMs) {
        long remainder = Math.floorMod(time + utcOffsetMs, step);
        return remainder == 0 ? time : time + step - remainder;
    }

    static long gapLimit(List<Sample> samples) {
        if (samples.size() < 2) return 600_000;
        long[] steps = new long[samples.size() - 1];
        for (int i = 0; i < steps.length; i++) steps[i] = samples.get(i + 1).time - samples.get(i).time;
        Arrays.sort(steps);
        return Math.max(600_000, 5 * steps[steps.length / 2]);
    }

    static int nearest(float[] xs, float x) {
        int lo = 0, hi = xs.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (xs[mid] < x) lo = mid + 1;
            else hi = mid;
        }
        if (lo == 0) return xs.length == 0 ? -1 : 0;
        if (lo == xs.length || x - xs[lo - 1] <= xs[lo] - x) {
            float previous = xs[lo - 1];
            while (lo > 1 && xs[lo - 2] == previous) lo--;
            return lo - 1;
        }
        return lo;
    }

    static List<Stroke> strokes(List<Sample> samples, Plot plot, boolean power, double limit) {
        long first = samples.get(0).time;
        long last = Math.max(first + 1, samples.get(samples.size() - 1).time);
        long gap = gapLimit(samples);
        long previous = first;
        boolean input = false;
        List<Stroke> strokes = new ArrayList<>();
        List<Vertex> points = new ArrayList<>();
        for (Sample p : samples) {
            double value = power ? p.kw : p.soc;
            if (!Double.isFinite(value)) continue;
            boolean nextInput = power && value < -0.5;
            Vertex vertex = new Vertex(plot.x(p.time, first, last), plot.y(value, power ? -limit : 0, limit));
            if (!points.isEmpty() && (p.time - previous > gap || input != nextInput)) {
                if (p.time - previous <= gap) points.add(vertex);
                if (points.size() > 1) strokes.add(new Stroke(points, input));
                points = new ArrayList<>();
            }
            points.add(vertex);
            input = nextInput;
            previous = p.time;
        }
        if (points.size() > 1) strokes.add(new Stroke(points, input));
        return strokes;
    }

    static final class Curve {
        final List<Sample> samples;
        final double lowSoc;
        final double highSoc;
        final double peakKw;

        Curve(List<Sample> samples, double lowSoc, double highSoc, double peakKw) {
            this.samples = samples;
            this.lowSoc = lowSoc;
            this.highSoc = highSoc;
            this.peakKw = peakKw;
        }

        List<Vertex> vertices(Plot plot) {
            List<Vertex> result = new ArrayList<>(samples.size());
            for (Sample p : samples) result.add(new Vertex(
                    plot.x(p.soc, lowSoc, highSoc), plot.y(-p.kw, 0, peakKw)));
            return result;
        }
    }

    static Curve chargeCurve(List<Sample> samples, Band session) {
        List<Sample> selected = new ArrayList<>();
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY, peak = 0.1;
        if (session != null) {
            for (Sample p : samples) {
                if (p.time < session.start || p.time > session.end || !Double.isFinite(p.soc)
                        || !Double.isFinite(p.kw) || p.kw >= 0) continue;
                selected.add(p);
                low = Math.min(low, p.soc);
                high = Math.max(high, p.soc);
                peak = Math.max(peak, -p.kw);
            }
        }
        return new Curve(selected, low, high, peak);
    }

    private ChartGeometry() { }
}

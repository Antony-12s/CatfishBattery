package com.catfish.battery;

import java.util.Arrays;

final class SchematicReadings {
    enum Band { WAIT, GOOD, WARN, DANGER }

    static final class Cells {
        private final double median;
        private final int minimum;
        private final int maximum;

        Cells(double median, int minimum, int maximum) {
            this.median = median;
            this.minimum = minimum;
            this.maximum = maximum;
        }

        double median() { return median; }
        int minimum() { return minimum; }
        int maximum() { return maximum; }

        static Cells of(double[] readings) {
            double[] sorted = Arrays.stream(readings).filter(Double::isFinite).sorted().toArray();
            int size = sorted.length;
            if (size == 0) return new Cells(Double.NaN, -1, -1);
            double median = (sorted[(size - 1) / 2] + sorted[size / 2]) / 2;
            if (sorted[size - 1] - sorted[0] <= 2) return new Cells(median, -1, -1);
            return new Cells(median, indexOf(readings, sorted[0]), indexOf(readings, sorted[size - 1]));
        }

        private static int indexOf(double[] readings, double value) {
            for (int i = 0; i < readings.length; i++) if (readings[i] == value) return i;
            return -1;
        }
    }

    static Band cellBand(double value, double median) {
        if (!Double.isFinite(value)) return Band.WAIT;
        double delta = Math.abs(value - median);
        if (value < 2800 || value > 3650 || delta > 8) return Band.DANGER;
        return delta > 2 ? Band.WARN : Band.GOOD;
    }

    static Band sensorBand(double value) {
        if (!Double.isFinite(value)) return Band.WAIT;
        if (value > 42) return Band.DANGER;
        return value >= 35 ? Band.WARN : Band.GOOD;
    }

    static int shade(int color, float factor) {
        return (color & 0xff000000)
                | Math.round(((color >> 16) & 255) * factor) << 16
                | Math.round(((color >> 8) & 255) * factor) << 8
                | Math.round((color & 255) * factor);
    }

    static String cellTip(int cell, double mv) {
        return "Cell " + UiFormat.fixed(cell,0,"") + " · "
                + (Double.isFinite(mv) ? UiFormat.fixed(mv / 1000,3," V") : "waiting");
    }

    static String sensorTip(int sensor, String name, double temp) {
        return "T" + UiFormat.fixed(sensor,0,"") + " · " + name + " · "
                + (Double.isFinite(temp) ? UiFormat.fixed(temp,0," °C") : "not seen yet");
    }

    /** BECM DID 496D comes in 0.01 % steps, canspi EgyCdn in 0.5 % steps: show only the digits the source has. */
    static String soh(double pct, String source) {
        return SohRecorder.BECM.equals(source) ? UiFormat.percent2(pct) : UiFormat.fixed(pct,1," %");
    }

    static String chargeNote(double kwh, double minutes) {
        return UiFormat.fixed(kwh,1," kWh") + " in · " + UiFormat.duration(minutes) + " left";
    }

    static String packPower(double watts) {
        double absolute = Math.abs(watts);
        return UiFormat.power(absolute) + (absolute >= 30 ? watts < 0 ? " IN" : " OUT" : "");
    }

    static double knownLoad(double value) { return Double.isFinite(value) ? Math.max(0,value) : 0; }

    private SchematicReadings() {}
}

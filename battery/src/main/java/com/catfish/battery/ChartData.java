package com.catfish.battery;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ChartData {
    static List<ChartGeometry.Sample> samples(JSONObject data) {
        JSONArray rows = data == null ? null : data.optJSONArray("points");
        List<ChartGeometry.Sample> samples = new ArrayList<>();
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null) continue;
            double time = number(row.opt(0));
            if (!validTime(time)) continue;
            samples.add(new ChartGeometry.Sample((long) time, number(row.opt(1)), number(row.opt(2)),
                    number(row.opt(3)), number(row.opt(4))));
        }
        samples.sort(Comparator.comparingLong(sample -> sample.time));
        return samples;
    }

    static ChartGeometry.Band band(JSONObject session) {
        if (session == null) return null;
        double start = number(session.opt("start"));
        double end = number(session.opt("end"));
        return validTime(start) && validTime(end) && end >= start
                ? new ChartGeometry.Band((long) start, (long) end) : null;
    }

    static List<ChartGeometry.Band> bands(JSONObject data) {
        List<ChartGeometry.Band> bands = new ArrayList<>();
        JSONArray sessions = data == null ? null : data.optJSONArray("sessions");
        for (int i = 0; sessions != null && i < sessions.length(); i++) {
            ChartGeometry.Band band = band(sessions.optJSONObject(i));
            if (band != null) bands.add(band);
        }
        ChartGeometry.Band live = band(data == null ? null : data.optJSONObject("live"));
        if (live != null) bands.add(live);
        return bands;
    }

    private static boolean validTime(double time) {
        return Double.isFinite(time) && Math.abs(time) <= 8_640_000_000_000_000L;
    }

    private static double number(Object value) {
        if (!(value instanceof Number)) return Double.NaN;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) ? number : Double.NaN;
    }

    private ChartData() { }
}

package com.catfish.battery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The native Cells view model: the 118 cell tiles in the four modules (rows P1 P4 / P2 P3, the cell ranges the schematic
 * draws) and the 12 sensor tiles, banded as the WebView drawPacks/drawSensorTiles did. Pure Java so it is unit tested.
 */
final class CellsModel {
    static final int WAIT = -1, GOOD = 0, WARN = 1, DANGER = 2;
    static final int CELLS = 118, SENSORS = 12;
    /** Name, first cell, cell count, in drawing order. Same table as BatteryWebPages.MODULES. */
    static final String[] MODULE_NAMES = { "P1", "P4", "P2", "P3" };
    static final int[][] MODULE_RANGES = { { 1, 30 }, { 90, 29 }, { 31, 30 }, { 61, 29 } };
    static final String[] SENSOR_NAMES = { "P1", "P2", "P3", "P4", "Coolant out", "Coolant in", "B+", "B\u2212", "DC+",
            "DC\u2212", "BMS board", "Air" };

    static final class Tile {
        final String label, text, mark;
        final int band;

        Tile(String label, String text, int band, String mark) {
            this.label = label;
            this.text = text;
            this.band = band;
            this.mark = mark;
        }
    }

    static final class Module {
        final String name, head;
        final List<Tile> tiles;

        Module(String name, String head, List<Tile> tiles) {
            this.name = name;
            this.head = head;
            this.tiles = tiles;
        }
    }

    final List<Module> modules;
    final List<Tile> sensors;

    private CellsModel(List<Module> modules, List<Tile> sensors) {
        this.modules = modules;
        this.sensors = sensors;
    }

    static CellsModel from(JSONObject data) {
        Map<Integer, Integer> cells = cellSamples(data);
        Map<Integer, Integer> temps = sensorTemps(data);
        List<Integer> sorted = new ArrayList<>(cells.values());
        Collections.sort(sorted);
        int n = sorted.size();
        double median = n == 0 ? 0 : (sorted.get((n - 1) / 2) + sorted.get(n / 2)) / 2.0;
        Marks cellMarks = new Marks(cells);
        List<Module> modules = new ArrayList<>();
        for (int m = 0; m < MODULE_NAMES.length; m++) {
            int first = MODULE_RANGES[m][0], count = MODULE_RANGES[m][1];
            List<Tile> tiles = new ArrayList<>();
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, seen = 0;
            long sum = 0;
            for (int c = first; c < first + count; c++) {
                Integer v = cells.get(c);
                if (v != null) {
                    seen++;
                    sum += v;
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
                tiles.add(new Tile(String.valueOf(c), v == null ? UiFormat.MISSING : String.valueOf(v),
                        v == null ? WAIT : cellBand(v, median), cellMarks.of(v)));
            }
            String head = "cells " + first + "\u2013" + (first + count - 1)
                    + (seen == 0 ? "" : " \u00b7 " + min + "\u2013" + max + " mV \u00b7 avg " + Math.round((double) sum / seen));
            modules.add(new Module(MODULE_NAMES[m], head, tiles));
        }
        Marks tempMarks = new Marks(temps);
        List<Tile> sensors = new ArrayList<>();
        for (int s = 1; s <= SENSORS; s++) {
            Integer v = temps.get(s);
            sensors.add(new Tile("S" + s + " \u00b7 " + SENSOR_NAMES[s - 1], StatusFormat.whole(v, " \u00b0C"),
                    v == null ? WAIT : tempBand(v), tempMarks.of(v)));
        }
        return new CellsModel(modules, sensors);
    }

    /** Out of the LFP cell bounds (below 2.8 V or above 3.65 V), as CELL_BAD in the WebView. */
    static boolean cellBad(int mv) {
        return mv < 2800 || mv > 3650;
    }

    /** Traffic light by distance from the pack median: 2 / 8 mV, or out of bounds. */
    static int cellBand(int mv, double median) {
        double d = Math.abs(mv - median);
        return cellBad(mv) || d > 8 ? DANGER : d > 2 ? WARN : GOOD;
    }

    /** Sensor bands: warn from 35 °C, danger above 42 °C. */
    static int tempBand(int c) {
        return c > 42 ? DANGER : c >= 35 ? WARN : GOOD;
    }

    /** Valid cell voltages by cell number (1-118, 2000-5000 mV). Not gated on the stream, like the WebView. */
    static Map<Integer, Integer> cellSamples(JSONObject data) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        JSONArray a = data == null ? null : data.optJSONArray("cells");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            Integer cell = whole(x.opt("cell")), mv = whole(x.opt("millivolts"));
            if (cell != null && cell >= 1 && cell <= CELLS && mv != null && mv >= 2000 && mv <= 5000) out.put(cell, mv);
        }
        return out;
    }

    /** Valid sensor temperatures by sensor number (1-12, -40..120 °C), only while the stream is up. */
    static Map<Integer, Integer> sensorTemps(JSONObject data) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        if (data == null || !data.optBoolean("streamConnected", false)) return out;
        JSONArray a = data.optJSONArray("cellTemps");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            Integer s = whole(x.opt("sensor")), t = whole(x.opt("tempC"));
            if (s != null && s >= 1 && s <= SENSORS && t != null && t >= -40 && t <= 120) out.put(s, t);
        }
        return out;
    }

    /** An integral JSON number, else null (Number.isInteger in the WebView). */
    static Integer whole(Object v) {
        if (!(v instanceof Number)) return null;
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d) || d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) return null;
        return (int) d;
    }

    /** ▼ / ▲ on the first lowest / highest value in drawing order, only when the set has a spread. */
    private static final class Marks {
        private final Integer lo, hi;
        private boolean doneLo, doneHi;

        Marks(Map<Integer, Integer> values) {
            Integer l = null, h = null;
            for (Integer v : values.values()) {
                if (l == null || v < l) l = v;
                if (h == null || v > h) h = v;
            }
            boolean spread = l != null && h > l;
            lo = spread ? l : null;
            hi = spread ? h : null;
        }

        String of(Integer v) {
            if (v == null || lo == null) return "";
            if (v.equals(lo) && !doneLo) { doneLo = true; return "\u25bc"; }
            if (v.equals(hi) && !doneHi) { doneHi = true; return "\u25b2"; }
            return "";
        }
    }
}

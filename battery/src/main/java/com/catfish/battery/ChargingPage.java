package com.catfish.battery;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Charging page, native port of the WebView Charging page: one card with the range (1h ... 30d) on top, the range's
 * six totals, the history chart with its legend, and beside it the live charge panel and the recent charges.
 * Data: BatteryDataHub.historyJson(range) and settingsJson() (for prices), read off the UI thread every 30 s.
 */
final class ChargingPage extends FrameLayout {
    private static final String TAG = "CatfishBattery";
    private static final long RELOAD_MS = 30000L;
    private static final String EMPTY = "{\"points\":[],\"sessions\":[],\"live\":null,\"stats\":{}}";

    private final ChargingUi ui;
    private final SharedPreferences prefs;
    private String range;
    private final List<TextView> rangeButtons = new ArrayList<>();
    private final TextView kicker, sideTitle, badge, costLine;
    private final List<TextView[]> tiles = new ArrayList<>();
    private final TextView kwNow, soc, kwhIn, peak, eta80, eta100;
    private final LinearLayout liveBox, sessions;
    private final HistoryChartView chart;
    private final ChargeCurveView curve;
    private boolean visible, loading;
    private long loadedAt;
    private int generation;
    private final Runnable tick = this::tick;

    ChargingPage(Context context) {
        super(context);
        ui = new ChargingUi(context);
        prefs = context.getSharedPreferences("battery_pages", Context.MODE_PRIVATE);
        range = ChargingModel.range(prefs.getString("historyRange", null));

        LinearLayout card = ui.card();
        addView(card, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // head: title + range kicker, range buttons on the right
        LinearLayout head = ui.row();
        head.setMinimumHeight(ui.dp(76));
        LinearLayout titles = ui.column();
        titles.addView(ui.title("Charging"));
        kicker = ui.kicker();
        titles.addView(kicker);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(rangeRail());
        LinearLayout.LayoutParams headLp = ChargingUi.fillWidth();
        headLp.bottomMargin = ui.dp(20);
        card.addView(head, headLp);

        // the range's totals
        LinearLayout stats = ui.row();
        if (!ui.night) stats.setBackground(ui.box(ui.panel, ui.panelEdge, 20));
        String[] labels = { "Used", "Charged", "Regen", "Cost", "SOC", "Cell diff" };
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) stats.addView(ui.divider(true));
            LinearLayout tile = ui.column();
            tile.setPadding(ui.dp(24), ui.dp(18), ui.dp(24), ui.dp(18));
            TextView small = ui.label(22), strong = ui.big(38, 46), em = ui.oneLine(ui.text(19, ui.muted2, 400));
            ChargingUi.set(small, labels[i]);
            ChargingUi.set(strong, UiFormat.MISSING);
            strong.setPadding(0, ui.dp(6), 0, ui.dp(4));
            tile.addView(small);
            tile.addView(strong);
            tile.addView(em);
            tiles.add(new TextView[] { small, strong, em });
            stats.addView(tile, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        LinearLayout.LayoutParams statsLp = ChargingUi.fillWidth();
        statsLp.bottomMargin = ui.dp(20);
        if (ui.night) {
            card.addView(ui.divider(false));
            statsLp.bottomMargin = 0;
            card.addView(stats, statsLp);
            View under = ui.divider(false);
            LinearLayout.LayoutParams ulp = ChargingUi.fillWidth();
            ulp.height = ui.dp(1);
            ulp.bottomMargin = ui.dp(20);
            card.addView(under, ulp);
        } else {
            card.addView(stats, statsLp);
        }

        // chart with legend | live panel and recent charges
        LinearLayout main = ui.row();
        main.setGravity(Gravity.NO_GRAVITY);
        card.addView(main, ChargingUi.fillHeight());

        LinearLayout chartBox = ui.column();
        ui.panel(chartBox);
        chartBox.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20));
        chart = new HistoryChartView(context);
        chart.setNight(ui.night);
        chartBox.addView(chart, ChargingUi.fillHeight());
        chartBox.addView(legend());
        main.addView(chartBox, ChargingUi.weight(1));
        main.addView(ui.space(24, 1));

        LinearLayout side = ui.column();
        ui.panel(side);
        side.setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24));
        main.addView(side, new LinearLayout.LayoutParams(ui.dp(580), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout sideHead = ui.row();
        sideTitle = ui.oneLine(ui.text(26, ui.text, 700));
        sideHead.addView(sideTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        badge = ui.badge();
        sideHead.addView(badge);
        side.addView(sideHead, ChargingUi.fillWidth());

        liveBox = ui.column();
        liveBox.setVisibility(GONE);
        liveBox.setPadding(0, ui.dp(8), 0, ui.dp(8));
        TextView[] f = new TextView[6];
        String[] factLabels = { "Power now", "SOC now → target", "Energy in", "Peak power", "ETA to 80%", "ETA to 100%" };
        costLine = ui.oneLine(ui.text(20, ui.muted, 500));
        for (int r = 0; r < 3; r++) {
            LinearLayout line = ui.row();
            line.setGravity(Gravity.NO_GRAVITY);
            for (int c = 0; c < 2; c++) {
                int i = r * 2 + c;
                if (c == 1) line.addView(ui.divider(true));
                LinearLayout fact = ui.column();
                fact.setPadding(c == 0 ? 0 : ui.dp(12), ui.dp(14), c == 0 ? ui.dp(12) : 0, ui.dp(14));
                TextView label = ui.oneLine(ui.text(21, ui.muted, 600));
                ChargingUi.set(label, factLabels[i]);
                f[i] = ui.oneLine(ui.text(32, ui.text, 650));
                f[i].setPadding(0, ui.dp(4), 0, 0);
                ChargingUi.set(f[i], UiFormat.MISSING);
                fact.addView(label);
                fact.addView(f[i]);
                if (i == 2) fact.addView(costLine);
                line.addView(fact, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            liveBox.addView(line, ChargingUi.fillWidth());
            liveBox.addView(ui.divider(false));
        }
        kwNow = f[0]; soc = f[1]; kwhIn = f[2]; peak = f[3]; eta80 = f[4]; eta100 = f[5];
        curve = new ChargeCurveView(context);
        curve.setNight(ui.night);
        curve.setContentDescription("Charge power versus SOC");
        LinearLayout.LayoutParams curveLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(130));
        curveLp.topMargin = ui.dp(12);
        liveBox.addView(curve, curveLp);
        side.addView(liveBox, ChargingUi.fillWidth());

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        sessions = ui.column();
        scroll.addView(sessions, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        side.addView(scroll, ChargingUi.fillHeight());

        markRange();
        ChargingUi.set(kicker, ChargingModel.rangeLabel(range));
        ui.badge(badge, false);
        ChargingUi.set(sideTitle, "Recent charges");
    }

    /** Called when the page becomes visible and then periodically; reloads at most every 30 s. */
    void refresh() {
        if (System.currentTimeMillis() - loadedAt >= RELOAD_MS) load();
    }

    void setVisibleNow(boolean visible) {
        this.visible = visible;
        removeCallbacks(tick);
        if (visible) { load(); postDelayed(tick, RELOAD_MS); }
    }

    private void tick() {
        if (!visible) return;
        refresh();
        postDelayed(tick, RELOAD_MS);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void setRange(String r) {
        range = ChargingModel.range(r);
        prefs.edit().putString("historyRange", range).apply();
        markRange();
        ChargingUi.set(kicker, ChargingModel.rangeLabel(range));
        loading = false; // a range change outranks the load in flight; its result is dropped by generation
        load();
    }

    private void load() {
        if (loading) return;
        loading = true;
        final int gen = ++generation;
        final String r = range;
        ChargingUi.background(() -> {
            String history, settings;
            try { history = BatteryDataHub.historyJson(r); }
            catch (RuntimeException e) { Log.e(TAG, "Charging history unavailable", e); history = EMPTY; }
            try { settings = BatteryDataHub.settingsJson(); }
            catch (RuntimeException e) { Log.e(TAG, "Battery settings unavailable", e); settings = null; }
            final String h = history, s = settings;
            post(() -> {
                if (gen != generation) return;
                loading = false;
                loadedAt = System.currentTimeMillis();
                render(h, s);
            });
        });
    }

    private void render(String historyText, String settingsText) {
        JSONObject history, settings = null;
        try { history = new JSONObject(historyText); }
        catch (JSONException e) { history = new JSONObject(); }
        try { if (settingsText != null) settings = new JSONObject(settingsText).optJSONObject("settings"); }
        catch (JSONException e) { settings = null; }
        TimeZone tz = TimeZone.getDefault();
        ChargingCost cost = new ChargingCost(settings, tz);

        List<ChargingModel.Tile> t = ChargingModel.statsTiles(history.optJSONObject("stats"), cost);
        for (int i = 0; i < t.size() && i < tiles.size(); i++) {
            TextView[] v = tiles.get(i);
            ChargingUi.set(v[1], t.get(i).value);
            ChargingUi.set(v[2], t.get(i).sub);
            v[1].setTextColor("good".equals(t.get(i).tone) ? ui.good : ui.text);
        }
        ChargingUi.set(kicker, ChargingModel.kicker(range, history.optJSONArray("points"), tz));
        chart.setData(history);

        JSONObject live = history.optJSONObject("live");
        ui.badge(badge, live != null);
        ChargingUi.set(sideTitle, live != null ? "Charging now" : "Recent charges");
        liveBox.setVisibility(live != null ? VISIBLE : GONE);
        if (live != null) {
            ChargingModel.Live f = ChargingModel.live(live, cost);
            ChargingUi.set(kwNow, f.kwNow);
            ChargingUi.set(soc, f.soc);
            ChargingUi.set(kwhIn, f.kwhIn);
            ChargingUi.set(costLine, f.cost);
            costLine.setVisibility(f.cost.isEmpty() ? GONE : VISIBLE);
            ChargingUi.set(peak, f.peak);
            ChargingUi.set(eta80, f.eta80);
            ChargingUi.set(eta100, f.eta100);
            curve.setData(history);
        }

        sessions.removeAllViews();
        List<ChargingModel.Row> rows = ChargingModel.recent(history.optJSONArray("sessions"), live != null, cost, tz);
        if (rows.isEmpty()) {
            TextView none = ui.text(24, ui.muted, 400);
            none.setLineSpacing(0, 1.3f);
            none.setPadding(0, ui.dp(16), 0, 0);
            ChargingUi.set(none, "No charge recorded yet; the full list is on the History tab.");
            sessions.addView(none, ChargingUi.fillWidth());
        }
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sessions.addView(ui.divider(false));
            sessions.addView(sessionRow(rows.get(i)), ChargingUi.fillWidth());
        }
    }

    private View sessionRow(ChargingModel.Row r) {
        LinearLayout box = ui.column();
        box.setPadding(0, ui.dp(20), 0, ui.dp(20));
        LinearLayout top = ui.row();
        top.addView(ui.tag(r.tag, r.ac, r.dc), new LinearLayout.LayoutParams(ui.dp(60), ui.dp(42)));
        LinearLayout when = ui.column();
        when.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        TextView b = ui.oneLine(ui.text(24, ui.text, 700)), small = ui.oneLine(ui.text(20, ui.muted, 400));
        small.setPadding(0, ui.dp(4), 0, 0);
        ChargingUi.set(b, r.when);
        ChargingUi.set(small, r.sub);
        when.addView(b);
        when.addView(small);
        top.addView(when, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView c = ui.text(26, ui.text, 700);
        ChargingUi.set(c, r.cost);
        top.addView(c);
        box.addView(top, ChargingUi.fillWidth());
        LinearLayout track = ui.track(8);
        ui.track(track, r.trackFrom, r.trackTo);
        LinearLayout.LayoutParams tlp = (LinearLayout.LayoutParams) track.getLayoutParams();
        tlp.topMargin = ui.dp(12);
        box.addView(track, tlp);
        LinearLayout meta = ui.row();
        meta.setPadding(0, ui.dp(10), 0, 0);
        for (String m : r.meta) {
            TextView v = ui.oneLine(ui.text(20, ui.muted, 500));
            v.setPadding(0, 0, ui.dp(18), 0);
            ChargingUi.set(v, m);
            meta.addView(v);
        }
        box.addView(meta, ChargingUi.fillWidth());
        return box;
    }

    private View rangeRail() {
        LinearLayout rail = ui.row();
        rail.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));
        rail.setBackground(ui.night ? ui.box(0, 0x2EE2C08D, 999) : ui.box(ui.panel, ui.panelEdge, 20));
        rail.setContentDescription("Chart range");
        for (String r : ChargingModel.RANGES) {
            TextView b = ui.text(24, ui.muted, 700);
            b.setGravity(Gravity.CENTER);
            b.setMinWidth(ui.dp(92));
            b.setMinHeight(ui.dp(76));
            b.setPadding(ui.dp(26), ui.dp(12), ui.dp(26), ui.dp(12));
            ChargingUi.set(b, r);
            b.setClickable(true);
            b.setFocusable(true);
            b.setOnClickListener(v -> setRange(r));
            if (!rangeButtons.isEmpty()) rail.addView(ui.space(23, 1));
            rail.addView(b);
            rangeButtons.add(b);
        }
        return rail;
    }

    private void markRange() {
        for (TextView b : rangeButtons) {
            boolean on = range.contentEquals(b.getText());
            ui.pill(b, on, ui.night ? 999 : 15);
            b.setSelected(on);
        }
    }

    private View legend() {
        LinearLayout l = ui.row();
        l.setPadding(0, ui.dp(16), 0, 0);
        Object[][] items = {
                { "SOC % (left)", ui.socLine, 4 }, { "kW out of the pack (right)", ui.kwOut, 4 },
                { "kW into the pack", ui.good, 4 }, { "Charging", (ui.good & 0x00FFFFFF) | 0x4D000000, 10 } };
        for (Object[] it : items) {
            View sw = new View(getContext());
            sw.setBackground(ui.box((Integer) it[1], 0, 2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(16), ui.dp((Integer) it[2]));
            lp.rightMargin = ui.dp(8);
            l.addView(sw, lp);
            TextView t = ui.text(20, ui.muted, 600);
            t.setPadding(0, 0, ui.dp(24), 0);
            ChargingUi.set(t, (String) it[0]);
            l.addView(t);
        }
        return l;
    }
}

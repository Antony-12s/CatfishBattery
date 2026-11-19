package com.catfish.battery;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.TimeZone;

/**
 * Battery health: the BECM SOH and how it falls with time, distance and cell spread (soh.csv). Simple shows the
 * number, a verdict and plain rates; Expert shows the three trend charts and the latest readings. Rates appear only
 * once HealthModel has enough data; before that the page says it is collecting.
 */
final class HealthPage extends FrameLayout {
    private static final String TAG = "CatfishBattery";
    private static final String PREF_VIEW = "health_view";
    private static final int TABLE_ROWS = 6;

    private final ChargingUi ui;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final TextView simpleTab, expertTab;
    private final View simple, expert;
    private final HealthChartView.Ring ring;
    private final TextView soh, verdict, meta, rateKm, rateYear, projection, collectHead, collectBody, sinceNew;
    private final View rates, collect;
    private final LinearLayout collectTrack;
    private final HealthChartView byTime, byKm, byDiff;
    private final LinearLayout table;
    private boolean expertShown, visible;

    HealthPage(Context context) {
        super(context);
        ui = new ChargingUi(context);
        prefs = context.getSharedPreferences("battery_ui", Context.MODE_PRIVATE);
        LinearLayout card = ui.card();
        addView(card, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout head = ui.row();
        head.setMinimumHeight(ui.dp(76));
        LinearLayout titles = ui.column();
        titles.addView(ui.title("Battery health"));
        TextView kicker = ui.kicker();
        ChargingUi.set(kicker, "SOH from the BECM, once a day in Park · tap to read now");
        kicker.setOnClickListener(v -> {
            BatteryDataHub.readBecmSohNow();
            android.widget.Toast.makeText(context, "Reading the BECM (Park only)…", android.widget.Toast.LENGTH_SHORT).show();
        });
        titles.addView(kicker);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout rail = ui.row();
        rail.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));
        rail.setBackground(ui.box(ui.panel, ui.panelEdge, 999));
        simpleTab = toggle("Simple");
        expertTab = toggle("Expert");
        rail.addView(simpleTab);
        rail.addView(ui.space(12, 1));
        rail.addView(expertTab);
        simpleTab.setOnClickListener(v -> showExpert(false));
        expertTab.setOnClickListener(v -> showExpert(true));
        head.addView(rail);
        LinearLayout.LayoutParams headLp = ChargingUi.fillWidth();
        headLp.bottomMargin = ui.dp(24);
        card.addView(head, headLp);

        FrameLayout body = new FrameLayout(context);
        card.addView(body, ChargingUi.fillHeight());

        // Simple: the number on the left, the rates (or the collecting note) on the right
        LinearLayout simpleRow = ui.row();
        simpleRow.setGravity(Gravity.NO_GRAVITY);
        LinearLayout left = ui.column();
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        left.setPadding(ui.dp(28), ui.dp(28), ui.dp(28), ui.dp(28));
        ui.panel(left);
        FrameLayout dial = new FrameLayout(context);
        ring = new HealthChartView.Ring(context, ui);
        dial.addView(ring, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout center = ui.column();
        center.setGravity(Gravity.CENTER);
        soh = ui.big(96, 104);
        verdict = ui.text(30, ui.good, 650);
        center.addView(soh, ChargingUi.wrap());
        center.addView(verdict, ChargingUi.wrap());
        dial.addView(center, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        left.addView(dial, new LinearLayout.LayoutParams(ui.dp(520), 0, 1f));
        meta = ui.text(22, ui.muted, 400);
        meta.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams metaLp = ChargingUi.fillWidth();
        metaLp.topMargin = ui.dp(16);
        left.addView(meta, metaLp);
        simpleRow.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        simpleRow.addView(ui.space(24, 1));

        FrameLayout right = new FrameLayout(context);
        LinearLayout rateCol = ui.column();
        rateKm = tile(rateCol, "PER DISTANCE");
        rateYear = tile(rateCol, "PER TIME");
        projection = tile(rateCol, "PROJECTION");
        rates = rateCol;
        right.addView(rateCol, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout collectCol = ui.column();
        collectCol.setGravity(Gravity.CENTER_VERTICAL);
        collectCol.setPadding(ui.dp(32), ui.dp(28), ui.dp(32), ui.dp(28));
        ui.panel(collectCol);
        TextView collectKicker = ui.label(22);
        ChargingUi.set(collectKicker, "COLLECTING DATA");
        collectCol.addView(collectKicker);
        collectHead = ui.big(48, 52);
        collectCol.addView(collectHead, margins(ChargingUi.fillWidth(), 12));
        collectTrack = ui.track(14);
        ((LinearLayout.LayoutParams) collectTrack.getLayoutParams()).topMargin = ui.dp(20);
        collectCol.addView(collectTrack);
        collectBody = ui.text(24, ui.muted, 400);
        ChargingUi.set(collectBody, "A rate needs readings over " + HealthModel.MIN_DAYS
                + " days and at least one SOH change. The BECM is read once a day when the app opens in Park.");
        collectCol.addView(collectBody, margins(ChargingUi.fillWidth(), 14));
        // a bare View with WRAP_CONTENT height measures to all the space left; keep the divider's own 1 dp height
        View rule = ui.divider(false);
        ((LinearLayout.LayoutParams) rule.getLayoutParams()).topMargin = ui.dp(28);
        collectCol.addView(rule);
        TextView sinceKicker = ui.label(22);
        ChargingUi.set(sinceKicker, "SINCE NEW · ASSUMES 100 % AT 0 KM");
        collectCol.addView(sinceKicker, margins(ChargingUi.fillWidth(), 28));
        sinceNew = ui.text(34, ui.text, 650);
        collectCol.addView(sinceNew, margins(ChargingUi.fillWidth(), 12));
        collect = collectCol;
        right.addView(collectCol, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        simpleRow.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        simple = simpleRow;
        body.addView(simpleRow, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Expert: three trend charts over the latest readings
        LinearLayout expertCol = ui.column();
        LinearLayout charts = ui.row();
        charts.setGravity(Gravity.NO_GRAVITY);
        byTime = new HealthChartView(context, ui, "SOH vs date", "", 365.2425, "%/year", "");
        byKm = new HealthChartView(context, ui, "SOH vs distance", "", 10_000, "%/10k km", "");
        byDiff = new HealthChartView(context, ui, "SOH vs cell spread", "V diff", 1, "%/mV", "correlation, not cause");
        HealthChartView[] all = { byTime, byKm, byDiff };
        for (int i = 0; i < all.length; i++) {
            if (i > 0) charts.addView(ui.space(20, 1));
            ui.panel(all[i]);
            charts.addView(all[i], ChargingUi.weight(1));
        }
        expertCol.addView(charts, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.25f));
        table = ui.column();
        table.setPadding(ui.dp(24), ui.dp(14), ui.dp(24), ui.dp(14));
        ui.panel(table);
        LinearLayout.LayoutParams tableLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        tableLp.topMargin = ui.dp(20);
        expertCol.addView(table, tableLp);
        expert = expertCol;
        body.addView(expertCol, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        showExpert("expert".equals(prefs.getString(PREF_VIEW, "simple")));
    }

    void setVisibleNow(boolean visible) { this.visible = visible; }

    /** Reads soh.csv off the UI thread; called when the page is shown (SOH changes at most once a day). */
    void refresh() {
        ChargingUi.background(() -> {
            try {
                List<SohRecorder.Entry> rows = BatteryDataHub.sohEntries();
                HealthModel model = HealthModel.from(rows, System.currentTimeMillis(), TimeZone.getDefault());
                main.post(() -> { if (visible) render(model); });
            } catch (RuntimeException e) {
                Log.e(TAG, "Health page load failed", e);
            }
        });
    }

    private void render(HealthModel m) {
        ring.setPct(m.latest);
        ChargingUi.set(soh, m.sohText);
        ChargingUi.set(verdict, m.verdict);
        verdict.setTextColor(m.latest >= 90 ? ui.good : m.latest >= 80 ? ui.warn : Double.isNaN(m.latest) ? ui.muted : ui.danger);
        ChargingUi.set(meta, (m.source.isEmpty() ? "No reading yet" : m.source + " · " + m.readAt) + " · " + m.kmText);
        rates.setVisibility(m.enough ? View.VISIBLE : View.GONE);
        collect.setVisibility(m.enough ? View.GONE : View.VISIBLE);
        ChargingUi.set(rateKm, m.per10kText);
        ChargingUi.set(rateYear, m.perYearText);
        ChargingUi.set(projection, m.projection.isEmpty() ? "No drop yet" : m.projection);
        ChargingUi.set(collectHead, m.collecting);
        ui.track(collectTrack, 0, 100.0 * m.collectDays / HealthModel.MIN_DAYS);
        ChargingUi.set(sinceNew, m.sinceNew.isEmpty() ? UiFormat.MISSING : m.sinceNew);

        String d0 = m.table.isEmpty() ? "" : m.table.get(m.table.size() - 1)[0].split(" ")[0] + " " + m.table.get(m.table.size() - 1)[0].split(" ")[1];
        String d1 = m.table.isEmpty() ? "" : m.table.get(0)[0].split(" ")[0] + " " + m.table.get(0)[0].split(" ")[1];
        byTime.setNote(m.roughNote);
        byKm.setNote(m.roughNote);
        byTime.setData(m.days, m.pctsAll, m.byTime, d0, d1);
        byKm.setData(m.kms, m.pctsKm, m.byKm, kmLabel(m.kms, true), kmLabel(m.kms, false));
        byDiff.setData(m.diffs, m.pctsDiff, m.byDiff, mvLabel(m.diffs, true), mvLabel(m.diffs, false));

        table.removeAllViews();
        table.addView(tableRow(new String[]{"Date", "SOH", "Source", "Odometer", "V diff"}, true));
        for (int i = 0; i < Math.min(TABLE_ROWS, m.table.size()); i++) {
            table.addView(ui.divider(false));
            table.addView(tableRow(m.table.get(i), false));
        }
        if (m.table.isEmpty()) {
            TextView none = ui.text(22, ui.muted, 400);
            ChargingUi.set(none, "No SOH reading yet: open the app once in Park.");
            table.addView(none, margins(ChargingUi.fillWidth(), 16));
        }
    }

    private LinearLayout tableRow(String[] cells, boolean header) {
        LinearLayout row = ui.row();
        row.setMinimumHeight(ui.dp(header ? 44 : 50));
        float[] weights = { 1.3f, 1f, 1f, 1.1f, 0.9f };
        for (int i = 0; i < cells.length; i++) {
            TextView t;
            if (!header && i == 2) {
                t = ui.tag(cells[i], "BECM".equals(cells[i]), false);
                t.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4));
                LinearLayout holder = ui.row();
                holder.addView(t, ChargingUi.wrap());
                row.addView(holder, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weights[i]));
                continue;
            }
            t = ui.oneLine(header ? ui.label(20) : ui.text(23, ui.text, i == 1 ? 700 : 400));
            ChargingUi.set(t, cells[i]);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weights[i]));
        }
        return row;
    }

    private TextView tile(LinearLayout column, String label) {
        LinearLayout tile = ui.column();
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(ui.dp(32), ui.dp(20), ui.dp(32), ui.dp(20));
        ui.panel(tile);
        TextView k = ui.label(22);
        ChargingUi.set(k, label);
        tile.addView(k);
        TextView value = ui.text(40, ui.text, 650);
        tile.addView(value, margins(ChargingUi.fillWidth(), 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        if (column.getChildCount() > 0) lp.topMargin = ui.dp(20);
        column.addView(tile, lp);
        return value;
    }

    private TextView toggle(String label) {
        TextView t = ui.text(24, ui.muted, 650);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(ui.dp(60));
        t.setPadding(ui.dp(28), 0, ui.dp(28), 0);
        ChargingUi.set(t, label);
        t.setClickable(true);
        return t;
    }

    private void showExpert(boolean on) {
        expertShown = on;
        simple.setVisibility(on ? View.GONE : View.VISIBLE);
        expert.setVisibility(on ? View.VISIBLE : View.GONE);
        ui.pill(simpleTab, !on, 999);
        ui.pill(expertTab, on, 999);
        prefs.edit().putString(PREF_VIEW, on ? "expert" : "simple").apply();
    }

    private static String kmLabel(double[] kms, boolean low) {
        if (kms.length == 0) return "";
        double v = low ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        for (double k : kms) v = low ? Math.min(v, k) : Math.max(v, k);
        return String.format(java.util.Locale.US, "%,.0f km", v);
    }

    private static String mvLabel(double[] mv, boolean low) {
        if (mv.length == 0) return "";
        double v = low ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        for (double k : mv) v = low ? Math.min(v, k) : Math.max(v, k);
        return UiFormat.fixed(v, 0, " mV");
    }

    private static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams lp, int topDp) {
        lp.topMargin = Math.round(topDp * android.content.res.Resources.getSystem().getDisplayMetrics().density);
        return lp;
    }
}

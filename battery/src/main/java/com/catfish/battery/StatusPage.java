package com.catfish.battery;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

/**
 * Status page: the Pack card (title, live badge, Schematic/Cells switch, cell over sensor counters, two-tap
 * Reset totals), the schematic with its energy-flow overlay or the Cells view, the legend, and the facts rows with the
 * energy totals. Native port of the WebView status page (BatteryWebPages renderBattery / batteryScript). The SOH badge
 * left the header: SOH is drawn in the schematic's HV PACK block (backlog 2).
 */
final class StatusPage extends FrameLayout {
    private static final String TAG = "CatfishBattery";
    private static final String PREFS = "status_page", PREF_VIEW = "packView";
    /** The WebView's caption (hidden on the car window: BatteryWindowStyle set .packCaption display:none); kept for a11y. */
    static final String CAPTION = "Positions are illustrative: the vehicle does not report where a cell or sensor sits. "
            + "Colours are the distance from the pack median. Loads are HvCabinThermPwrCns, HvBattThermPwrCns and "
            + "HvConvPwrAct; Motor is IsgUDc \u00d7 IsgIDc; Other is the balance. Used, charged and regen are totals the app "
            + "integrates from the pack power every 10 s while the head unit is on, from boot, until Reset totals. Health is "
            + "the BMS state of health (HvBattEgyCdn, 0.5 % steps; it matched the BECM's dealer SOH), saved once each time "
            + "the car is parked, and shown in the HV PACK block.";

    private final StatusStyle s;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final StatusFreshness freshness = new StatusFreshness();
    private final StatusResetArming arming = new StatusResetArming();
    private final SharedPreferences prefs;

    private final TextView liveBadge, resetButton, tabSchematic, tabCells, cellCount, sensorCount;
    private final TextView cellMax, cellMin, cellDelta, tMax, tMin, tSpread, used, charged, regen, cost, since, costNote;
    private final TextView legendCells, legendSensors;
    private final View packWrap, legend, schematicPanel;
    private final PackSchematicView schematic;
    private final FlowOverlayView flow;
    private final CellsView cells;

    private boolean visible, cellsShown;
    private int liveColor;
    private JSONObject lastData, settings, history;
    private long historyAt;

    StatusPage(Context context) {
        super(context);
        s = new StatusStyle(context);
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(s.card());
        card.setPadding(s.px(28), s.px(24), s.px(28), s.px(24));
        addView(card, match());

        // header: title on the left, the actions on the right
        LinearLayout head = new LinearLayout(context);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(s.px(76));
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = s.night ? s.text(context, 34, 500, s.text) : s.text(context, 32, 700, s.text);
        title.setText(R.string.status_pack_title);
        titles.addView(title);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        resetButton = s.text(context, 22, 700, s.muted);
        resetButton.setText(StatusResetArming.IDLE);
        resetButton.setGravity(Gravity.CENTER);
        resetButton.setMinHeight(s.px(76));
        resetButton.setPadding(s.px(24), s.px(10), s.px(24), s.px(10));
        resetButton.setBackground(pressable(s.round(0, 16, s.line, 1)));
        resetButton.setClickable(true);
        resetButton.setOnClickListener(v -> onReset());
        liveBadge = badge(context, s.good);
        LinearLayout tabs = new LinearLayout(context);
        tabs.setPadding(s.px(6), s.px(6), s.px(6), s.px(6));
        tabs.setBackground(s.night ? s.round(0, 999, StatusStyle.alpha(s.accent, 0.18f), 1) : s.round(s.bgDeep, 20, s.line, 1));
        tabSchematic = tab(context, "Schematic");
        tabCells = tab(context, "Cells");
        tabs.addView(tabSchematic);
        tabs.addView(tabCells, margins(wrap(), s.px(23), 0, 0, 0));
        tabSchematic.setOnClickListener(v -> showCells(false, true));
        tabCells.setOnClickListener(v -> showCells(true, true));
        cellCount = badge(context, s.accent);
        sensorCount = badge(context, s.accent);
        cellCount.setContentDescription("Cell voltages collected");
        sensorCount.setContentDescription("Temperature sensors seen");
        // cells over sensors, right-aligned, compact so the stack stays within the header height
        LinearLayout counts = new LinearLayout(context);
        counts.setOrientation(LinearLayout.VERTICAL);
        counts.setGravity(Gravity.END);
        for (TextView count : new TextView[]{ cellCount, sensorCount }) {
            count.setMinHeight(s.px(36));
            count.setPadding(s.px(16), s.px(3), s.px(16), s.px(3));
        }
        counts.addView(cellCount, wrap());
        counts.addView(sensorCount, margins(wrap(), 0, s.px(6), 0, 0));
        View[] actions = { resetButton, liveBadge, tabs, counts };
        for (int i = 0; i < actions.length; i++) head.addView(actions[i], margins(wrap(), i == 0 ? 0 : s.px(18), 0, 0, 0));
        card.addView(head, margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, 0, 0, s.px(20)));

        // body: the schematic (pack + flow overlay + legend) or the Cells view
        FrameLayout body = new FrameLayout(context);
        LinearLayout schem = new LinearLayout(context);
        schem.setOrientation(LinearLayout.VERTICAL);
        FrameLayout wrap = new FrameLayout(context);
        // no panel here: PackSchematicView paints its own themed panel; a second one drew a double frame
        wrap.setClipToOutline(true);
        wrap.setContentDescription(CAPTION);
        // the flow dots must sit exactly over the drawing: both fill the same FrameLayout and share its 1180x470 mapping
        FrameLayout packBox = new FrameLayout(context);
        schematic = new PackSchematicView(context);
        flow = new FlowOverlayView(context);
        schematic.setNight(s.night);
        flow.setNight(s.night);
        packBox.addView(schematic, match());
        packBox.addView(flow, match());
        wrap.addView(packBox, match());
        schem.addView(wrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout leg = new LinearLayout(context);
        legendCells = s.text(context, 20, 600, s.muted);
        legendSensors = s.text(context, 20, 600, s.muted);
        leg.addView(scale(context, legendCells, new String[] { "\u22642 mV", "\u22648 mV", ">8 mV" }),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        leg.addView(scale(context, legendSensors, new String[] { "<35 \u00b0C", "35\u201342 \u00b0C", ">42 \u00b0C" }),
                margins(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1), s.px(24), 0, 0, 0));
        schem.addView(leg, margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, s.px(14), 0, 0));
        cells = new CellsView(context);
        body.addView(schem, match());
        body.addView(cells, match());
        card.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        packWrap = wrap;
        legend = leg;
        schematicPanel = schem;

        // facts: cells, sensors, energy totals (3 : 3 : 4.5 like the window CSS)
        LinearLayout facts = new LinearLayout(context);
        facts.setBaselineAligned(false);
        LinearLayout cellGroup = group(context), tempGroup = group(context), energyGroup = group(context);
        cellMax = fact(context, cellGroup, "Max", null);
        cellMin = fact(context, cellGroup, "Min", null);
        cellDelta = fact(context, cellGroup, "Diff", null);
        tMax = fact(context, tempGroup, "T max", null);
        tMin = fact(context, tempGroup, "T min", null);
        tSpread = fact(context, tempGroup, "Spread", null);
        since = note(context, "from boot");
        used = fact(context, energyGroup, "Used", since);
        charged = fact(context, energyGroup, "Charged", note(context, "charger \u2192 pack"));
        regen = fact(context, energyGroup, "Regen", note(context, "braking energy"));
        regen.setTextColor(s.good);
        costNote = note(context, "set prices in Settings");
        cost = fact(context, energyGroup, "Cost", costNote);
        facts.addView(cellGroup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3));
        facts.addView(tempGroup, margins(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3), s.px(18), 0, 0, 0));
        facts.addView(energyGroup, margins(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 4.5f), s.px(18), 0, 0, 0));
        card.addView(facts, margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, s.px(18), 0, 0));

        showCells("cells".equals(prefs.getString(PREF_VIEW, "schematic")), false);
        draw(new JSONObject());
    }

    /** Called about once a second while visible with BatteryDataHub.dataJson(). */
    void refresh(JSONObject batteryData) {
        if (batteryData == null) return;
        lastData = batteryData;
        draw(batteryData);
    }

    void setVisibleNow(boolean visible) {
        this.visible = visible;
        flow.setRunning(visible && !cellsShown);
        if (visible) loadSettings();
    }

    @Override protected void onDetachedFromWindow() {
        flow.setRunning(false);
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDetachedFromWindow();
    }

    private void draw(JSONObject data) {
        StatusFreshness.State f = freshness.update(data.optBoolean("streamConnected", false),
                data.optLong("lastUpdatedMs", 0), System.currentTimeMillis());
        set(liveBadge, f.label);
        int live = f.kind == StatusFreshness.LIVE ? s.good : f.kind == StatusFreshness.STALE ? s.warn : s.night ? s.muted : s.danger;
        if (liveColor != live) {
            liveColor = live;
            liveBadge.setTextColor(live);
            liveBadge.setBackground(badgeBg(live, f.kind == StatusFreshness.OFFLINE && s.night));
            liveBadge.setCompoundDrawablesRelativeWithIntrinsicBounds(dot(live), null, null, null);
        }
        float alpha = f.dim ? 0.6f : 1f;
        packWrap.setAlpha(alpha);
        legend.setAlpha(alpha);
        cells.setAlpha(alpha);

        StatusFacts x = StatusFacts.from(data, settings, TimeZone.getDefault());
        set(cellCount, x.cellCount);
        set(sensorCount, x.sensorCount);
        set(cellMax, x.cellMax);
        set(cellMin, x.cellMin);
        set(cellDelta, x.cellDelta);
        cellDelta.setTextColor(x.deltaLevel == StatusFormat.DANGER ? s.danger : x.deltaLevel == StatusFormat.WARN ? s.warn : s.good);
        set(tMax, x.tempMax);
        set(tMin, x.tempMin);
        set(tSpread, x.tempSpread);
        set(used, x.used);
        set(charged, x.charged);
        set(regen, x.regen);
        set(cost, x.cost);
        set(costNote, x.costNote);
        set(since, x.since);
        legendCells.setText(scaleText("Cells", x.legendVmin, x.legendVmax));
        legendSensors.setText(scaleText("Sensors", x.legendTmin, x.legendTmax));

        if (cellsShown) cells.setModel(CellsModel.from(data));
        else {
            long now = System.currentTimeMillis();
            if (now - historyAt > 30_000) loadHistory(now); // the sparkline's points: the log, read every 30 s
            schematic.setTrend(history == null ? null : SocTrend.of(history, data, now, TimeZone.getDefault()));
            schematic.setData(data);
            flow.setData(data);
        }
    }

    private void showCells(boolean on, boolean save) {
        cellsShown = on;
        schematicPanel.setVisibility(on ? View.GONE : View.VISIBLE);
        cells.setVisibility(on ? View.VISIBLE : View.GONE);
        styleTab(tabSchematic, !on);
        styleTab(tabCells, on);
        flow.setRunning(visible && !on);
        if (save) prefs.edit().putString(PREF_VIEW, on ? "cells" : "schematic").apply();
        if (lastData != null) draw(lastData);
    }

    private void onReset() {
        long now = SystemClock.elapsedRealtime();
        boolean confirmed = arming.tap(now);
        resetButton.setText(arming.label(now));
        main.removeCallbacks(resetLabel);
        if (!confirmed) {
            main.postDelayed(resetLabel, StatusResetArming.ARM_MS);
            return;
        }
        submit(() -> {
            BatteryDataHub.resetTotals();
            JSONObject fresh = new JSONObject(BatteryDataHub.dataJson());
            main.post(() -> refresh(fresh));
        });
    }

    private final Runnable resetLabel = this::updateResetLabel;

    private void updateResetLabel() {
        resetButton.setText(arming.label(SystemClock.elapsedRealtime()));
    }

    /** The prices for the Cost fact: loaded each time the page is shown, so a save on Settings shows up here. */
    private void loadSettings() {
        submit(() -> {
            JSONObject loaded = new JSONObject(BatteryDataHub.settingsJson());
            main.post(() -> {
                settings = loaded;
                if (lastData != null) draw(lastData);
            });
        });
    }

    private void loadHistory(long now) {
        historyAt = now;
        submit(() -> {
            JSONObject loaded = new JSONObject(BatteryDataHub.historyJson("24h"));
            main.post(() -> history = loaded);
        });
    }

    private interface Job { void run() throws Exception; }

    private void submit(Job job) {
        if (io.isShutdown()) return;
        io.execute(() -> {
            try { job.run(); } catch (Exception e) { Log.e(TAG, "Status page background job failed", e); }
        });
    }

    // --- view builders ---

    private static void set(TextView t, String text) {
        if (!TextUtils.equals(t.getText(), text)) t.setText(text);
    }

    private CharSequence scaleText(String name, String lo, String hi) {
        SpannableStringBuilder b = new SpannableStringBuilder(name + "  ");
        int start = b.length();
        b.append(lo).append(" \u2192 ").append(hi);
        b.setSpan(new ForegroundColorSpan(s.text), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    private View scale(Context c, TextView label, String[] bands) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label, margins(wrap(), 0, 0, s.px(14), 0));
        int[] colors = { s.good, s.warn, s.danger };
        for (int i = 0; i < bands.length; i++) {
            View sw = new View(c);
            sw.setBackground(s.round(colors[i], 2, 0, 0));
            row.addView(sw, margins(new LinearLayout.LayoutParams(s.px(16), s.px(12)), 0, 0, s.px(5), 0));
            TextView t = s.text(c, 19, 400, s.muted2);
            t.setText(bands[i]);
            row.addView(t, margins(wrap(), 0, 0, s.px(16), 0));
        }
        return row;
    }

    private TextView badge(Context c, int color) {
        TextView t = s.text(c, 21, s.night ? 600 : 700, color);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(s.px(48));
        t.setPadding(s.px(18), s.px(8), s.px(18), s.px(8));
        t.setCompoundDrawablePadding(s.px(8));
        t.setMaxLines(1);
        t.setBackground(badgeBg(color, false));
        return t;
    }

    /** Day: a tinted pill; night (champagne): an outline only. */
    private GradientDrawable badgeBg(int color, boolean quiet) {
        if (s.night) return s.round(0, 999, quiet ? 0x1FFFFFFF : StatusStyle.alpha(color, 0.35f), 1);
        return s.round(StatusStyle.alpha(color, 0.10f), 999, StatusStyle.alpha(color, 0.25f), 1);
    }

    private GradientDrawable dot(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setSize(s.px(10), s.px(10));
        return d;
    }

    private TextView tab(Context c, String text) {
        TextView t = s.text(c, 24, s.night ? 600 : 700, s.muted);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(s.px(76));
        t.setMinWidth(s.px(92));
        t.setPadding(s.px(26), s.px(12), s.px(26), s.px(12));
        t.setClickable(true);
        return t;
    }

    private void styleTab(TextView t, boolean active) {
        t.setSelected(active);
        t.setTextColor(active ? s.onAccent : s.muted);
        t.setBackground(pressable(s.round(active ? s.accent : 0, s.night ? 999 : 15, 0, 0)));
    }

    private RippleDrawable pressable(GradientDrawable shape) {
        return new RippleDrawable(ColorStateList.valueOf(StatusStyle.alpha(s.accent, 0.3f)), shape, null);
    }

    private LinearLayout group(Context c) {
        LinearLayout g = new LinearLayout(c);
        g.setBaselineAligned(false);
        g.setBackground(s.round(s.bgDeep, 18, s.line, 1));
        g.setClipToOutline(true);
        // a bare View divider with MATCH_PARENT height inside this wrap_content row measures to all the space left
        // and pushed the schematic to zero height; LinearLayout dividers take the row's real height instead
        android.graphics.drawable.GradientDrawable line = new android.graphics.drawable.GradientDrawable();
        line.setColor(s.lineSoft);
        line.setSize(Math.max(1, s.px(1)), 1);
        g.setDividerDrawable(line);
        g.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        return g;
    }

    /** One facts cell: label, value, optional note; cells after the first get a soft divider on the left. */
    private TextView fact(Context c, LinearLayout group, String label, TextView note) {
        LinearLayout cell = new LinearLayout(c);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(s.px(20), s.px(16), s.px(20), s.px(16));
        TextView l = s.text(c, 22, 600, s.muted);
        l.setText(label);
        TextView v = s.text(c, 32, 700, s.text);
        v.setText(UiFormat.MISSING);
        v.setMaxLines(1);
        v.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(l);
        cell.addView(v, margins(wrap(), 0, s.px(6), 0, 0));
        if (note != null) cell.addView(note, margins(wrap(), 0, s.px(4), 0, 0));
        group.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    private TextView note(Context c, String text) {
        TextView t = s.text(c, 18, 400, s.muted2);
        t.setText(text);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, int l, int t, int r, int b) {
        p.setMargins(l, t, r, b);
        return p;
    }
}

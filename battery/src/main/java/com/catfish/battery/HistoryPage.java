package com.catfish.battery;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.TimeZone;

/**
 * Charge history page, native port of the WebView History page: this month against the budget, the average price
 * and all-time totals, then every charge newest first in month blocks. A tap on a charge opens the charger picker,
 * which saves through BatteryDataHub.setCharger(start, charger). Data: sessionsJson() and settingsJson().
 */
final class HistoryPage extends FrameLayout {
    private static final String TAG = "CatfishBattery";
    private static final long RELOAD_MS = 30000L;

    private final ChargingUi ui;
    private final TextView badge;
    private final LinearLayout kpis, list;
    private final FrameLayout picker;
    private final TextView pickerTitle;
    private final LinearLayout pickerList;
    private HistoryModel model;
    private boolean visible, loading;
    private long loadedAt;
    private final Runnable tick = this::tick;

    HistoryPage(Context context) {
        super(context);
        ui = new ChargingUi(context);
        LinearLayout card = ui.card();
        addView(card, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout head = ui.row();
        head.setMinimumHeight(ui.dp(76));
        LinearLayout titles = ui.column();
        titles.addView(ui.title("Charge history"));
        TextView kicker = ui.kicker();
        ChargingUi.set(kicker, "Every charge, newest first");
        titles.addView(kicker);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        badge = ui.badge();
        ui.badge(badge, false);
        head.addView(badge);
        LinearLayout.LayoutParams headLp = ChargingUi.fillWidth();
        headLp.bottomMargin = ui.dp(20);
        card.addView(head, headLp);

        kpis = ui.row();
        kpis.setGravity(Gravity.NO_GRAVITY);
        LinearLayout.LayoutParams kpiLp = ChargingUi.fillWidth();
        kpiLp.bottomMargin = ui.dp(24);
        card.addView(kpis, kpiLp);

        ScrollView scroll = new ScrollView(context);
        ui.panel(scroll);
        scroll.setClipToOutline(true);
        list = ui.column();
        scroll.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, ChargingUi.fillHeight());

        TextView hint = ui.text(20, ui.muted2, 400);
        hint.setPadding(ui.dp(2), ui.dp(18), 0, 0);
        ChargingUi.set(hint, "Tap a charge to pick which charger it was; a dashed chip is the default for its kind.");
        card.addView(hint, ChargingUi.fillWidth());
        TextView caption = ui.text(20, ui.muted2, 400);
        caption.setLineSpacing(0, 1.4f);
        caption.setPadding(ui.dp(2), ui.dp(8), 0, 0);
        ChargingUi.set(caption, "A charge is a charger plugged in with energy going into the pack. One made while the head "
                + "unit slept shows the sleep and wake times, not the charge itself. Cost adds the charging loss at the "
                + "charger's price from Settings.");
        card.addView(caption, ChargingUi.fillWidth());

        // the charger picker, over the card
        picker = new FrameLayout(context);
        picker.setBackgroundColor(0x99000000);
        picker.setVisibility(GONE);
        picker.setClickable(true);
        picker.setOnClickListener(v -> closePicker());
        LinearLayout pickCard = ui.column();
        pickCard.setClickable(true);
        pickCard.setBackground(ui.box(ui.night ? 0xFF141418 : ui.surface, ui.cardEdge, 24));
        pickCard.setPadding(ui.dp(32), ui.dp(32), ui.dp(32), ui.dp(32));
        LinearLayout pickHead = ui.row();
        pickerTitle = ui.oneLine(ui.text(32, ui.text, 700));
        pickHead.addView(pickerTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = ui.button("Close", 22, false);
        close.setOnClickListener(v -> closePicker());
        pickHead.addView(close);
        pickCard.addView(pickHead, ChargingUi.fillWidth());
        ScrollView pickScroll = new ScrollView(context);
        pickerList = ui.column();
        pickScroll.addView(pickerList, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams psl = ChargingUi.fillWidth();
        psl.topMargin = ui.dp(23);
        pickCard.addView(pickScroll, psl);
        FrameLayout.LayoutParams pcl = new FrameLayout.LayoutParams(ui.dp(1000), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        pcl.topMargin = pcl.bottomMargin = ui.dp(48);
        picker.addView(pickCard, pcl);
        addView(picker, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        render(null, null);
    }

    /** Called when the page becomes visible and then periodically; reloads at most every 30 s. */
    void refresh() {
        if (System.currentTimeMillis() - loadedAt >= RELOAD_MS) load();
    }

    void setVisibleNow(boolean visible) {
        this.visible = visible;
        removeCallbacks(tick);
        if (visible) { load(); postDelayed(tick, RELOAD_MS); }
        else closePicker();
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

    private void load() {
        if (loading) return;
        loading = true;
        ChargingUi.background(() -> {
            String sessions, settings;
            try { sessions = BatteryDataHub.sessionsJson(); }
            catch (RuntimeException e) { Log.e(TAG, "Charge sessions unavailable", e); sessions = "{\"sessions\":[],\"live\":null}"; }
            try { settings = BatteryDataHub.settingsJson(); }
            catch (RuntimeException e) { Log.e(TAG, "Battery settings unavailable", e); settings = null; }
            final String a = sessions, b = settings;
            post(() -> {
                loading = false;
                loadedAt = System.currentTimeMillis();
                render(a, b);
            });
        });
    }

    private void render(String sessionsText, String settingsText) {
        JSONObject data = null, settings = null;
        try { if (sessionsText != null) data = new JSONObject(sessionsText); }
        catch (JSONException e) { data = null; }
        try { if (settingsText != null) settings = new JSONObject(settingsText).optJSONObject("settings"); }
        catch (JSONException e) { settings = null; }
        TimeZone tz = TimeZone.getDefault();
        model = new HistoryModel(data, new ChargingCost(settings, tz), System.currentTimeMillis(), tz);
        ui.badge(badge, model.charging());

        kpis.removeAllViews();
        List<HistoryModel.Kpi> k = model.kpis();
        float[] weights = { 1.3f, 1f, 1f };
        for (int i = 0; i < k.size(); i++) {
            if (i > 0) kpis.addView(ui.space(24, 1));
            kpis.addView(kpi(k.get(i)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weights[i]));
        }

        list.removeAllViews();
        HistoryModel.Row live = model.liveRow();
        boolean first = true;
        if (live != null) { list.addView(row(live)); first = false; }
        for (HistoryModel.Month m : model.months()) {
            if (!first) list.addView(ui.divider(false));
            first = false;
            LinearLayout mh = ui.row();
            mh.setGravity(Gravity.BOTTOM);
            mh.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(12));
            TextView b = ui.text(28, ui.text, 700), s = ui.text(22, ui.muted2, 500);
            ChargingUi.set(b, m.label);
            ChargingUi.set(s, m.summary);
            mh.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            mh.addView(s);
            list.addView(mh, ChargingUi.fillWidth());
            for (HistoryModel.Row r : m.rows) {
                list.addView(ui.divider(false));
                list.addView(row(r));
            }
        }
        if (model.isEmpty()) list.addView(emptyState());
    }

    private View kpi(HistoryModel.Kpi k) {
        LinearLayout box = ui.column();
        ui.panel(box);
        box.setPadding(ui.dp(28), ui.dp(28), ui.dp(28), ui.dp(28));
        TextView small = ui.label(24);
        ChargingUi.set(small, k.label);
        box.addView(small);
        TextView strong = ui.big(48, 54);
        strong.setPadding(0, ui.dp(8), 0, 0);
        SpannableStringBuilder v = new SpannableStringBuilder(k.value);
        if (!k.unit.isEmpty()) {
            int at = v.length();
            v.append("  ").append(k.unit);
            v.setSpan(new AbsoluteSizeSpan(ui.dp(22)), at, v.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            v.setSpan(new ForegroundColorSpan(ui.muted2), at, v.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        ChargingUi.set(strong, v);
        box.addView(strong);
        if (k.barPercent >= 0) {
            LinearLayout bar = ui.track(10);
            int color = "danger".equals(k.barTone) ? ui.danger : "warn".equals(k.barTone) ? ui.warn : ui.accent;
            bar.getChildAt(1).setBackground(ui.box(color, 0, 5));
            ui.track(bar, 0, k.barPercent);
            if (k.barPercent == 0) bar.getChildAt(1).setVisibility(INVISIBLE);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
            lp.topMargin = ui.dp(12);
            box.addView(bar, lp);
        }
        TextView sub = ui.oneLine(ui.text(22, ui.muted2, 400));
        sub.setPadding(0, ui.dp(8), 0, 0);
        ChargingUi.set(sub, k.sub);
        box.addView(sub);
        return box;
    }

    /** One charge: tag | when | SOC with track | kWh | peak | charger chip | cost (WebView .hrow columns). */
    private View row(HistoryModel.Row r) {
        LinearLayout row = ui.row();
        row.setMinimumHeight(ui.dp(100));
        row.setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(18));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(rowBackground(r.live));
        row.setOnClickListener(v -> openPicker(r.start));
        int gap = ui.dp(20);

        row.addView(ui.tag(r.tag, r.ac, r.dc), new LinearLayout.LayoutParams(ui.dp(70), ui.dp(42)));
        LinearLayout when = ui.column();
        TextView b = ui.oneLine(ui.text(26, ui.text, 700)), s = ui.oneLine(ui.text(22, ui.muted2, 400));
        s.setPadding(0, ui.dp(4), 0, 0);
        ChargingUi.set(b, r.when);
        ChargingUi.set(s, r.sub);
        when.addView(b);
        when.addView(s);
        row.addView(when, cell(1.25f, gap));

        LinearLayout soc = ui.column();
        TextView st = ui.oneLine(ui.text(22, ui.text, 700));
        ChargingUi.set(st, r.soc);
        soc.addView(st);
        LinearLayout track = ui.track(8);
        ui.track(track, r.trackFrom, r.trackTo);
        LinearLayout.LayoutParams tl = (LinearLayout.LayoutParams) track.getLayoutParams();
        tl.topMargin = ui.dp(8);
        soc.addView(track, tl);
        row.addView(soc, cell(1.3f, gap));

        TextView kwh = ui.oneLine(ui.text(28, ui.text, 700));
        ChargingUi.set(kwh, r.kwh);
        row.addView(kwh, cell(0.8f, gap));
        TextView peak = ui.oneLine(ui.text(22, ui.muted, 500));
        ChargingUi.set(peak, r.peak);
        row.addView(peak, cell(0.8f, gap));

        LinearLayout chipCell = ui.row();
        if (r.charger != null) {
            TextView chip = ui.oneLine(ui.text(22, r.chargerDefault ? ui.muted : ui.text, 600));
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setMinHeight(ui.dp(70));
            chip.setPadding(ui.dp(16), 0, ui.dp(16), 0);
            int edge = ui.night ? 0x404CC9F0 : ui.panelEdge;
            GradientDrawable d = ui.box(ui.surface, edge, 999);
            if (r.chargerDefault) d.setStroke(ui.dp(1), edge, ui.dp(6), ui.dp(4));
            chip.setBackground(d);
            ChargingUi.set(chip, r.charger + "  ▾");
            chipCell.addView(chip, ChargingUi.wrap());
        }
        row.addView(chipCell, cell(1f, gap));

        TextView cost = ui.oneLine(ui.text(28, ui.text, 700));
        cost.setGravity(Gravity.END);
        ChargingUi.set(cost, r.cost);
        row.addView(cost, cell(0.7f, gap));
        return row;
    }

    private static LinearLayout.LayoutParams cell(float weight, int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.leftMargin = gap;
        return lp;
    }

    /** Pressed tint; the live charge also gets the WebView's 3 px green edge on the left. */
    private Drawable rowBackground(boolean live) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_pressed }, ui.box(ui.night ? 0x144CC9F0 : 0x142368C5, 0, 0));
        if (!live) return s;
        LayerDrawable l = new LayerDrawable(new Drawable[] { s, ui.box(ui.good, 0, 0) });
        l.setLayerGravity(1, Gravity.START | Gravity.FILL_VERTICAL);
        l.setLayerWidth(1, ui.dp(3));
        return l;
    }

    private View emptyState() {
        LinearLayout box = ui.column();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(ui.dp(40), ui.dp(80), ui.dp(40), ui.dp(40));
        TextView b = ui.text(32, ui.text, 700), s = ui.text(24, ui.muted, 400);
        b.setGravity(Gravity.CENTER);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(0, 1.4f);
        s.setPadding(0, ui.dp(12), 0, 0);
        s.setMaxWidth(ui.dp(1000));
        ChargingUi.set(b, "No charges logged yet");
        ChargingUi.set(s, "Charge history records itself. After the first charge (a charger plugged in with energy going "
                + "into the pack, or the car waking to a pack that charged while parked), each session appears here with "
                + "its energy, cost and SOC change.");
        box.addView(b);
        box.addView(s);
        return box;
    }

    private void openPicker(long start) {
        HistoryModel.Picker p = model == null ? null : model.picker(start);
        if (p == null) return;
        ChargingUi.set(pickerTitle, p.title);
        pickerList.removeAllViews();
        for (HistoryModel.Option o : p.options) {
            LinearLayout b = ui.row();
            b.setMinimumHeight(ui.dp(90));
            b.setPadding(ui.dp(28), ui.dp(20), ui.dp(28), ui.dp(20));
            b.setBackground(o.on ? ui.box(ui.night ? 0x244CC9F0 : 0x1A2368C5, ui.accent, 16)
                    : ui.box(0, ui.night ? 0x2E4CC9F0 : ui.panelEdge, 16));
            b.setClickable(true);
            b.setFocusable(true);
            TextView name = ui.oneLine(ui.text(28, o.on ? ui.accent2 : ui.text, 700));
            ChargingUi.set(name, o.name);
            b.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView detail = ui.text(24, ui.muted, 500);
            ChargingUi.set(detail, o.detail);
            b.addView(detail);
            b.setOnClickListener(v -> pick(p.start, o.id));
            LinearLayout.LayoutParams lp = ChargingUi.fillWidth();
            if (pickerList.getChildCount() > 0) lp.topMargin = ui.dp(23);
            pickerList.addView(b, lp);
        }
        picker.setVisibility(VISIBLE);
    }

    private void closePicker() { picker.setVisibility(GONE); }

    private void pick(long start, String charger) {
        closePicker();
        ChargingUi.background(() -> {
            boolean ok = true;
            try { BatteryDataHub.setCharger(String.valueOf(start), charger); }
            catch (RuntimeException e) { Log.e(TAG, "Charger pick failed", e); ok = false; }
            final boolean saved = ok;
            post(() -> {
                if (!saved) Toast.makeText(getContext(), "Charger not saved, try again", Toast.LENGTH_SHORT).show();
                loading = false;
                load();
            });
        });
    }
}

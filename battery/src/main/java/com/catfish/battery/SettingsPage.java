package com.catfish.battery;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.IntConsumer;

/**
 * Settings page: a summary card per section on the left (Preferences, Chargers & prices, Totals & reset, About), each
 * showing its current values, and the open section on the right; Save at the bottom.
 * Loads BatteryDataHub.settingsJson(), saves with saveSettings(json); BatterySettings.clean stays the validation and
 * fields it would change show the saved value inline. Edits live in a {@link SettingsForm} until Save, so they
 * survive focus changes and switching tabs.
 */
final class SettingsPage extends FrameLayout {
    private static final String TAG = "CatfishBattery";

    private final ChargingUi ui;
    private static final String[] SECTIONS = { "Preferences", "Chargers & prices", "Totals & reset", "About" };
    private static final String[] ICONS = { "\u2699\uFE0E", "\u26A1\uFE0E", "\u21BB", "i" };

    private final LinearLayout chargersBox, prefs, totals;
    private final View[] items = new View[SECTIONS.length], panes;
    private final TextView[] summaries = new TextView[SECTIONS.length];
    private final ScrollView detail;
    private final TextView state;
    private final Button save;
    private final Map<String, TextView> errors = new HashMap<>();
    private final Map<String, TextView> unitLabels = new HashMap<>();
    private final List<View[]> defaultButtons = new ArrayList<>();
    private SettingsForm form;
    private JSONObject saved;
    private Long nextReset;
    private boolean dirty, loading, saving, binding;
    private TextView nextResetLine;
    private Spinner monthsSpinner, daySpinner;

    SettingsPage(Context context) {
        super(context);
        ui = new ChargingUi(context);
        LinearLayout card = ui.card();
        addView(card, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout body = ui.row();
        body.setGravity(Gravity.NO_GRAVITY);
        card.addView(body, ChargingUi.fillHeight());

        // left: one card per section with its current values, so the state reads without opening it
        LinearLayout list = ui.column();
        for (int i = 0; i < SECTIONS.length; i++) {
            final int at = i;
            summaries[i] = ui.oneLine(ui.text(20, ui.muted, 400));
            items[i] = item(ICONS[i], SECTIONS[i], summaries[i]);
            items[i].setOnClickListener(v -> show(at));
            LinearLayout.LayoutParams lp = ChargingUi.fillWidth();
            if (i > 0) lp.topMargin = ui.dp(12);
            list.addView(items[i], lp);
        }
        body.addView(list, new LinearLayout.LayoutParams(ui.dp(560), ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(ui.space(24, 1));

        // right: the open section
        detail = pane();
        LinearLayout sections = ui.column();
        detail.addView(sections, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(detail, ChargingUi.weight(1f));

        LinearLayout chargers = ui.column();
        chargers.addView(h3("Chargers & prices", "TOU: weekdays 09:00–22:00 on-peak, nights and weekends off-peak. A charge "
                + "while parked has no known time and counts as off-peak. Public holidays are not known."));
        chargersBox = ui.column();
        chargers.addView(chargersBox, ChargingUi.fillWidth());
        Button add = ui.button("+ Add charger", 24, false);
        add.setOnClickListener(v -> addCharger());
        LinearLayout.LayoutParams addLp = ChargingUi.wrap();
        addLp.topMargin = ui.dp(20);
        chargers.addView(add, addLp);
        prefs = ui.column();
        totals = ui.column();
        panes = new View[] { prefs, chargers, totals, about() };
        for (View p : panes) sections.addView(p, ChargingUi.fillWidth());

        // save bar
        LinearLayout bar = ui.row();
        bar.setBackground(ui.box(ui.panel, ui.panelEdge, 18));
        bar.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
        state = ui.oneLine(ui.text(24, ui.muted, 600));
        bar.addView(state, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        save = ui.button("Save", 28, true);
        save.setMinWidth(ui.dp(220));
        save.setOnClickListener(v -> save());
        bar.addView(save);
        LinearLayout.LayoutParams barLp = ChargingUi.fillWidth();
        barLp.topMargin = ui.dp(20);
        card.addView(bar, barLp);
        setState("Loading…", ui.muted);
        saveEnabled(false);
        ChargingUi.set(summaries[3], "v" + BuildConfig.VERSION_NAME + " · built " + UiFormat.date(BuildConfig.BUILD_TIME, TimeZone.getDefault()));
        show(0);
    }

    private LinearLayout item(String icon, String title, TextView summary) {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(20), ui.dp(18), ui.dp(16), ui.dp(18));
        row.setClickable(true);
        row.setFocusable(true);
        TextView ic = ui.text(28, ui.accent, 700);
        ic.setGravity(Gravity.CENTER);
        ChargingUi.set(ic, icon);
        ic.setBackground(ui.box(ui.night ? 0x1FE2C08D : 0x142368C5, 0, 16));
        row.addView(ic, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)));
        LinearLayout text = ui.column();
        TextView t = ui.text(26, ui.text, 700);
        ChargingUi.set(t, title);
        text.addView(t);
        summary.setPadding(0, ui.dp(8), 0, 0);
        text.addView(summary);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = ui.dp(18);
        row.addView(text, lp);
        TextView chevron = ui.text(34, ui.muted, 400);
        ChargingUi.set(chevron, "›");
        row.addView(chevron);
        row.setContentDescription(title);
        return row;
    }

    /** Opens one section on the right and marks its card. */
    private void show(int at) {
        for (int i = 0; i < panes.length; i++) {
            boolean on = i == at;
            panes[i].setVisibility(on ? VISIBLE : GONE);
            items[i].setBackground(ui.box(on ? (ui.night ? 0x14E2C08D : 0x0F2368C5) : ui.panel, on ? ui.accent : ui.panelEdge, 20));
            items[i].setSelected(on);
        }
        detail.scrollTo(0, 0);
    }

    private void summaries() {
        if (form == null) return;
        ChargingUi.set(summaries[1], form.chargersSummary());
        ChargingUi.set(summaries[0], form.prefsSummary());
        ChargingUi.set(summaries[2], form.totalsSummary());
    }

    private void saveEnabled(boolean on) {
        save.setEnabled(on);
        save.setAlpha(on ? 1f : .45f);
    }

    /** Nothing edited: say whether these are saved settings or the defaults. */
    private void idle() {
        boolean wasSaved = saved != null && saved.optBoolean("_saved");
        setState(wasSaved ? "✓  Saved" : "Default settings", wasSaved ? ui.good : ui.muted);
        saveEnabled(false);
    }

    // ---- About ----

    /** Set to the owner's https://ko-fi.com/<name> to show the Ko-fi card with its QR code; empty hides it. */
    static final String KOFI_URL = "https://ko-fi.com/catfishfarm";

    private LinearLayout about() {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.TOP);
        LinearLayout info = ui.column();
        info.addView(h3("Catfish Battery", "Battery, charging and health for the Geely EX5. Read-only: it never writes to the car."));
        fact(info, "Version", BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")", false);
        fact(info, "Built", UiFormat.dateTime(BuildConfig.BUILD_TIME, TimeZone.getDefault()), false);
        fact(info, "Commit", BuildConfig.GIT_COMMIT, true);
        fact(info, "Signer", signer(), true);
        fact(info, "Source", "github.com/Antony-12s/CatfishBattery", true);
        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!KOFI_URL.isEmpty()) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(400), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = ui.dp(28);
            row.addView(kofi(), lp);
        }
        return row;
    }

    private void fact(LinearLayout into, String label, String value, boolean mono) {
        if (into.getChildCount() > 1) into.addView(ui.divider(false));
        LinearLayout row = ui.row();
        row.setPadding(0, ui.dp(16), 0, ui.dp(16));
        TextView k = ui.text(22, ui.muted, 500);
        ChargingUi.set(k, label);
        row.addView(k, new LinearLayout.LayoutParams(ui.dp(190), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = ui.text(mono ? 22 : 24, ui.text, 500);
        if (mono) v.setTypeface(android.graphics.Typeface.MONOSPACE);
        ChargingUi.set(v, value);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        into.addView(row, ChargingUi.fillWidth());
    }

    /** The first 8 and last 4 hex digits of the APK signer's SHA-256, as in the release notes. */
    private String signer() {
        try {
            android.content.pm.PackageInfo p = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(),
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(p.signingInfo.getApkContentsSigners()[0].toByteArray());
            String hex = String.format("%064x", new java.math.BigInteger(1, d));
            return hex.substring(0, 8) + "…" + hex.substring(60);
        } catch (Exception e) {
            return UiFormat.MISSING;
        }
    }

    private View kofi() {
        LinearLayout c = ui.column();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24));
        c.setBackground(ui.box(ui.night ? 0x1AE2C08D : 0x0F2368C5, ui.night ? 0x59E2C08D : ui.panelEdge, 22));
        TextView t = ui.text(26, ui.text, 700);
        ChargingUi.set(t, "Buy me a coffee");
        c.addView(t);
        TextView s = ui.text(20, ui.muted, 400);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, ui.dp(8), 0, ui.dp(16));
        ChargingUi.set(s, "Scan with your phone to support the app on Ko-fi.");
        c.addView(s);
        android.widget.ImageView qr = new android.widget.ImageView(getContext());
        qr.setImageBitmap(qr(KOFI_URL, 400));
        qr.setBackground(ui.box(0xFFFFFFFF, 0, 16));
        qr.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
        qr.setContentDescription("Ko-fi QR code");
        c.addView(qr, new LinearLayout.LayoutParams(ui.dp(260), ui.dp(260)));
        TextView u = ui.text(21, ui.accent, 600);
        u.setPadding(0, ui.dp(14), 0, 0);
        ChargingUi.set(u, KOFI_URL.replaceFirst("^https?://", ""));
        c.addView(u);
        return c;
    }

    private static android.graphics.Bitmap qr(String text, int size) {
        try {
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter().encode(text,
                    com.google.zxing.BarcodeFormat.QR_CODE, size, size, Map.of(com.google.zxing.EncodeHintType.MARGIN, 0));
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            return android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888);
        } catch (com.google.zxing.WriterException e) {
            return null;
        }
    }

    /** Called when the page becomes visible and then periodically; loads once, then keeps the edits. */
    void refresh() {
        if (form == null) load();
    }

    /** Becoming visible reloads only when nothing is being edited, so unsaved edits stay. */
    void setVisibleNow(boolean visible) {
        if (visible && !dirty && !saving) load();
        if (!visible) clearFocus();
    }

    private void load() {
        if (loading) return;
        loading = true;
        ChargingUi.background(() -> {
            String json;
            try { json = BatteryDataHub.settingsJson(); }
            catch (RuntimeException e) { Log.e(TAG, "Battery settings unavailable", e); json = null; }
            final String j = json;
            post(() -> {
                loading = false;
                if (dirty || saving) return; // the driver started editing meanwhile
                if (!apply(j)) { if (form == null) setState("Settings unavailable", ui.danger); return; }
                idle();
            });
        });
    }

    /** Takes a settingsJson() / saveSettings() reply into the form; false when it does not parse. */
    private boolean apply(String json) {
        if (json == null) return false;
        try {
            JSONObject d = new JSONObject(json);
            JSONObject s = d.optJSONObject("settings");
            if (s == null) return false;
            saved = new JSONObject(s.toString()).put("_saved", d.optBoolean("saved"));
            nextReset = d.isNull("nextReset") ? null : d.optLong("nextReset");
            form = SettingsForm.from(s);
        } catch (JSONException e) {
            return false;
        }
        dirty = false;
        build();
        return true;
    }

    private void save() {
        if (form == null || saving) return;
        clearFocus();
        saving = true;
        saveEnabled(false);
        final String json = form.toJson().toString();
        ChargingUi.background(() -> {
            String reply;
            try { reply = BatteryDataHub.saveSettings(json); }
            catch (RuntimeException e) { Log.e(TAG, "Battery settings not saved", e); reply = null; }
            final String r = reply;
            post(() -> {
                saving = false;
                if (r != null && apply(r)) {
                    idle();
                    Toast.makeText(getContext(), "Settings saved", Toast.LENGTH_SHORT).show();
                } else {
                    setState("Not saved, try again", ui.danger);
                    saveEnabled(true);
                }
            });
        });
    }

    private void changed() {
        dirty = true;
        setState("●  Unsaved changes", ui.warn);
        saveEnabled(!saving);
        showIssues();
        summaries();
    }

    private void setState(String text, int color) {
        ChargingUi.set(state, text);
        state.setTextColor(color);
    }

    // ---- building the form ----

    private void build() {
        binding = true;
        errors.clear();
        unitLabels.clear();
        defaultButtons.clear();
        chargersBox.removeAllViews();
        for (SettingsForm.Charger c : form.chargers) addRow(c);
        buildPrefs();
        binding = false;
        markDefaults();
        showIssues();
        summaries();
    }

    private void addRow(SettingsForm.Charger c) {
        LinearLayout box = ui.column();
        if (chargersBox.getChildCount() > 0) chargersBox.addView(ui.divider(false));
        box.setPadding(0, ui.dp(20), 0, ui.dp(20));
        LinearLayout row = ui.row();
        row.setGravity(Gravity.TOP);
        int gap = ui.dp(18);

        LinearLayout main = ui.column();
        EditText name = input(c.name, "Name", false);
        name.setFilters(new InputFilter[] { new InputFilter.LengthFilter(40) });
        name.setContentDescription("Charger name");
        name.addTextChangedListener(watch(s -> { c.name = s; changed(); }));
        main.addView(name, ChargingUi.fillWidth());
        main.addView(error("charger." + c.id + ".name"));
        LinearLayout defs = ui.row();
        TextView acDef = defButton("AC default", v -> { form.ac = c.id; markDefaults(); changed(); });
        TextView dcDef = defButton("DC default", v -> { form.dc = c.id; markDefaults(); changed(); });
        defs.addView(acDef, new LinearLayout.LayoutParams(0, ui.dp(76), 1f));
        defs.addView(ui.space(10, 1));
        defs.addView(dcDef, new LinearLayout.LayoutParams(0, ui.dp(76), 1f));
        defaultButtons.add(new View[] { acDef, dcDef });
        acDef.setTag(c.id);
        LinearLayout.LayoutParams defsLp = ChargingUi.fillWidth();
        defsLp.topMargin = ui.dp(10);
        main.addView(defs, defsLp);
        row.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));

        TextView tou = ui.text(21, ui.muted, 700);
        tou.setGravity(Gravity.CENTER);
        tou.setClickable(true);
        tou.setFocusable(true);
        LinearLayout.LayoutParams touLp = new LinearLayout.LayoutParams(ui.dp(152), ui.dp(76));
        touLp.leftMargin = gap;
        row.addView(tou, touLp);

        LinearLayout single = priceField(c, "price", "Price", "Price per kWh", null);
        LinearLayout off = priceField(c, "offPeak", "0", "Off-peak price per kWh", "off-peak");
        LinearLayout peak = priceField(c, "peak", "0", "On-peak price per kWh", "on-peak");
        row.addView(single, cell(2f, gap));
        row.addView(off, cell(1f, gap));
        row.addView(peak, cell(1f, gap));
        Runnable touShown = () -> {
            ChargingUi.set(tou, (c.tou ? "☑" : "☐") + "  TOU");
            tou.setTextColor(c.tou ? ui.accent2 : ui.muted);
            tou.setBackground(ui.box(c.tou ? (ui.night ? 0x24E2C08D : 0x1A2368C5) : 0, c.tou ? ui.accent : ui.night ? 0x2EE2C08D : ui.panelEdge, 14));
            single.setVisibility(c.tou ? GONE : VISIBLE);
            off.setVisibility(c.tou ? VISIBLE : GONE);
            peak.setVisibility(c.tou ? VISIBLE : GONE);
        };
        touShown.run();
        tou.setOnClickListener(v -> { c.tou = !c.tou; touShown.run(); changed(); });

        Button del = ui.button("✕", 26, false);
        del.setPadding(0, 0, 0, 0);
        del.setContentDescription("Remove charger");
        del.setOnClickListener(v -> {
            if (!form.remove(c.id)) {
                Toast.makeText(getContext(), "Keep at least one charger", Toast.LENGTH_SHORT).show();
                return;
            }
            binding = true;
            chargersBox.removeAllViews();
            errors.keySet().removeIf(k -> k.startsWith("charger."));
            unitLabels.keySet().removeIf(k -> k.startsWith("charger."));
            defaultButtons.clear();
            for (SettingsForm.Charger x : form.chargers) addRow(x);
            binding = false;
            markDefaults();
            changed();
        });
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(ui.dp(76), ui.dp(76));
        delLp.leftMargin = gap;
        row.addView(del, delLp);
        box.addView(row, ChargingUi.fillWidth());
        chargersBox.addView(box, ChargingUi.fillWidth());
    }

    /** A price box with the currency unit inside on the right and an optional caption above. */
    private LinearLayout priceField(SettingsForm.Charger c, String key, String placeholder, String label, String caption) {
        LinearLayout col = ui.column();
        if (caption != null) {
            TextView cap = ui.oneLine(ui.text(19, ui.muted, 500));
            ChargingUi.set(cap, caption);
            cap.setPadding(ui.dp(4), 0, 0, ui.dp(4));
            col.addView(cap);
        }
        String value = "price".equals(key) ? c.price : "offPeak".equals(key) ? c.offPeak : c.peak;
        FrameLayout field = new FrameLayout(getContext());
        EditText e = input(value, placeholder, true);
        e.setContentDescription(label);
        e.setPadding(ui.dp(18), 0, ui.dp(110), 0);
        e.addTextChangedListener(watch(s -> {
            if ("price".equals(key)) c.price = s; else if ("offPeak".equals(key)) c.offPeak = s; else c.peak = s;
            changed();
        }));
        field.addView(e, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(76)));
        TextView unit = ui.text(18, ui.muted, 500);
        unit.setPadding(0, 0, ui.dp(16), 0);
        ChargingUi.set(unit, unitText());
        unitLabels.put("charger." + c.id + "." + key, unit);
        field.addView(unit, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));
        col.addView(field, ChargingUi.fillWidth());
        col.addView(error("charger." + c.id + "." + key));
        return col;
    }

    private void buildPrefs() {
        prefs.removeAllViews();
        prefs.addView(h3("Preferences", "Cost, budget and alerts."));
        totals.removeAllViews();
        totals.addView(h3("Totals & reset", "Used, charged and regen totals and their cost. Charge history and settings are never cleared."));

        String[] codes = ChargingCost.CURRENCY.keySet().toArray(new String[0]);
        String[] labels = new String[codes.length];
        int at = 0;
        for (int i = 0; i < codes.length; i++) {
            labels[i] = codes[i] + " · " + ChargingCost.CURRENCY.get(codes[i]);
            if (codes[i].equals(form.currency)) at = i;
        }
        prow(prefs, "Currency", "every price and total follows it", spinner(labels, at, i -> { form.currency = codes[i]; units(); changed(); }));

        LinearLayout pair = ui.row();
        pair.addView(numberField("lossAc", form.lossAc, "% AC", "AC charging loss", s -> form.lossAc = s));
        pair.addView(ui.space(12, 1));
        pair.addView(numberField("lossDc", form.lossDc, "% DC", "DC charging loss", s -> form.lossDc = s));
        prow(prefs, "Charging loss", "what the pack does not keep; every cost grows by it", pair);
        prow(prefs, "Monthly budget", "History shows the month against it; 0 = none",
                numberField("budget", form.budget, null, "Monthly budget", s -> form.budget = s));

        String[] alerts = new String[SettingsForm.ALERT_STEPS.length];
        int alertAt = 0;
        for (int i = 0; i < alerts.length; i++) {
            int p = SettingsForm.ALERT_STEPS[i];
            alerts[i] = p == 0 ? "Off" : p + " %";
            if (p == form.alertSoc) alertAt = i;
        }
        prow(prefs, "Charge alert", "a message on screen once per charge",
                spinner(alerts, alertAt, i -> { form.alertSoc = SettingsForm.ALERT_STEPS[i]; changed(); }));

        Button reset = ui.button("Reset now", 22, false);
        reset.setOnClickListener(v -> resetNow(reset));
        prow(totals, "Reset now", "asks for a second tap", reset);

        String[] modes = { "off", "monthly", "months" };
        int modeAt = Math.max(0, java.util.Arrays.asList(modes).indexOf(form.resetMode));
        LinearLayout auto = ui.row();
        auto.addView(spinner(new String[] { "Off", "Every month", "Every N months" }, modeAt, i -> {
            form.resetMode = modes[i];
            resetShown();
            changed();
        }));
        String[] months = new String[11];
        for (int m = 2; m <= 12; m++) months[m - 2] = "Every " + m + " months";
        monthsSpinner = spinner(months, form.resetMonths - 2, i -> { form.resetMonths = i + 2; resetShown(); changed(); });
        String[] days = new String[31];
        for (int d = 1; d <= 31; d++) days[d - 1] = d == 31 ? "Last day" : "Day " + d;
        daySpinner = spinner(days, form.resetDay - 1, i -> { form.resetDay = i + 1; resetShown(); changed(); });
        auto.addView(ui.space(12, 1));
        auto.addView(monthsSpinner);
        auto.addView(ui.space(12, 1));
        auto.addView(daySpinner);
        LinearLayout autoRow = prow(totals, "Auto reset", "", auto);
        nextResetLine = (TextView) ((LinearLayout) autoRow.getChildAt(0)).getChildAt(1);
        nextResetLine.setVisibility(VISIBLE);
        resetShown();
    }

    private void resetShown() {
        if (form == null || nextResetLine == null) return;
        daySpinner.setVisibility("off".equals(form.resetMode) ? GONE : VISIBLE);
        monthsSpinner.setVisibility("months".equals(form.resetMode) ? VISIBLE : GONE);
        ChargingUi.set(nextResetLine, "off".equals(form.resetMode) ? "totals keep counting"
                : form.nextResetText(saved, nextReset, TimeZone.getDefault()));
        summaries();
    }

    /** Two taps: the first arms the button for 4 s, the second clears the totals (not history or settings). */
    private void resetNow(Button b) {
        if (b.getTag() == null) {
            b.setTag(Boolean.TRUE);
            ChargingUi.set(b, "Tap again to reset");
            b.postDelayed(() -> { b.setTag(null); ChargingUi.set(b, "Reset now"); }, 4000);
            return;
        }
        b.setTag(null);
        ChargingUi.set(b, "Reset now");
        ChargingUi.background(() -> {
            boolean ok = true;
            String json = null;
            try {
                BatteryDataHub.resetTotals();
                json = BatteryDataHub.settingsJson();
            } catch (RuntimeException e) { Log.e(TAG, "Totals reset failed", e); ok = false; }
            final boolean done = ok;
            final String j = json;
            post(() -> {
                Toast.makeText(getContext(), done ? "Totals reset" : "Reset failed, try again", Toast.LENGTH_SHORT).show();
                if (done && j != null) {
                    try { nextReset = new JSONObject(j).isNull("nextReset") ? null : new JSONObject(j).optLong("nextReset"); }
                    catch (JSONException e) { nextReset = null; }
                    resetShown();
                }
            });
        });
    }

    private void addCharger() {
        if (form == null) return;
        SettingsForm.Charger c = form.addCharger(System.currentTimeMillis());
        binding = true;
        addRow(c);
        binding = false;
        markDefaults();
        changed();
        View name = firstEdit(chargersBox.getChildAt(chargersBox.getChildCount() - 1));
        if (name != null) name.requestFocus();
    }

    private static View firstEdit(View v) {
        if (v instanceof EditText) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = firstEdit(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    /** The AC / DC default badges: filled on the default charger, outlined elsewhere. */
    private void markDefaults() {
        int edge = ui.night ? 0x2EE2C08D : ui.panelEdge;
        for (View[] pair : defaultButtons) {
            String id = (String) pair[0].getTag();
            boolean[] on = { id.equals(form.ac), id.equals(form.dc) };
            for (int i = 0; i < 2; i++) {
                TextView b = (TextView) pair[i];
                ui.pill(b, on[i], 14);
                ChargingUi.set(b, (on[i] ? "✓  " : "") + (i == 0 ? "AC default" : "DC default"));
                if (!on[i]) b.setBackground(ui.box(0, edge, 14));
                b.setSelected(on[i]);
            }
        }
    }

    /** Currency changed: every unit label follows. */
    private void units() {
        for (Map.Entry<String, TextView> e : unitLabels.entrySet()) {
            ChargingUi.set(e.getValue(), "budget".equals(e.getKey()) ? currencySymbol() : unitText());
        }
    }

    private String unitText() { return currencySymbol() + "/kWh"; }

    private String currencySymbol() {
        String s = form == null ? null : ChargingCost.CURRENCY.get(form.currency);
        return s == null ? "฿" : s;
    }

    private void showIssues() {
        if (form == null) return;
        Map<String, String> issues = form.issues();
        for (Map.Entry<String, TextView> e : errors.entrySet()) {
            String msg = issues.get(e.getKey());
            ChargingUi.set(e.getValue(), msg == null ? "" : msg);
            e.getValue().setVisibility(msg == null ? GONE : VISIBLE);
        }
    }

    // ---- small views ----

    private ScrollView pane() {
        ScrollView s = new ScrollView(getContext());
        ui.panel(s);
        s.setPadding(ui.dp(26), ui.dp(26), ui.dp(26), ui.dp(26));
        s.setClipToPadding(true);
        s.setFillViewport(true);
        return s;
    }

    private View h3(String title, String small) {
        LinearLayout box = ui.column();
        box.setPadding(0, 0, 0, ui.dp(16));
        TextView t = ui.text(28, ui.text, 700);
        ChargingUi.set(t, title);
        box.addView(t);
        if (small != null) {
            TextView s = ui.text(20, ui.muted, 400);
            s.setPadding(0, ui.dp(6), 0, 0);
            ChargingUi.set(s, small);
            box.addView(s);
        }
        return box;
    }

    /** A preference row: bold label and helper on the left, the control on the right. */
    private LinearLayout prow(LinearLayout into, String label, String small, View control) {
        LinearLayout row = ui.row();
        row.setPadding(0, ui.dp(16), 0, ui.dp(16));
        LinearLayout text = ui.column();
        TextView b = ui.text(26, ui.text, 700);
        ChargingUi.set(b, label);
        text.addView(b);
        TextView s = ui.text(20, ui.muted, 400);
        s.setPadding(0, ui.dp(4), ui.dp(12), 0);
        s.setMaxWidth(ui.dp(400));
        ChargingUi.set(s, small == null ? "" : small);
        s.setVisibility(small == null ? GONE : VISIBLE);
        text.addView(s);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = ChargingUi.wrap();
        lp.leftMargin = ui.dp(20);
        row.addView(control, lp);
        if (into.getChildCount() > 1) into.addView(ui.divider(false));
        into.addView(row, ChargingUi.fillWidth());
        return row;
    }

    /** A number box (losses, budget) with its unit inside, and its inline error below. */
    private View numberField(String key, String value, String suffix, String label, java.util.function.Consumer<String> set) {
        LinearLayout col = ui.column();
        FrameLayout field = new FrameLayout(getContext());
        EditText e = input(value, "0", true);
        e.setContentDescription(label);
        boolean budget = suffix == null;
        e.setPadding(ui.dp(budget ? 52 : 18), 0, ui.dp(budget ? 18 : 70), 0);
        e.addTextChangedListener(watch(s -> { set.accept(s); changed(); }));
        field.addView(e, new FrameLayout.LayoutParams(ui.dp(budget ? 220 : 160), ui.dp(76)));
        TextView unit = ui.text(budget ? 24 : 18, ui.muted, 600);
        if (budget) {
            unit.setPadding(ui.dp(18), 0, 0, 0);
            unitLabels.put(key, unit);
            ChargingUi.set(unit, currencySymbol());
        } else {
            unit.setPadding(0, 0, ui.dp(14), 0);
            ChargingUi.set(unit, suffix);
        }
        field.addView(unit, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                (budget ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL));
        col.addView(field, ChargingUi.wrap());
        col.addView(error(key));
        return col;
    }

    private TextView error(String key) {
        TextView t = ui.text(19, ui.danger, 600);
        t.setPadding(ui.dp(4), ui.dp(6), 0, 0);
        t.setVisibility(GONE);
        errors.put(key, t);
        return t;
    }

    private EditText input(String value, String placeholder, boolean number) {
        EditText e = new EditText(getContext());
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        e.setTextColor(ui.text);
        e.setHintTextColor(ui.muted2);
        e.setSingleLine(true);
        e.setHint(placeholder);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        // the car's number pad has no decimal point: any non-digit typed is taken as one (SettingsForm.priceText)
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        // like the WebView's inputmode=decimal: a number pad, but the text key listener keeps * # and spaces
        if (number) e.setRawInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setPadding(ui.dp(18), 0, ui.dp(18), 0);
        e.setMinHeight(ui.dp(76));
        e.setBackground(ui.box(ui.night ? 0x08FFFFFF : ui.surface, ui.night ? 0x33E2C08D : ui.panelEdge, 14));
        e.setText(value);
        if (number) e.setTag(Boolean.TRUE);
        return e;
    }

    /** A watcher that keeps number boxes as SettingsForm.priceText and reports user edits only. */
    private TextWatcher watch(java.util.function.Consumer<String> onEdit) {
        return new TextWatcher() {
            private boolean self;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                if (self || binding) return;
                View focused = findFocus();
                if (focused instanceof EditText && focused.getTag() == Boolean.TRUE) {
                    String clean = SettingsForm.priceText(s.toString());
                    if (!clean.contentEquals(s)) {
                        self = true;
                        s.replace(0, s.length(), clean);
                        self = false;
                    }
                }
                onEdit.accept(s.toString());
            }
        };
    }

    private TextView defButton(String text, View.OnClickListener click) {
        TextView b = ui.text(19, ui.muted, 700);
        b.setGravity(Gravity.CENTER);
        b.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        b.setClickable(true);
        b.setFocusable(true);
        ChargingUi.set(b, text);
        b.setOnClickListener(click);
        return b;
    }

    private Spinner spinner(String[] labels, int selected, IntConsumer onPick) {
        Spinner s = new Spinner(getContext(), Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> a = new ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return style((TextView) super.getView(position, convertView, parent), false);
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return style((TextView) super.getDropDownView(position, convertView, parent), true);
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(Math.max(0, Math.min(labels.length - 1, selected)), false);
        s.setMinimumWidth(ui.dp(210));
        s.setMinimumHeight(ui.dp(76));
        s.setBackground(ui.box(ui.night ? 0x08FFFFFF : ui.surface, ui.night ? 0x33E2C08D : ui.panelEdge, 14));
        s.setPopupBackgroundDrawable(ui.box(ui.night ? 0xFF16161A : ui.surface, ui.cardEdge, 14));
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private int last = s.getSelectedItemPosition();
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding || position == last) return;
                last = position;
                onPick.accept(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        return s;
    }

    private TextView style(TextView t, boolean dropDown) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTextColor(ui.text);
        t.setMinHeight(ui.dp(76));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(ui.dp(18), 0, ui.dp(dropDown ? 18 : 30), 0);
        return t;
    }

    private static LinearLayout.LayoutParams cell(float weight, int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.leftMargin = gap;
        return lp;
    }
}

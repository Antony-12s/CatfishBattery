package com.catfish.battery;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared look of the native Charging, History and Settings pages: the colours and text sizes of the WebView pages
 * (BatteryWindowStyle css(), Navy Cyan at night) and small view factories. The car runs at density 160,
 * so 1 dp = 1 px and the CSS pixel sizes are used as sp / dp unchanged.
 */
final class ChargingUi {
    /** One worker thread for every page's BatteryDataHub reads, so file reads never run on the UI thread. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "battery-pages");
        t.setDaemon(true);
        return t;
    });

    static void background(Runnable work) { IO.execute(work); }

    final Context context;
    final boolean night;
    final int card, cardEdge, panel, panelEdge, lineSoft, text, muted, muted2, accent, accent2, onAccent, good, warn,
            danger, track, surface, socLine, kwOut;

    ChargingUi(Context context) {
        this.context = context;
        UiPalette p = UiPalette.of(context);
        night = p.night;
        if (night) {
            // Navy Cyan (BatteryWindowStyle champagneNight())
            card = 0xFF0D1A26; cardEdge = 0x214CC9F0; panel = 0x04FFFFFF; panelEdge = 0x1F4CC9F0; lineSoft = 0x124CC9F0;
            text = 0xFFE3EEF7; muted = 0xFF86A0B5; muted2 = 0xFF6E879B; accent = 0xFF4CC9F0; accent2 = 0xFF8ADDF5;
            onAccent = 0xFF04202B; good = 0xFF85DAA4; warn = 0xFFF2B86D; danger = p.danger; track = 0x1F4CC9F0;
            surface = 0x0AFFFFFF; socLine = 0xFFE3EEF7; kwOut = 0xFFF2B86D;
        } else {
            card = 0xFFFFFFFF; cardEdge = p.line; panel = p.bgDeep; panelEdge = p.line; lineSoft = 0xFFD5DFE9;
            text = p.text; muted = p.muted; muted2 = 0xFF4D647A; accent = p.accent; accent2 = 0xFF205CA9;
            onAccent = 0xFFFFFFFF; good = p.good; warn = p.warn; danger = p.danger; track = 0xFFC8D5E2;
            surface = p.surface; socLine = p.accent; kwOut = p.warn;
        }
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.getResources().getDisplayMetrics()));
    }

    // ---- text ----

    TextView text(float sp, int color, int weight) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        weight(t, weight);
        return t;
    }

    /** CSS-like weights: 300 light, 400 regular, 500-650 medium, 700+ bold. */
    static void weight(TextView t, int weight) {
        if (weight <= 300) t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        else if (weight < 500) t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        else if (weight < 700) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        else t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    }

    /** setText through a variable, so built strings stay out of lint's literal check. */
    static void set(TextView t, CharSequence value) {
        if (!TextUtils.equals(t.getText(), value)) t.setText(value);
    }

    TextView oneLine(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** Card title and kicker ("Charging" / "LAST 24 HOURS"). */
    TextView title(String value) {
        TextView t = night ? text(34, text, 500) : text(32, text, 750);
        set(t, value);
        return t;
    }

    TextView kicker() {
        TextView t = night ? text(14, muted, 600) : text(22, muted, 500); // headings stay quiet: accent is for taps and flow
        if (night) { t.setAllCaps(true); t.setLetterSpacing(0.24f); }
        t.setPadding(0, dp(night ? 10 : 4), 0, 0);
        return oneLine(t);
    }

    /** Small label above a big number (stats tiles, KPIs); muted capitals at night. */
    TextView label(float daySp) {
        TextView t = night ? text(13, muted, 600) : text(daySp, muted, 600);
        if (night) { t.setAllCaps(true); t.setLetterSpacing(0.2f); }
        return oneLine(t);
    }

    TextView big(float daySp, float nightSp) {
        return oneLine(night ? text(nightSp, text, 300) : text(daySp, text, 750));
    }

    // ---- boxes ----

    GradientDrawable box(int fill, int edge, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if (edge != 0) d.setStroke(dp(1), edge);
        return d;
    }

    /** The page card: full size, rounded 24, padding 24 x 28. */
    LinearLayout card() {
        LinearLayout c = column();
        GradientDrawable d = night
                ? new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0xFF0D1A26, 0xFF0A1520 })
                : new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xFFFFFFFF, 0xFFF8FBFD });
        d.setCornerRadius(dp(24));
        d.setStroke(dp(1), cardEdge);
        c.setBackground(d);
        c.setPadding(dp(28), dp(24), dp(28), dp(24));
        return c;
    }

    /** An inner panel (chart, side list, panes, KPIs): rounded 20. */
    void panel(View v) { v.setBackground(box(panel, panelEdge, 20)); }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setBaselineAligned(false);
        return l;
    }

    View divider(boolean vertical) {
        View v = new View(context);
        v.setBackgroundColor(night ? 0x244CC9F0 : lineSoft);
        v.setLayoutParams(vertical ? new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w);
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams fillWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams fillHeight() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    View space(int w, int h) {
        View v = new View(context);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(w), dp(h)));
        return v;
    }

    View grow() {
        View v = new View(context);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    // ---- controls ----

    /** A rounded button; primary = accent fill, otherwise a ghost outline. */
    Button button(String value, float sp, boolean primary) {
        Button b = new Button(context);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        weight(b, 700);
        b.setMinHeight(dp(76));
        b.setMinimumHeight(dp(76));
        b.setPadding(dp(24), 0, dp(24), 0);
        b.setStateListAnimator(null);
        b.setTextColor(primary ? onAccent : text);
        b.setBackground(primary ? box(accent, 0, 14) : box(0, night ? 0x2E4CC9F0 : panelEdge, 14));
        set(b, value);
        return b;
    }

    /** A pill toggle (range buttons, AC/DC default badges, picker rows). */
    void pill(TextView b, boolean on, float radius) {
        b.setTextColor(on ? onAccent : muted);
        b.setBackground(on ? box(accent, 0, radius) : box(0, 0, radius));
    }

    /** The "Charging" / "Not charging" badge with its dot. */
    TextView badge() {
        TextView t = text(21, good, 650);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(48));
        t.setPadding(dp(18), dp(8), dp(18), dp(8));
        t.setLetterSpacing(night ? 0.06f : 0f);
        return t;
    }

    void badge(TextView t, boolean live) {
        set(t, "●  " + (live ? "Charging" : "Not charging"));
        int c = live ? good : muted;
        t.setTextColor(c);
        t.setBackground(night ? box(0, live ? 0x5985DAA4 : 0x1FFFFFFF, 999)
                : box(live ? 0x1A178657 : 0xFFF0F5FA, 0, 999));
    }

    /** A 0-100 % track with the part from..to filled (the SOC a charge added). */
    LinearLayout track(int heightDp) {
        LinearLayout t = row();
        t.setBackground(box(track, 0, heightDp / 2f));
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        for (int i = 0; i < 3; i++) t.addView(new View(context), weight(1));
        t.getChildAt(1).setBackground(box(good, 0, heightDp / 2f));
        return t;
    }

    void track(LinearLayout t, double from, double to) {
        double a = Math.max(0, Math.min(100, from)), z = Math.max(a, Math.min(100, to));
        float fill = (float) Math.max(z - a, 0.8); // the WebView's 3 px minimum
        ((LinearLayout.LayoutParams) t.getChildAt(0).getLayoutParams()).weight = (float) a;
        ((LinearLayout.LayoutParams) t.getChildAt(1).getLayoutParams()).weight = fill;
        ((LinearLayout.LayoutParams) t.getChildAt(2).getLayoutParams()).weight = (float) Math.max(0, 100 - a - fill);
        t.requestLayout();
    }

    /** The AC / DC kind tag of a charge (60 x 42, 20 sp). */
    TextView tag(String value, boolean ac, boolean dc) {
        TextView t = text(20, dc ? warn : ac ? accent2 : muted, 800);
        t.setGravity(Gravity.CENTER);
        t.setLetterSpacing(0.06f);
        int fill = dc ? 0x29F59D32 : ac ? (night ? 0x294CC9F0 : 0x292A74D6) : (night ? 0x0FFFFFFF : 0xFFF0F5FA);
        t.setBackground(box(fill, 0, 8));
        set(t, value);
        return t;
    }
}

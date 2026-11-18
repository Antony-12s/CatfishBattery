package com.catfish.battery;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

/**
 * The battery pages (Status / Charging / History / Settings) as native views under the native tab bar. Status gets
 * BatteryDataHub.dataJson() once a second while shown, read on a background thread; Charging and History refresh every
 * 30 s and Settings when shown, as the WebView pages polled. Day/night changes recreate the activity (resolved colours
 * do not update on existing views); the selected page survives the recreate.
 */
public final class BatteryActivity extends Activity {
    private static final String TAG = "CatfishBattery";
    private static final String[] PAGES = { "status", "charging", "history", "settings" };
    private static final int SLOW_REFRESH_S = 30;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Button[] tabs;
    private View[] pages;
    private StatusPage status;
    private ChargingPage charging;
    private HistoryPage history;
    private SettingsPage settings;
    private volatile String page = "status";
    private volatile boolean resumed;
    private ScheduledExecutorService poller;
    private ScheduledFuture<?> tick;
    private int ticks;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        BatteryService.start(this);
        BatteryDataHub.acquire(this);
        setContentView(R.layout.activity_battery);
        if (state != null) page = state.getString("page", page);
        tabs = new Button[] { findViewById(R.id.battery_tab_status), findViewById(R.id.battery_tab_charging),
                findViewById(R.id.battery_tab_history), findViewById(R.id.battery_tab_settings) };
        status = new StatusPage(this);
        charging = new ChargingPage(this);
        history = new HistoryPage(this);
        settings = new SettingsPage(this);
        pages = new View[] { status, charging, history, settings };
        FrameLayout slot = findViewById(R.id.battery_page_slot);
        for (View p : pages) {
            p.setVisibility(View.GONE);
            slot.addView(p, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        for (int i = 0; i < tabs.length; i++) {
            final String name = PAGES[i];
            tabs[i].setOnClickListener(v -> select(name));
        }
        show(page);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        poller = Executors.newSingleThreadScheduledExecutor();
        setPageVisible(page, true);
        ticks = 0;
        tick = poller.scheduleWithFixedDelay(this::onTick, 0, 1, TimeUnit.SECONDS);
    }

    @Override protected void onPause() {
        resumed = false;
        if (tick != null) tick.cancel(false);
        if (poller != null) poller.shutdownNow();
        poller = null;
        setPageVisible(page, false);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("page", page);
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        // Reinflate native chrome and pages: resolved day/night colours do not update on existing Views.
        recreate();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        BatteryDataHub.release();
        super.onDestroy();
    }

    private void select(String name) {
        if (name.equals(page)) return;
        if (resumed) setPageVisible(page, false);
        page = name;
        show(name);
        if (resumed) {
            setPageVisible(name, true);
            ticks = 0;
            if ("status".equals(name) && poller != null) poller.execute(this::pollStatus);
        }
    }

    private void show(String name) {
        for (int i = 0; i < PAGES.length; i++) {
            boolean on = PAGES[i].equals(name);
            pages[i].setVisibility(on ? View.VISIBLE : View.GONE);
            tabs[i].setSelected(on);
        }
    }

    private void setPageVisible(String name, boolean visible) {
        switch (name) {
            case "charging": charging.setVisibleNow(visible); if (visible) charging.refresh(); break;
            case "history": history.setVisibleNow(visible); if (visible) history.refresh(); break;
            case "settings": settings.setVisibleNow(visible); if (visible) settings.refresh(); break;
            default: status.setVisibleNow(visible); break;
        }
    }

    /** Background thread, once a second while resumed. */
    private void onTick() {
        String now = page;
        if ("status".equals(now)) {
            pollStatus();
            return;
        }
        main.post(() -> {
            if (!resumed || !now.equals(page) || ++ticks < SLOW_REFRESH_S) return;
            ticks = 0;
            if ("charging".equals(now)) charging.refresh();
            else if ("history".equals(now)) history.refresh();
        });
    }

    private void pollStatus() {
        JSONObject data;
        try {
            data = new JSONObject(BatteryDataHub.dataJson());
        } catch (Exception e) {
            Log.e(TAG, "Battery data unavailable", e);
            data = offline();
        }
        final JSONObject snapshot = data;
        main.post(() -> {
            if (resumed && "status".equals(page)) status.refresh(snapshot);
        });
    }

    private static JSONObject offline() {
        try {
            return new JSONObject("{\"streamConnected\":false,\"cells\":[]}");
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}

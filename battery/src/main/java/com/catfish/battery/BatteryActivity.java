package com.catfish.battery;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * The battery pages (Status / Charging / History / Settings) in a WebView, fed in-process: the page HTML
 * is loaded directly and its data fetches are answered by {@link #intercept}. Ported from Custom
 * Profiles' BatteryCategory.
 */
public final class BatteryActivity extends Activity {
    private static final String TAG = "CatfishBattery";
    private static final String BASE = "https://battery.local/";
    private static final String[] PAGES = { "status", "charging", "history", "settings" };
    private WebView web;
    private Button[] tabs;
    private String page = "status";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        BatteryService.start(this);
        BatteryDataHub.acquire(this);
        setContentView(R.layout.activity_battery);
        if (state != null) page = state.getString("page", page);
        tabs = new Button[] { findViewById(R.id.battery_tab_status), findViewById(R.id.battery_tab_charging),
                findViewById(R.id.battery_tab_history), findViewById(R.id.battery_tab_settings) };
        for (int i = 0; i < tabs.length; i++) {
            final String name = PAGES[i];
            tabs[i].setOnClickListener(v -> { page = name; load(); });
        }
        attachWebView();
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) { web.onResume(); load(); }
    }

    @Override protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("page", page);
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        // Reinflate native chrome too: resolved day/night colors do not update on existing Views.
        recreate();
    }

    @Override protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        BatteryDataHub.release();
        super.onDestroy();
    }

    private void attachWebView() {
        FrameLayout slot = findViewById(R.id.battery_web_slot);
        try {
            web = new WebView(this);
        } catch (Throwable t) {
            // No WebView provider on this build: say so instead of crashing.
            Log.e(TAG, "WebView unavailable", t);
            findViewById(R.id.battery_unavailable).setVisibility(View.VISIBLE);
            slot.setVisibility(View.GONE);
            return;
        }
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        // The Settings page saves through this: intercepted requests carry no body. Only this app's own
        // pages ever load here (navigation is blocked below).
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public String saveSettings(String json) { return BatteryDataHub.saveSettings(json); }
        }, "CFBattery");
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return intercept(request);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // the pages' own nav links are meaningless here
            }
        });
        slot.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void load() {
        if (web == null) return;
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        web.loadDataWithBaseURL(BASE, BatteryWebPages.embedded(page, night), "text/html", "utf-8", null);
        for (int i = 0; i < tabs.length; i++) {
            boolean on = PAGES[i].equals(page);
            tabs[i].setSelected(on);
        }
    }

    private static WebResourceResponse intercept(WebResourceRequest request) {
        String path = request.getUrl().getPath();
        if (path == null) return null;
        try {
            if ("/batt-data".equals(path)) return json(BatteryDataHub.dataJson());
            if ("/batt-totals-reset".equals(path) && "POST".equals(request.getMethod())) {
                BatteryDataHub.resetTotals();
                return json("{}");
            }
            if ("/batt-history-data".equals(path)) return json(BatteryDataHub.historyJson(request.getUrl().getQueryParameter("range")));
            if ("/batt-sessions".equals(path)) return json(BatteryDataHub.sessionsJson());
            if ("/batt-settings-data".equals(path)) return json(BatteryDataHub.settingsJson());
            if ("/batt-session-charger".equals(path) && "POST".equals(request.getMethod())) {
                BatteryDataHub.setCharger(request.getUrl().getQueryParameter("start"), request.getUrl().getQueryParameter("charger"));
                return json("{}");
            }
        } catch (Exception e) {
            Log.e(TAG, "Battery page request failed", e);
        }
        return null;
    }

    private static WebResourceResponse json(String body) {
        return new WebResourceResponse("application/json", "utf-8",
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
}

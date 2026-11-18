package com.catfish.battery;

import android.content.Context;
import android.widget.FrameLayout;
import android.view.View;

import org.json.JSONObject;

/**
 * History chart: SOC line, kW out/in bars and charging bands with a touch tooltip.
 * Owner: codex-motion (Codex). Contract stub created by the coordinator: keep the class name, constructor and method
 * signatures; replace the bodies. See docs/superpowers/plans/2026-11-18-native-ui.md.
 */
final class HistoryChartView extends View {
    HistoryChartView(Context context) {
        super(context);
    }

    /** Same JSON as BatteryDataHub.historyJson(range), the object the WebView chart received. */
    void setData(JSONObject historyData) { }

    void setNight(boolean night) { }
}

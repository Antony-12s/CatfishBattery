package com.catfish.battery;

import android.content.Context;
import android.widget.FrameLayout;
import android.view.View;

import org.json.JSONObject;

/**
 * Charge curve of the selected or live charge session (Charging page).
 * Owner: codex-motion (Codex). Contract stub created by the coordinator: keep the class name, constructor and method
 * signatures; replace the bodies. See docs/superpowers/plans/2026-11-18-native-ui.md.
 */
final class ChargeCurveView extends View {
    ChargeCurveView(Context context) {
        super(context);
    }

    /** The same JSON value the WebView's charge-curve drawing received (see BatteryWebPages chargeCurve). */
    void setData(JSONObject curveData) { }

    void setNight(boolean night) { }
}

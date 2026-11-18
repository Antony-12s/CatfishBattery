package com.catfish.battery;

import android.content.Context;
import android.widget.FrameLayout;
import android.view.View;

import org.json.JSONObject;

/**
 * Transparent overlay above PackSchematicView with the moving energy-flow dots, in the same 1180x470 mapping.
 * Owner: codex-motion (Codex). Contract stub created by the coordinator: keep the class name, constructor and method
 * signatures; replace the bodies. See docs/superpowers/plans/2026-11-18-native-ui.md.
 */
final class FlowOverlayView extends View {
    FlowOverlayView(Context context) {
        super(context);
    }

    /** Same JSON as BatteryDataHub.dataJson(); decides which flows run, their direction and speed. */
    void setData(JSONObject batteryData) { }

    /** Stop animating while the page is hidden (no frames drawn, no CPU). */
    void setRunning(boolean running) { }

    void setNight(boolean night) { }
}

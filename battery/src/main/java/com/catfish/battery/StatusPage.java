package com.catfish.battery;

import android.content.Context;
import android.widget.FrameLayout;
import android.view.View;

import org.json.JSONObject;

/**
 * Status page: Pack card (Schematic/Cells switch, badges, Reset totals), facts rows and energy totals.
 * Owner: opus-shell (Claude). Contract stub created by the coordinator: keep the class name, constructor and method
 * signatures; replace the bodies. See docs/superpowers/plans/2026-11-18-native-ui.md.
 */
final class StatusPage extends FrameLayout {
    StatusPage(Context context) {
        super(context);
    }

    /** Called about once a second while visible with BatteryDataHub.dataJson(). */
    void refresh(JSONObject batteryData) { }

    void setVisibleNow(boolean visible) { }
}

package com.catfish.battery;

import android.content.Context;
import android.widget.FrameLayout;
import android.view.View;

import org.json.JSONObject;

/**
 * The static 3D pack schematic: the original 1180x470 design space drawn on a Canvas, scaled to fit both axes without distortion.
 * Owner: codex-art (Codex). Contract stub created by the coordinator: keep the class name, constructor and method
 * signatures; replace the bodies. See docs/superpowers/plans/2026-11-18-native-ui.md.
 */
final class PackSchematicView extends View {
    PackSchematicView(Context context) {
        super(context);
    }

    /** Same JSON as BatteryDataHub.dataJson(), the object the WebView's drawPack/drawSystem received. */
    void setData(JSONObject batteryData) { }

    void setNight(boolean night) { }
}

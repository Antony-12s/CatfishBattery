package com.catfish.battery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts charge logging after a head-unit boot or an app update, without opening the screen. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        BatteryService.start(context);
    }
}

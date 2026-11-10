package com.catfish.battery;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/** Foreground service that holds the battery hub so charges are logged while no page is open. */
public final class BatteryService extends Service {
    private static final String CHANNEL = "battery_logger";
    private boolean holding;

    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, BatteryService.class));
        } catch (RuntimeException e) {
            Log.w("CatfishBattery", "Battery service could not start", e);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.battery_service_running),
                NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, BatteryActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        startForeground(9002, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentTitle(getString(R.string.battery_service_running))
                .setContentIntent(open).setOngoing(true).build());
        if (!holding) { BatteryDataHub.acquire(this); holding = true; }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (holding) { BatteryDataHub.release(); holding = false; }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

package com.catfish.battery;

import android.content.Context;

/**
 * One battery reader + logger shared by every consumer (the web console and the in-app Battery
 * category), so the APVP subscription, the CSV logger exist once no matter
 * how many views show the data. Reference counted: the first acquire starts them, the last release
 * stops them.
 */
final class BatteryDataHub {
    private static VehicleBatteryReader reader;
    private static BatteryLogger logger;
    private static int users;

    private BatteryDataHub() { }

    static synchronized void acquire(Context context) {
        if (users++ > 0) return;
        Context app = context.getApplicationContext();
        reader = new VehicleBatteryReader(app);
        // shortcut: no root here, so no car_service RANGE_REMAINING probe; range shows only if the VHAL stream pushes it.
        logger = new BatteryLogger(app, reader);
        logger.onAlert = soc -> announce(app, soc);
        logger.start();
    }

    /** The charge alert: a toast over whatever is on screen, and a notification that stays until read. */
    private static void announce(Context app, int soc) {
        String text = "Battery charged to " + soc + " %";
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show());
        try {
            android.app.NotificationManager nm = app.getSystemService(android.app.NotificationManager.class);
            nm.createNotificationChannel(new android.app.NotificationChannel("battery_alert", "Charge alert",
                    android.app.NotificationManager.IMPORTANCE_HIGH));
            nm.notify(9003, new android.app.Notification.Builder(app, "battery_alert")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_charging).setContentTitle(text)
                    .setAutoCancel(true).build());
        } catch (RuntimeException e) {
            android.util.Log.e("CatfishBattery", "Charge alert notification failed", e);
        }
    }

    static synchronized void release() {
        if (users == 0 || --users > 0) return;
        logger.stop();
        reader.close();
        logger = null;
        reader = null;
    }

    static synchronized VehicleBatteryReader reader() { return reader; }
    static synchronized BatteryLogger logger() { return logger; }

    /** JSON for the pages' 1 s poll, with the saved energy totals; an offline snapshot when nothing holds the hub. */
    static String dataJson() {
        VehicleBatteryReader r = reader();
        BatteryLogger l = logger();
        String json = r == null ? "{\"streamConnected\":false,\"cells\":[]}" : r.read().toJson();
        return l == null ? json : json.substring(0, json.length() - 1) + ",\"totals\":" + l.totalsJson() + "}";
    }

    static void resetTotals() {
        BatteryLogger l = logger();
        if (l != null) l.resetTotals(System.currentTimeMillis());
    }

    static String historyJson(String range) {
        BatteryLogger l = logger();
        long hours = "1h".equals(range) ? 1L : "6h".equals(range) ? 6L : "7d".equals(range) ? 168L : "30d".equals(range) ? 720L : 24L;
        return l == null ? "{\"points\":[],\"sessions\":[],\"live\":null,\"stats\":{}}"
                : l.historyJson(System.currentTimeMillis() - hours * 3600000L, 600);
    }

    static String sessionsJson() {
        BatteryLogger l = logger();
        return l == null ? "{\"sessions\":[],\"live\":null}" : l.sessionsJson();
    }

    static String settingsJson() {
        BatteryLogger l = logger();
        return l == null ? "{\"saved\":false,\"settings\":" + BatterySettings.clean(null) + "}" : l.settingsJson();
    }

    /** Saves the settings a page sent, cleaned; the clean copy comes back. */
    static String saveSettings(String raw) {
        BatteryLogger l = logger();
        if (l == null) throw new IllegalStateException("The battery logger is not running.");
        return l.saveSettings(raw);
    }

    /** The driver's pick of charger for the charge that started at {@code start}; empty goes back to the default. */
    static void setCharger(String start, String charger) {
        BatteryLogger l = logger();
        if (l == null) throw new IllegalStateException("The battery logger is not running.");
        l.setCharger(Long.parseLong(start), charger == null ? "" : charger);
    }
}

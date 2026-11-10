package com.catfish.battery;

import android.content.Context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One shared read-only VHAL observer plus the latest value of every streamed property. */
public final class VehiclePropertyManager {
    private static final Map<Integer, Integer> INT_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, Float> FLOAT_CACHE = new ConcurrentHashMap<>();
    private static VehiclePropertyObserver observer;

    public VehiclePropertyManager(Context context) { }

    static Map<Integer, Integer> intCache() { return INT_CACHE; }
    static Map<Integer, Float> floatCache() { return FLOAT_CACHE; }

    public synchronized VehiclePropertyObserver getObserver() {
        synchronized (VehiclePropertyManager.class) {
            if (observer == null) {
                observer = new VehiclePropertyObserver();
                observer.addListener(new VehiclePropertyObserver.VehiclePropertyListener() {
                    @Override public void onPropertyUpdated(int propId, int areaId, int intValue, float floatValue, boolean isFloat) {
                        if (isFloat) FLOAT_CACHE.put(propId, floatValue); else INT_CACHE.put(propId, intValue);
                    }
                    @Override public void onConnectionStateChanged(boolean connected) { }
                });
                observer.start();
            }
            return observer;
        }
    }
}

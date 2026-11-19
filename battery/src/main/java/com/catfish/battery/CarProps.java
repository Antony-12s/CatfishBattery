package com.catfish.battery;

import android.annotation.SuppressLint;
import android.content.Context;

/**
 * One android.car CarPropertyManager per process, read only, through reflection (android.car is the head unit's
 * system library). Built once and kept, never rebuilt per failed read (that leaked a Car connection each time):
 * runtime grants such as CAR_ENERGY apply to the same manager. Null while the car service is unavailable.
 * shortcut: if the car service restarts, reads fail (unavailable, fail closed) until the app restarts; add a
 * CarServiceLifecycleListener if that is ever seen on the car.
 */
@SuppressLint("PrivateApi")
final class CarProps {
    private static Object manager;

    private CarProps() { }

    static synchronized Object manager(Context context) {
        if (manager != null) return manager;
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            Object car = carClass.getMethod("createCar", Context.class).invoke(null, context.getApplicationContext());
            if (car != null) manager = carClass.getMethod("getCarManager", String.class).invoke(car, "property");
        } catch (ReflectiveOperationException | RuntimeException e) {
            manager = null; // no car service yet: the next caller tries again
        }
        return manager;
    }
}

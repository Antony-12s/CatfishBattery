package com.catfish.battery;

import android.content.Context;

import dalvik.system.PathClassLoader;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * The BECM's own SOH (UDS DID 0x496D) through Dxapp's VehicleDiagIF middleware, the same path as
 * battery/tools/becm-soh/ReadBecmDid.java. The user approved one automatic read per day when the app opens
 * (2026-11-18, see DECISIONS.md). Only in Park: gear events from CarPropertyManager void the read the moment either
 * gear leaves Park or turns unknown, and the whole read has a 15 s budget. One request (22 49 6D, enforced by
 * UdsPolicy), mode 5, tester 0x0E80, and the channel is always released. Never sends anything else.
 */
final class BecmSoh {
    static final int GEAR_SELECTION = 0x11400400, CURRENT_GEAR = 0x11400401, PARK = 4;
    private static final int MODE = 5;
    private static final char TESTER = 0x0E80;
    private static final char BECM = UdsPolicy.BECM;
    private static final byte[] REQUEST = UdsPolicy.BECM_SOH;
    private static final long READ_BUDGET_S = 15; // whole read: wait for Park, middleware, channel, reply
    private static final String SDK_APK = "/system/app/Dxapp/Dxapp.apk";

    private BecmSoh() { }

    /** Percent from a positive 62 49 6D reply (4 data bytes, big endian, 0.01 % per bit); NaN otherwise. */
    static double decode(byte[] reply) {
        if (reply == null || reply.length != 7 || (reply[0] & 0xFF) != 0x62 || reply[1] != 0x49 || reply[2] != 0x6D)
            return Double.NaN;
        long raw = 0;
        for (int i = 3; i < 7; i++) raw = (raw << 8) | (reply[i] & 0xFF);
        double pct = raw / 100.0;
        return pct > 0 && pct <= 100 ? pct : Double.NaN;
    }

    static boolean sameDay(long aMs, long bMs, ZoneId zone) {
        return Instant.ofEpochMilli(aMs).atZone(zone).toLocalDate().equals(Instant.ofEpochMilli(bMs).atZone(zone).toLocalDate());
    }

    /**
     * Park from gear events, the way the car reports them: both GEAR_SELECTION and CURRENT_GEAR must be Park.
     * Any step out of Park, an unknown value or an error event bumps the generation, so a token taken in Park stays
     * void even if the car comes back to Park. Every token also expires with the read's deadline.
     */
    static final class ParkGuard implements AutoCloseable {
        private final LongSupplier clock;
        private final long deadline;
        private Integer selection, current;
        private boolean seenSelection, seenCurrent, parked;
        private int generation;
        Runnable closer = () -> { };

        ParkGuard(LongSupplier clock, long budgetNs) {
            this.clock = clock;
            this.deadline = clock.getAsLong() + budgetNs;
        }

        synchronized void onGear(int property, Integer value) {
            if (property == GEAR_SELECTION) { selection = value; seenSelection = true; }
            else if (property == CURRENT_GEAR) { current = value; seenCurrent = true; }
            else return;
            boolean now = selection != null && selection == PARK && current != null && current == PARK;
            if (parked && !now) generation++;
            parked = now;
            notifyAll();
        }

        /** A one-off read for a gear that has had no event yet; it never overrides an event. */
        synchronized void seed(int property, Integer value) {
            if ((property == GEAR_SELECTION && !seenSelection) || (property == CURRENT_GEAR && !seenCurrent)) onGear(property, value);
        }

        synchronized int awaitPark(long timeoutMs) throws IOException, InterruptedException {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (!parked) {
                long left = end - System.nanoTime();
                if (left <= 0) throw new IOException("Park not confirmed: " + describe());
                wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
            }
            return generation;
        }

        synchronized void check(int token) throws IOException {
            if (!parked || generation != token) throw new IOException("Left Park or gear unknown: " + describe());
            if (clock.getAsLong() > deadline) throw new IOException("BECM read took too long");
        }

        private String describe() { return "selection=" + selection + " current=" + current; }

        @Override public void close() { closer.run(); }
    }

    /** One guarded DID 496D read; throws with the reason when anything is off. */
    static double read(Context context) throws Exception {
        UdsPolicy.require(BECM, REQUEST); // before anything is opened
        try (ParkGuard park = watchGear(context)) {
            int token = park.awaitPark(2000);
            return readInPark(park, token);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
        }
    }

    /** Gear events from the shared CarPropertyManager (read only); fails closed when they cannot be registered. */
    @android.annotation.SuppressLint("PrivateApi")
    private static ParkGuard watchGear(Context context) throws Exception {
        Object manager = CarProps.manager(context);
        if (manager == null) throw new IOException("Car service unavailable");
        ParkGuard guard = new ParkGuard(System::nanoTime, TimeUnit.SECONDS.toNanos(READ_BUDGET_S));
        Class<?> type = Class.forName("android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback");
        Object callback = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, values) -> {
            switch (method.getName()) {
                case "onChangeEvent": {
                    Object v = values[0];
                    int id = (Integer) v.getClass().getMethod("getPropertyId").invoke(v);
                    int status = (Integer) v.getClass().getMethod("getStatus").invoke(v);
                    Object gear = v.getClass().getMethod("getValue").invoke(v);
                    guard.onGear(id, status == 0 && gear instanceof Integer ? (Integer) gear : null);
                    return null;
                }
                case "onErrorEvent": guard.onGear((Integer) values[0], null); return null;
                case "equals": return proxy == values[0]; // the manager keys its listeners by callback
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "CFBecmParkGuard";
                default: return null;
            }
        });
        Method unregister = manager.getClass().getMethod("unregisterCallback", type);
        guard.closer = () -> {
            try { unregister.invoke(manager, callback); } catch (ReflectiveOperationException | RuntimeException ignored) { }
        };
        Method register = manager.getClass().getMethod("registerCallback", type, int.class, float.class);
        Method getInt = manager.getClass().getMethod("getIntProperty", int.class, int.class);
        for (int id : new int[]{GEAR_SELECTION, CURRENT_GEAR}) {
            if (!(Boolean) register.invoke(manager, callback, id, 0f)) {
                guard.close();
                throw new IOException("Gear events unavailable");
            }
            try { guard.seed(id, (Integer) getInt.invoke(manager, id, 0)); } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        return guard;
    }

    private static double readInPark(ParkGuard park, int token) throws Exception {
        park.check(token);
        PathClassLoader sdk = new PathClassLoader(SDK_APK, SDK_APK + "!/lib/arm64-v8a:/vendor/lib64", BecmSoh.class.getClassLoader());
        Class<?> api = sdk.loadClass("com.geely.udsmiddleware.VehicleDiagIF");
        Class<?> listener = sdk.loadClass("com.geely.udsmiddleware.IVehicleDiagListener");
        Object client = api.getConstructor().newInstance();
        LinkedBlockingQueue<Object> responses = new LinkedBlockingQueue<>();
        AtomicBoolean awaiting = new AtomicBoolean();
        Object callback = Proxy.newProxyInstance(sdk, new Class<?>[]{listener}, (proxy, method, values) -> {
            if (!awaiting.get()) return null;
            if (method.getName().equals("onUdsResponse") && (Integer) values[0] == MODE
                    && (Character) values[1] == BECM && (Character) values[2] == TESTER) {
                byte[] data = (byte[]) values[4];
                int length = (Integer) values[3];
                if (data != null && length > 0 && length <= data.length) responses.offer(Arrays.copyOf(data, length));
            } else if (method.getName().equals("onDoIPMessageSendNackNotification")) {
                responses.offer("DoIP NACK " + values[0]);
            }
            return null;
        });
        api.getMethod("registeListener", listener).invoke(client, callback);
        // This SDK's local init path stores the Context but does not dereference it (same as the CLI tool).
        api.getMethod("init", Context.class).invoke(client, new Object[]{null});
        Method status = api.getMethod("getStatusWithMiddleware");
        long readyBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!(Boolean) status.invoke(client)) {
            park.check(token);
            if (System.nanoTime() >= readyBy) throw new IOException("Middleware did not become ready");
            Thread.sleep(100);
        }
        Method getMode = api.getMethod("getCurDiagType");
        int currentMode = (Integer) getMode.invoke(client);
        if (currentMode != 0) throw new IOException("Diagnostic channel busy, mode " + currentMode);
        park.check(token);
        int result = (Integer) api.getMethod("takeDiagWay", int.class, char.class, int.class).invoke(client, MODE, TESTER, 1);
        if (result != 0) throw new IOException("Middleware declined the diagnostic channel: " + result);
        try {
            park.check(token);
            if ((Integer) getMode.invoke(client) != MODE) throw new IOException("Requested diagnostic mode did not become active");
            awaiting.set(true);
            UdsPolicy.require(BECM, REQUEST);
            boolean accepted = (Boolean) api.getMethod("sendUdsPayload", int.class, char.class, char.class, int.class, byte[].class)
                    .invoke(client, MODE, TESTER, BECM, REQUEST.length, REQUEST);
            if (!accepted) throw new IOException("Middleware rejected the request");
            return awaitResponse(responses, park, token);
        } finally {
            awaiting.set(false);
            if ((Integer) getMode.invoke(client) == MODE) api.getMethod("releaseDiagWay").invoke(client);
        }
    }

    private static double awaitResponse(LinkedBlockingQueue<Object> responses, ParkGuard park, int token) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            park.check(token);
            Object item = responses.poll(125, TimeUnit.MILLISECONDS);
            if (item instanceof String) throw new IOException((String) item);
            if (!(item instanceof byte[])) continue;
            byte[] reply = (byte[]) item;
            if (reply.length == 3 && (reply[0] & 0xFF) == 0x7F && reply[1] == 0x22) {
                if (reply[2] == 0x78) continue; // response pending
                throw new IOException(String.format("BECM refused: 7F 22 %02X", reply[2] & 0xFF));
            }
            double pct = decode(reply);
            if (!Double.isNaN(pct)) return pct;
        }
        throw new IOException("No final BECM response before timeout");
    }
}

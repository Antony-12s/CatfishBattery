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
import java.util.function.Supplier;

/**
 * The BECM's own SOH (UDS DID 0x496D) through Dxapp's VehicleDiagIF middleware, the same path as
 * battery/tools/becm-soh/ReadBecmDid.java. The user approved one automatic read per day when the app opens
 * (2026-11-18, see DECISIONS.md). Only in Park: both gear properties are re-read every 100 ms and the read aborts
 * as soon as a fresh read stops showing Park. One request (22 49 6D), mode 5, tester 0x0E80, and the channel is
 * always released. Never sends anything else.
 */
final class BecmSoh {
    static final int GEAR_SELECTION = 0x11400400, CURRENT_GEAR = 0x11400401, PARK = 4;
    private static final int MODE = 5;
    private static final char TESTER = 0x0E80;
    private static final char BECM = 0x1635;
    private static final byte[] REQUEST = {0x22, 0x49, 0x6D};
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

    /** Both gear properties must read Park on a fresh read; anything else (other gear, null, error) is unsafe. */
    static final class ParkGuard implements AutoCloseable {
        private static final long MAX_AGE_NS = TimeUnit.MILLISECONDS.toNanos(1500);
        private volatile long parkedAt;
        private volatile String last = "no read yet";
        private volatile boolean running = true;
        private final Thread poller;

        ParkGuard(Supplier<Integer> selection, Supplier<Integer> current) {
            poller = new Thread(() -> {
                while (running) {
                    Integer s = selection.get(), c = current.get();
                    last = "selection=" + s + " current=" + c;
                    parkedAt = s != null && s == PARK && c != null && c == PARK ? System.nanoTime() : 0;
                    try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                }
            }, "CFBecmParkGuard");
            poller.setDaemon(true);
            poller.start();
        }

        void awaitPark() throws IOException, InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                long at = parkedAt;
                if (at != 0 && System.nanoTime() - at < MAX_AGE_NS) return;
                Thread.sleep(50);
            }
            throw new IOException("Park not confirmed: " + last);
        }

        void check() throws IOException {
            long at = parkedAt;
            if (at == 0 || System.nanoTime() - at > MAX_AGE_NS) throw new IOException("Left Park or gear unknown: " + last);
        }

        @Override public void close() { running = false; poller.interrupt(); }
    }

    /** One guarded DID 496D read; throws with the reason when anything is off. */
    static double read(Context context) throws Exception {
        try (ParkGuard park = new ParkGuard(SohRecorder.carInt(context, GEAR_SELECTION), SohRecorder.carInt(context, CURRENT_GEAR))) {
            park.awaitPark();
            return readInPark(park);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
        }
    }

    private static double readInPark(ParkGuard park) throws Exception {
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
            park.check();
            if (System.nanoTime() >= readyBy) throw new IOException("Middleware did not become ready");
            Thread.sleep(100);
        }
        Method getMode = api.getMethod("getCurDiagType");
        int currentMode = (Integer) getMode.invoke(client);
        if (currentMode != 0) throw new IOException("Diagnostic channel busy, mode " + currentMode);
        park.check();
        int result = (Integer) api.getMethod("takeDiagWay", int.class, char.class, int.class).invoke(client, MODE, TESTER, 1);
        if (result != 0) throw new IOException("Middleware declined the diagnostic channel: " + result);
        try {
            park.check();
            if ((Integer) getMode.invoke(client) != MODE) throw new IOException("Requested diagnostic mode did not become active");
            awaiting.set(true);
            boolean accepted = (Boolean) api.getMethod("sendUdsPayload", int.class, char.class, char.class, int.class, byte[].class)
                    .invoke(client, MODE, TESTER, BECM, REQUEST.length, REQUEST);
            if (!accepted) throw new IOException("Middleware rejected the request");
            return awaitResponse(responses, park);
        } finally {
            awaiting.set(false);
            if ((Integer) getMode.invoke(client) == MODE) api.getMethod("releaseDiagWay").invoke(client);
        }
    }

    private static double awaitResponse(LinkedBlockingQueue<Object> responses, ParkGuard park) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            park.check();
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

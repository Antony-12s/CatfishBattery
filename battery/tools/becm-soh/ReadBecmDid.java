import android.content.Context;
import dalvik.system.PathClassLoader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReadBecmDid {
    private static final int MODE = 5;
    private static final char TESTER = 0x0E80;
    private static final char BECM = 0x1635;
    private static final byte[] REQUEST = {0x22, 0x49, 0x6D};

    public static void main(String[] args) {
        try {
            read(args.length > 0 && args[0].equals("--guard-only"));
            System.exit(0);
        } catch (Throwable error) {
            if (error instanceof InvocationTargetException && error.getCause() != null) {
                error = error.getCause();
            }
            error.printStackTrace();
            System.exit(1);
        }
    }

    /** Park guard from VHAL: GEAR_SELECTION 0x11400400 and CURRENT_GEAR 0x11400401 must both be 4 (Park). */
    static final class VhalParkGuard {
        private static final long MAX_AGE_NS = TimeUnit.MILLISECONDS.toNanos(1500);
        private volatile long parkedAt; // nanoTime of the last read that saw Park on both properties, 0 = never
        private volatile String last = "no read yet";
        private volatile boolean running = true;
        private final Thread poller = new Thread(() -> {
            while (running) {
                try {
                    int selection = read("11400400"), current = read("11400401");
                    last = "selection=" + selection + " current=" + current;
                    if (selection == 4 && current == 4) parkedAt = System.nanoTime();
                    else parkedAt = 0;
                } catch (Exception e) {
                    last = "read failed: " + e.getMessage();
                    parkedAt = 0;
                }
            }
        });

        VhalParkGuard() { poller.setDaemon(true); poller.start(); }

        private static int read(String prop) throws Exception {
            Process p = new ProcessBuilder("cmd", "car_service", "get-property-value", prop, "0").redirectErrorStream(true).start();
            String out = new String(readAll(p.getInputStream()), "UTF-8");
            p.waitFor();
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Property:0x" + prop + ",status: 0,.*?int32Values: \\[(-?\\d+)\\]").matcher(out);
            if (!m.find()) throw new IOException("no status-0 value for 0x" + prop);
            return Integer.parseInt(m.group(1));
        }

        private static byte[] readAll(java.io.InputStream in) throws IOException {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int n; (n = in.read(chunk)) > 0; ) buffer.write(chunk, 0, n);
            return buffer.toByteArray();
        }

        void awaitPark() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                long at = parkedAt;
                if (at != 0 && System.nanoTime() - at < MAX_AGE_NS) return;
                Thread.sleep(100);
            }
            throw new IOException("Park not confirmed: " + last);
        }

        /** Throws unless a fresh read saw Park. */
        void check() throws IOException {
            long at = parkedAt;
            if (at == 0 || System.nanoTime() - at > MAX_AGE_NS) throw new IOException("Left Park or gear unknown: " + last);
        }

        String description() { return last; }

        void close() { running = false; }
    }

    private static void read(boolean guardOnly) throws Exception {
        String sdkApk = "/system/app/Dxapp/Dxapp.apk";
        ClassLoader parent = ReadBecmDid.class.getClassLoader();
        VhalParkGuard park = new VhalParkGuard();
        try {
            park.awaitPark();
            System.out.println("Park confirmed: " + park.description());
            if (guardOnly) {
                for (int i = 0; i < 16; i++) { park.check(); Thread.sleep(125); }
                System.out.println("Guard held Park for 2 s: " + park.description());
                return;
            }
            PathClassLoader sdk = new PathClassLoader(sdkApk,
                sdkApk + "!/lib/arm64-v8a:/vendor/lib64", parent);
            Class<?> api = sdk.loadClass("com.geely.udsmiddleware.VehicleDiagIF");
            Class<?> listener = sdk.loadClass("com.geely.udsmiddleware.IVehicleDiagListener");
            Object client = api.getConstructor().newInstance();
            LinkedBlockingQueue<Object> responses = new LinkedBlockingQueue<>();
            AtomicBoolean awaiting = new AtomicBoolean();
            Object callback = Proxy.newProxyInstance(sdk, new Class<?>[]{listener}, (proxy, method, values) -> {
                if (!awaiting.get()) {
                    return null;
                }
                if (method.getName().equals("onUdsResponse")
                        && (Integer) values[0] == MODE && (Character) values[1] == BECM
                        && (Character) values[2] == TESTER) {
                    byte[] data = (byte[]) values[4];
                    int length = (Integer) values[3];
                    if (data != null && length > 0 && length <= data.length) {
                        responses.offer(Arrays.copyOf(data, length));
                    }
                } else if (method.getName().equals("onDoIPMessageSendNackNotification")) {
                    responses.offer("DoIP NACK " + values[0]);
                }
                return null;
            });
            api.getMethod("registeListener", listener).invoke(client, callback);
            // This SDK build uses process-local GOS configuration for a local client.
            // Its local init path stores the Context but does not dereference it.
            System.out.println("SDK init: " + api.getMethod("init", Context.class).invoke(client, new Object[]{null}));
            Method status = api.getMethod("getStatusWithMiddleware");
            long readyBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!(Boolean) status.invoke(client)) {
                park.check();
                if (System.nanoTime() >= readyBy) {
                    throw new IOException("Middleware did not become ready");
                }
                Thread.sleep(100);
            }
            System.out.println("Middleware ready");
            Method getMode = api.getMethod("getCurDiagType");
            int currentMode = (Integer) getMode.invoke(client);
            if (currentMode != 0) {
                throw new IOException("Diagnostic channel busy, mode " + currentMode);
            }
            park.check();
            int result = (Integer) api.getMethod("takeDiagWay", int.class, char.class, int.class)
                .invoke(client, MODE, TESTER, 1);
            System.out.println("Channel acquisition result: " + result);
            if (result != 0) {
                throw new IOException("Middleware declined the diagnostic channel");
            }
            try {
                park.check();
                if ((Integer) getMode.invoke(client) != MODE) {
                    throw new IOException("Requested diagnostic mode did not become active");
                }
                awaiting.set(true);
                boolean accepted = (Boolean) api.getMethod("sendUdsPayload",
                    int.class, char.class, char.class, int.class, byte[].class)
                    .invoke(client, MODE, TESTER, BECM, REQUEST.length, REQUEST);
                System.out.println("Send 22 49 6D accepted: " + accepted);
                if (!accepted) {
                    throw new IOException("Middleware rejected the request");
                }
                awaitResponse(responses, park);
            } finally {
                awaiting.set(false);
                if ((Integer) getMode.invoke(client) == MODE) {
                    api.getMethod("releaseDiagWay").invoke(client);
                    System.out.println("Diagnostic channel released");
                }
            }
        } finally {
            park.close();
        }
    }

    private static void awaitResponse(LinkedBlockingQueue<Object> responses, VhalParkGuard park)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            park.check();
            Object item = responses.poll(125, TimeUnit.MILLISECONDS);
            if (item instanceof String) {
                throw new IOException((String) item);
            }
            if (!(item instanceof byte[])) {
                continue;
            }
            byte[] reply = (byte[]) item;
            boolean positive = reply.length >= 3 && reply[0] == 0x62 && reply[1] == 0x49 && reply[2] == 0x6D;
            boolean negative = reply.length == 3 && reply[0] == 0x7F && reply[1] == 0x22;
            if (!positive && !negative) {
                continue;
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : reply) {
                hex.append(String.format(Locale.ROOT, "%02X ", b & 0xFF));
            }
            System.out.println("BECM response: " + hex.toString().trim());
            if (!negative || reply[2] != 0x78) {
                return;
            }
        }
        throw new IOException("No final BECM UDS response before timeout");
    }
}
